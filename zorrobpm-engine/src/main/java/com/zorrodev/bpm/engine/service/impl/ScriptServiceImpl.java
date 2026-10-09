package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.metrics.BpmMetrics;
import com.zorrodev.bpm.engine.service.AdmissionLease;
import com.zorrodev.bpm.engine.service.ScriptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.SimpleScriptContext;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class ScriptServiceImpl implements ScriptService {

    private final ScriptEngine scriptEngine;
    private final ScriptEngine feelExpressionScriptEngine;
    private final ObjectMapper objectMapper;
    private final BpmMetrics bpmMetrics;
    private final long timeoutMs;
    /**
     * WO-ENG-24: admission control — сколько ждать освобождения пула/очереди,
     * прежде чем сбросить нагрузку вместо отката всей операции. Короткий
     * всплеск впитывается ожиданием, sustained-перегрузка по-прежнему
     * сбрасывается быстро (fail-fast), но уже как 503/retry-later, не 422.
     */
    private final long queueWaitMs;
    private final long queueWaitSeconds;
    /**
     * WO-ENG-35 (NEW2-16): двухфазный admission. Гейт-семафор считает те же
     * места, что пул+очередь ({@code poolSize + queueCapacity}): лиза,
     * выданная {@link #admitOutsideTx()} ДО транзакции, гарантирует место для
     * прямых сабмитов этого потока внутри транзакции. Fair (FIFO): при
     * sustained-перегрузке вставший раньше ждёт не дольше вставшего позже.
     *
     * <p>Почему это не второй bulkhead (запрет WO-ENG-20 п.2): семафор не
     * исполняет и не отбрасывает ничего сам — он только считает места того же
     * единственного пула; жёсткой границей остаётся сам executor.
     */
    private final java.util.concurrent.Semaphore admissionGate;
    /**
     * WO-ENG-35: резервация текущего потока, выданная гейтом (whole-op лиза)
     * или разовым разрешением (один eval вне гейтованного входа). Пока поле
     * хранит ЖИВУЮ резервацию, {@link #submitToPool} идёт напрямую в executor,
     * не ожидая admission внутри транзакции. Поток пула сюда не пишет никогда
     * (он исполняет, а не сабмитит) — дедлока «гейт ждёт пул, пул ждёт гейт»
     * нет по построению.
     *
     * <p>WO-ENG-35 раунд 2 (Б-1): значение — не голый семафор, а
     * {@link Reservation} с флагом живости. Протухшая резервация (лиза закрыта,
     * но holder не очищен — пропущенный {@code remove()}) НЕ даёт тихий
     * fail-open: {@link #submitToPool} видит {@code live == false}, сносит
     * holder, считает и предупреждает, и идёт обычным ограниченным путём
     * (fail-closed). Тест: {@code AdmissionOutsideTxTest} (holder-пины +
     * протухший holder).
     */
    private final ThreadLocal<Reservation> reservationHolder =
        new ThreadLocal<>();

    /**
     * WO-ENG-35 раунд 2 (Б-1): живая единица резервации. Флаг гаснет ровно
     * когда слот возвращён (close лизы / завершение разового eval): holder,
     * указывающий на погашенную резервацию, — протухший и доверять ему
     * нельзя.
     */
    static final class Reservation {
        final java.util.concurrent.atomic.AtomicBoolean live =
            new java.util.concurrent.atomic.AtomicBoolean(true);
    }
    /**
     * WO-REL-33 F22: пул ОДИН на все времена (final — никогда не заменяется).
     * Старой болезни «каждый timeout = новый executor + выжившие поколения»
     * больше нет: поколений нет вообще, суммарные потоки ≤ poolSize константно.
     * Non-cooperative задача пинит свой слот (неизбежно при любом bounded-дизайне),
     * дальше работает штатный bulkhead WO-A-02: очередь → AbortPolicy → быстрый
     * EngineException вместо утечки. Сосед не прерывается ничем (заменять нечего) —
     * инвариант WO-REL-24 сохранён доказанно его же тестом.
     */
    private final ThreadPoolExecutor executor;
    /**
     * WO-REL-46: троттлинг warn'а о насыщении — при полном простое каждый запрос
     * иначе писал бы warn и топил лог ровно в момент инцидента. 60с: насыщение
     * держится минутами (non-cooperative слот не освобождается сам), повтор
     * раньше — шум, не информация.
     */
    private static final long SATURATION_WARN_INTERVAL_MS = 60_000;
    private final AtomicLong lastSaturationWarnMs = new AtomicLong(0);

    public ScriptServiceImpl(@Qualifier("feelScriptEngine") ScriptEngine scriptEngine,
                              @Qualifier("feelExpressionScriptEngine") ScriptEngine feelExpressionScriptEngine,
                              ObjectMapper objectMapper,
                              BpmMetrics bpmMetrics,
                              @Value("${zorrobpm.engine.script-timeout-seconds:10}") long timeoutSeconds,
                              @Value("${zorrobpm.engine.script-pool-size:8}") int poolSize,
                              @Value("${zorrobpm.engine.script-queue-capacity:10}") int queueCapacity,
                              @Value("${zorrobpm.engine.script-queue-wait-seconds:5}") long queueWaitSeconds) {
        this.scriptEngine = scriptEngine;
        this.feelExpressionScriptEngine = feelExpressionScriptEngine;
        this.objectMapper = objectMapper;
        this.bpmMetrics = bpmMetrics;
        this.timeoutMs = timeoutSeconds * 1000;
        if (poolSize < 1) {
            throw new IllegalArgumentException(
                "zorrobpm.engine.script-pool-size must be >= 1, got " + poolSize);
        }
        if (queueCapacity < 1) {
            // WO-ENG-24: та же fail-fast валидация, что у script-pool-size рядом
            // (P-41) — новая ручка проходит ту же проверку, а не обходит её.
            throw new IllegalArgumentException(
                "zorrobpm.engine.script-queue-capacity must be >= 1, got " + queueCapacity);
        }
        if (queueWaitSeconds < 0) {
            throw new IllegalArgumentException(
                "zorrobpm.engine.script-queue-wait-seconds must be >= 0, got " + queueWaitSeconds);
        }
        this.queueWaitMs = queueWaitSeconds * 1000;
        this.queueWaitSeconds = queueWaitSeconds;
        // WO-ENG-35: мест в гейте столько же, сколько в пуле+очереди, —
        // лиза покрывает ровно один прямой сабмит.
        this.admissionGate = new java.util.concurrent.Semaphore(poolSize + queueCapacity, true);
        // WO-A-02: bounded bulkhead — bounded pool + bounded queue + abort policy.
        // WO-REL-46: размер конфигурируется (дефолт 8 — порог одновременных
        // зависших non-cooperative скриптов, нужный для полного outage, выше,
        // чем был при хардкоде 2).
        // WO-ENG-24: ёмкость очереди тоже конфигурируется (дефолт 10 — тот же
        // хардкод, что был; итоговая вместимость pool + queue обоснована ниже
        // в submitToPool относительно реальных вызывающих).
        this.executor = new ThreadPoolExecutor(
            poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            r -> {
                Thread t = new Thread(r, "script-eval");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
        bpmMetrics.setScriptPoolSize(poolSize);
    }

    @Override
    public Object evaluateScript(String script, List<ProcessVariable> variables) {
        return evalWithTimeout(scriptEngine, script, variables);
    }

    @Override
    public Object evaluateExpression(String expression, List<ProcessVariable> variables) {
        return evalWithTimeout(feelExpressionScriptEngine, expression, variables);
    }

    private Object evalWithTimeout(ScriptEngine engine, String code, List<ProcessVariable> variables) {
        ScriptContext ctx = buildContext(variables);
        String ref = codeRef(code);
        return awaitWithTimeout(submitToPool(() -> engine.eval(code, ctx), ref), ref);
    }

    /**
     * WO-ENG-20: {@link com.zorrodev.bpm.engine.service.ScriptService#runWithBudget} —
     * тот же пул/timeout/bulkhead/метрики, что у script task'ов, для задач, которые
     * нельзя выразить через {@code evaluateScript}/{@code evaluateExpression}.
     */
    public Object runWithBudget(java.util.concurrent.Callable<Object> task, String codeRef) {
        return awaitWithTimeout(submitToPool(task, codeRef), codeRef);
    }

    /**
     * WO-ENG-35 (NEW2-16): фаза 1 — резервирование места ДО открытия
     * транзакции. Вызывающие: {@code RuntimeResource} (12 HTTP-входов),
     * {@code ServiceTaskCompletionProcessor} (AMQP), {@code TimerBatchProcessor}
     * (fire таймеров) — держат лизу всё время операции.
     */
    @Override
    public AdmissionLease admitOutsideTx() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // WO-ENG-35: деградация, а не fail-fast. Легитимный путь запроса с
            // Idempotency-Key идёт через IdempotencyFilter, который выполняет
            // цепочку (включая контроллер) внутри своей транзакции, — жёсткий
            // отказ превращал бы его в 500 (поймано полным clean verify:
            // IdempotencyFilterTest 4× «expected 201 but was 500»). Ожидание
            // здесь — то же ограниченное окно, считается как in-tx и видно
            // оператору; вынос гейта до фильтра — отдельная эскалация.
            bpmMetrics.scriptAdmissionInTx();
            log.warn("Script admission inside caller transaction "
                + "(e.g. idempotent request via IdempotencyFilter): "
                + "bounded wait holds the transaction, counted in zbpm.script.admission.in_tx");
        }
        if (reservationHolder.get() != null && reservationHolder.get().live.get()) {
            // Реентрантный вход того же потока (гейтованный вход зовёт
            // гейтованный вход): слот уже держит внешний владелец.
            return AdmissionLease.noop();
        }
        if (reservationHolder.get() != null) {
            // WO-ENG-35 раунд 2 (Б-1): протухшая резервация, вытесненная новым
            // гейтом, — сносим молча здесь (громкий учёт был там, где её
            // ИСПОЛЬЗОВАЛИ: см. submitToPool — сюда доходят только после
            // очистки или вообще без holder).
            reservationHolder.remove();
        }
        boolean admitted = false;
        try {
            admitted = admissionGate.tryAcquire(queueWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new EngineException("Script admission wait interrupted");
        }
        if (!admitted) {
            bpmMetrics.scriptRejected();
            bpmMetrics.updateScriptPoolMetrics(executor);
            log.warn("Script rejected at admission gate (bulkhead overloaded, no tx held): "
                    + "{} workers active, queue full", executor.getActiveCount());
            throw new com.zorrodev.bpm.engine.service.ScriptOverloadException(
                "Script execution rejected: pool overloaded, retry later",
                (int) Math.max(1, queueWaitSeconds));
        }
        reservationHolder.set(new Reservation());
        Reservation own = reservationHolder.get();
        return new WholeOpLease(own);
    }

    /**
     * WO-ENG-35: whole-op лиза — слот держится всё время операции вызывающего
     * (все eval'ы операции идут напрямую), возвращается при закрытии.
     * Владелец — только выдавший поток; закрытие идемпотентно.
     *
     * <p>WO-ENG-35 раунд 2 (Б-1): close гасит флаг живости ПЕРВЫМ делом, а
     * очистку holder — явно. Мутация «убрать {@code reservationHolder.remove()}»
     * оставляет holder с погашенной резервацией: {@link #submitToPool} её
     * распознаёт и идёт fail-closed путём (тест
     * {@code holderClearedAfterClose_sameThread} краснеет прямо на
     * неочищенном holder, а протухший путь — на счётчике).
     */
    private final class WholeOpLease implements AdmissionLease {
        private final Reservation own;
        private boolean closed;

        WholeOpLease(Reservation own) {
            this.own = own;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            own.live.set(false);
            // Только владелец: чужой поток лизу не видит (holder — ThreadLocal),
            // реентрант получал noop и сюда не попадает. Сравнение по identity —
            // holder мог держать только эту резервацию (см. admitOutsideTx).
            if (reservationHolder.get() == own) {
                reservationHolder.remove();
            }
            admissionGate.release();
        }
    }

    /**
     * WO-ENG-20: сабмит в общий пул (выделено из {@code evalWithTimeout} без смены
     * семантики — saturation-warn, AbortPolicy-маппинг и сообщения те же).
     *
     * <p>WO-ENG-24: admission control вместо мгновенного AbortPolicy. Пул 8 +
     * очередь 10 = 18 мест: порог отказов в замере аудита — как раз число
     * одновременных вызывающих сверх pool+queue (timer-executor до 8, Rabbit
     * 3–5, Tomcat до 200 — перевалить за 18 в пике реально даже на дешёвых
     * выражениях). Поэтому мгновенный отказ при полном пуле сбрасывал целые
     * операции на переходном всплеске. Теперь при отказе {@code execute} задача
     * ждёт освобождения ограниченное время ({@code script-queue-wait-seconds},
     * дефолт 5с): короткий всплеск впитывается, sustained-перегрузка
     * по-прежнему сбрасывается — но уже как временная
     * ({@link com.zorrodev.bpm.engine.service.ScriptOverloadException} →
     * HTTP 503 + Retry-After), а не как неверный запрос (422).
     *
     * <p>WO-ENG-35 (NEW2-16): фаза 2 — привязка резервации. Если поток держит
     * лизу гейта (вход ждал ДО транзакции) или разовое разрешение — прямой
     * {@code execute} без ожидания внутри транзакции. Прямой сабмит тоже
     * может получить отказ (смешанный трафик, остановка пула): тогда —
     * прежний ограниченный offer-wait с меткой {@code in_tx}, а не новый
     * механизм. Без резервации поведение побайтово прежнее (фаза 1 просто не
     * вызывалась): мгновенная попытка → ограниченное ожидание → сброс 503.
     */
    private java.util.concurrent.Future<Object> submitToPool(
        java.util.concurrent.Callable<Object> task, String ref) {
        // WO-REL-46: ранний сигнал насыщения — все воркеры заняты, запрос сейчас
        // встанет в очередь. getActiveCount() приблизителен, для warn-уровня
        // достаточно; точная картина — в zbpm.script.pool.active/size.
        if (executor.getActiveCount() >= executor.getMaximumPoolSize()) {
            recordSaturation();
        }

        // WO-A-02: bulkhead — bounded queue rejects if pool is full (AbortPolicy)
        java.util.concurrent.FutureTask<Object> future = new java.util.concurrent.FutureTask<>(task);
        // WO-ENG-35: резервация этого потока (whole-op лиза гейта или разовое
        // разрешение) — место уже учтено в гейте, ждём ноль.
        // WO-ENG-35 раунд 2 (Б-1): доверяем только ЖИВОЙ резервации. Протухшая
        // (лиза закрыта, holder не очищен) — не тихий fail-open, а снос с
        // учётом и обычный ограниченный путь ниже (fail-closed).
        Reservation held = reservationHolder.get();
        if (held != null && !held.live.get()) {
            reservationHolder.remove();
            staleReservationCleared(ref);
            held = null;
        }
        boolean reserved = held != null;
        if (reserved) {
            try {
                executor.execute(future);
                return future;
            } catch (RejectedExecutionException mixedTraffic) {
                // Смешанный трафик (негейтованный поток занял последнее место)
                // или остановка пула: проваливаемся в прежний ограниченный
                // wait ниже (учёт in-tx — там, единой точкой).
            }
        } else if (admissionGate.tryAcquire()) {
            // WO-ENG-35: негейтованный вход, но место прямо сейчас есть —
            // разовое разрешение на один eval: ждём ноль и здесь, слот
            // возвращается по завершении eval (finally в awaitWithTimeout).
            Reservation one = new Reservation();
            reservationHolder.set(one);
            try {
                executor.execute(future);
                return new SingleEvalFuture(future, one);
            } catch (RejectedExecutionException raced) {
                one.live.set(false);
                if (reservationHolder.get() == one) {
                    reservationHolder.remove();
                }
                admissionGate.release();
                // Проваливаемся в прежний ограниченный wait ниже.
            } catch (RuntimeException | Error e) {
                one.live.set(false);
                if (reservationHolder.get() == one) {
                    reservationHolder.remove();
                }
                admissionGate.release();
                throw e;
            }
        }
        try {
            executor.execute(future);
            return future;
        } catch (RejectedExecutionException budgetFull) {
            // Сюда попадают только те, кто реально ждёт внутри транзакции
            // вызывающего (если она открыта): негейтованные пути, добивка
            // после отказа прямого сабмита, смешанный трафик. Единая точка
            // учёта — см. zbpm.script.admission.in_tx.
            submitInTxFallback(ref);
            // WO-ENG-24: пул+очередь полны прямо сейчас — ждём освобождения
            // ограниченное время вместо мгновенного отката операции. На уже
            // останавливающемся пуле ждать нечего — очередь примет, но никто
            // не выполнит (раньше здесь был мгновенный отказ, не timeout).
            boolean admitted = false;
            if (!executor.isShutdown()) {
                try {
                    admitted = executor.getQueue().offer(future, queueWaitMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            if (admitted) {
                return future;
            }
            bpmMetrics.scriptRejected();
            bpmMetrics.updateScriptPoolMetrics(executor);
            log.warn("Script rejected (bulkhead overloaded after {}ms admission wait): "
                    + "{} workers active, queue full", queueWaitMs, executor.getActiveCount());
            throw new com.zorrodev.bpm.engine.service.ScriptOverloadException(
                "Script execution rejected: pool overloaded (" + ref + "), retry later",
                budgetFull,
                (int) Math.max(1, queueWaitSeconds));
        }
    }

    /**
     * WO-ENG-35 раунд 2 (Б-1): протухшая резервация использована вместо живой.
     * Не тихий fail-open: сносим holder, считаем как in-tx (та же метрика, что
     * у прочих внутритранзакционных ожиданий) и предупреждаем — дальше обычный
     * ограниченный wait (при sustained-перегрузке он же даст 503, как всем).
     */
    private void staleReservationCleared(String ref) {
        bpmMetrics.scriptAdmissionInTx();
        log.warn("Stale script admission reservation on this thread ({}): "
                + "lease already closed, holder cleared — bounded wait follows, "
                + "counted in zbpm.script.admission.in_tx", ref);
    }

    /**
     * WO-ENG-35: метка «ожидание произошло внутри транзакции вызывающего»
     * (негейтованный путь, смешанный трафик, добивка после отказа прямого
     * сабмита). Операторский сигнал — см. {@code zbpm.script.admission.in_tx}.
     */
    private void submitInTxFallback(String ref) {
        bpmMetrics.scriptAdmissionInTx();
        log.debug("Script admission inside caller transaction ({}): "
                + "no gate reservation held, bounded wait follows", ref);
    }

    /**
     * WO-ENG-35: обёртка разового разрешения — возвращает слот гейта ровно
     * когда eval завершён (успех/ошибка/отмена/таймаут — любой исход через
     * {@code Future}, слот не зависит от исхода). Делегирует всё остальное
     * внутренней задаче без изменения семантики ожидания.
     */
    private final class SingleEvalFuture implements java.util.concurrent.Future<Object> {
        private final java.util.concurrent.Future<Object> delegate;
        private final Reservation own;
        private boolean released;

        SingleEvalFuture(java.util.concurrent.Future<Object> delegate, Reservation own) {
            this.delegate = delegate;
            this.own = own;
        }

        private synchronized void releaseOnce() {
            if (!released) {
                released = true;
                own.live.set(false);
                // Свой поток и свой слот: holder мог перезаписаться только
                // whole-op лизой этого же потока (вложенный гейтованный вход —
                // noop, holder не трогает); чистим только если там наш слот.
                if (reservationHolder.get() == own) {
                    reservationHolder.remove();
                }
                admissionGate.release();
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            try {
                return delegate.cancel(mayInterruptIfRunning);
            } finally {
                releaseOnce();
            }
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public Object get() throws InterruptedException, java.util.concurrent.ExecutionException {
            try {
                return delegate.get();
            } finally {
                releaseOnce();
            }
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
            throws InterruptedException, java.util.concurrent.ExecutionException,
            java.util.concurrent.TimeoutException {
            try {
                return delegate.get(timeout, unit);
            } finally {
                releaseOnce();
            }
        }
    }

    /**
     * WO-ENG-20: ожидание с timeout (выделено из {@code evalWithTimeout} без смены
     * семантики — сообщения, cancel, метрики те же).
     */
    private Object awaitWithTimeout(java.util.concurrent.Future<Object> future, String ref) {
        try {
            Object result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            // A-09: log length/hash, not full code
            log.debug("Eval result ({})", ref);
            return result;
        } catch (java.util.concurrent.TimeoutException e) {
            // WO-A-02: stuck-worker — точечный cancel; пул НЕ заменяется (F22:
            // single-pool — замена и была утечкой поколений). Non-cooperative
            // задача пиннит слот, остальное — штатный bulkhead.
            future.cancel(true);
            bpmMetrics.scriptTimeout();
            // A-09: no code in exception, correlation via length/hash
            throw new EngineException("Script execution timed out after " + (timeoutMs / 1000) + "s (" + ref + ")");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof javax.script.ScriptException se) throw new RuntimeException(se);
            throw new EngineException("Script execution failed (" + ref + "): " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineException("Script execution interrupted");
        } finally {
            // WO-REL-46: gauges свежие после КАЖДОГО eval, а не только после
            // timeout/reject — иначе Grafana-правило насыщения смотрит на
            // протухшие значения ровно тогда, когда они нужны.
            bpmMetrics.updateScriptPoolMetrics(executor);
        }
    }

    /**
     * WO-REL-46: троттлированный warn о насыщении пула (см. поле
     * {@code lastSaturationWarnMs}).
     */
    private void recordSaturation() {
        long now = System.currentTimeMillis();
        long last = lastSaturationWarnMs.get();
        if (now - last >= SATURATION_WARN_INTERVAL_MS && lastSaturationWarnMs.compareAndSet(last, now)) {
            log.warn("Script pool saturated: {}/{} workers active, new evaluations queue "
                    + "(pool size via zorrobpm.engine.script-pool-size)",
                executor.getActiveCount(), executor.getMaximumPoolSize());
            bpmMetrics.updateScriptPoolMetrics(executor);
        }
    }

    /**
     * WO-A-09: code reference for logging — length + hash, NOT full code.
     * WO-DIFF-10: null-safe — evaluateScript(null) падал ВТОРОЙ маскирующей NPE
     * прямо здесь, пряча реальную причину сбоя FEEL-движка.
     */
    private static String codeRef(String code) {
        if (code == null) {
            return "null";
        }
        return "len=" + code.length() + ",hash=" + code.hashCode();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private ScriptContext buildContext(List<ProcessVariable> variables) {
        ScriptContext ctx = new SimpleScriptContext();

        if (variables != null && !variables.isEmpty()) {
            for (ProcessVariable variable : variables) {
                ProcessVariableType type = variable.getType();
                if (type == ProcessVariableType.LONG) {
                    ctx.setAttribute(variable.getName(), Long.valueOf(variable.getValue()), ScriptContext.ENGINE_SCOPE);
                } else if (type == ProcessVariableType.BOOLEAN) {
                    ctx.setAttribute(variable.getName(), Boolean.valueOf(variable.getValue()), ScriptContext.ENGINE_SCOPE);
                } else if (type == ProcessVariableType.DOUBLE) {
                    // FEEL numbers are BigDecimal — pass decimals as such so arithmetic/comparison works
                    ctx.setAttribute(variable.getName(), new java.math.BigDecimal(variable.getValue()), ScriptContext.ENGINE_SCOPE);
                } else if (type == ProcessVariableType.JSON) {
                    // JSON object/list -> Java Map/List so FEEL can read nested properties (order.total) and iterate
                    ctx.setAttribute(variable.getName(), objectMapper.readValue(variable.getValue(), Object.class), ScriptContext.ENGINE_SCOPE);
                } else {
                    ctx.setAttribute(variable.getName(), variable.getValue(), ScriptContext.ENGINE_SCOPE);
                }
                log.debug("Variable {}", variable.getName());
            }
        }
        return ctx;
    }

}
