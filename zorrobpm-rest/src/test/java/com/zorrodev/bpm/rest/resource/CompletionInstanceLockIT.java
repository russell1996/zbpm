package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.contract.dto.StartProcessInstanceDTO;
import com.zorrodev.bpm.contract.dto.query.ServiceTaskQuery;
import com.zorrodev.bpm.contract.model.ProcessDefinition;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import com.zorrodev.bpm.engine.repository.ServiceTaskRepository;
import com.zorrodev.bpm.engine.repository.UserTaskRepository;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.engine.service.AuditLogService;
import com.zorrodev.bpm.engine.service.DBService;
import com.zorrodev.bpm.engine.service.ProcessDefinitionService;
import com.zorrodev.bpm.engine.service.QueryService;
import com.zorrodev.bpm.engine.service.RuntimeService;
import com.zorrodev.bpm.engine.service.db.ActivityDbOperations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * WO-ENG-28 (NEW3-05): complete сериализуется с cancel через instance-lock.
 *
 * <p>Два реальных потока/транзакции (V6): поток C — настоящий complete
 * service-task (`RuntimeService.completeServiceTask` → `ActivityServiceImpl`
 * (`@Transactional`) → `CompletionService`, реальный прод-путь целиком),
 * поток A — настоящая отмена
 * (`ProcessInstanceRuntimeOperationsImpl.cancelProcessInstance`, реальный бин).
 * Топология `eng28-svc-user.bpmn`: start → svc → usr → end.
 *
 * <p>Хореография (детерминирована барьерами, не таймингом):
 * <ol>
 *   <li>C проходит guard и встаёт на паузу ВНУТРИ finish, на
 *       `completeActivity(svc)` (крюк на spy `DBService.completeActivity` —
 *       срабатывает ОДИН раз, только в C: флаг armed взводится строго перед
 *       сабмитом C + identity по svcId + pool-поток; отмена activity-строки
 *       НЕ завершает — только CANCELLED-UPDATE — её крюк не трогает). К этому
 *       моменту C уже: прошёл guard, взял оба лока (activity-row FOR UPDATE
 *       И instance-row FOR UPDATE — порядок activity→instance), svc ещё НЕ
 *       done, usr-строка ещё НЕ создана; вся C-транзакция открыта.</li>
 *   <li>A стартует отмену и паркуется на instance-lock у входа (C держит оба
 *       FOR UPDATE). Парк ДОКАЗАН, не предположен: aEntered (A вошла в lock —
 *       хук отличает A от C-lock'а того же pi по имени потока C) + aPassedLock
 *       НЕ пройден + стабильность 100мс. Сон 800мс — лишь позиционирование
 *       наблюдения (100× запас на локальный путь entry→парк; failure mode
 *       короткого сна — громкий провал guard'а, не тихий пропуск).</li>
 *   <li>Main отпускает C → C выполняет finish (svc done, usr создана) и
 *       коммитит (cCommitted) → A разблокируется, снимает post-commit снапшот
 *       ([svc-done, usr-CREATED]) и отменяет usr.</li>
 * </ol>
 *
 * <p>Финал с фиксом (GREEN): svc=COMPLETED (complete реально отработал),
 * usr=CANCELLED (отмена реально убрала созданное), живых activity нет,
 * экземпляр cancelled. Порядок C-COMMIT &lt; A-BULK детерминирован бытом
 * lock'а: A физически не могла пройти lock до коммита C (парк-guard выше), а
 * её bulk-FIND идёт после lock — т.е. после коммита C.
 *
 * <p>Честная граница POF (V9, см. отчёт): в этом порядке зомби НЕВОЗМОЖЕН по
 * построению ни с фиксом, ни без (мутант GREEN — проверено 2×): у мутанта A
 * тоже паркуется (на activity-row через CGLIB-цепочку bulk-UPDATE) и тоже идёт
 * post-commit. Уязвимый интерливинг (A-FIND &lt; C-COMMIT) требует паузы A
 * между FIND и UPDATE — этого тест не делает. Прямое доказательство наличия
 * лока — unit-тесты `CompletionServiceTest.complete{Service,User}Task_`
 * `takesInstanceLock` (мутация `Wanted but not invoked`), а порядок
 * lock-сериализации — парк-guard'ы этого IT (A стоит, пока C не закоммитит).
 *
 * <p>Крюки — spy поверх реальных бинов (всё остальное — настоящий прод-путь,
 * прецедент `ProcessInstanceCancelLockIT`).
 */
@SpringBootTest(properties = {
    // Изолированная mem-БД: чужой сид не мешает + LOCK_TIMEOUT с запасом —
    // потоки in-flight держат FOR UPDATE дольше дефолтных 1-2с H2.
    "spring.datasource.url=jdbc:h2:mem:eng28;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000"
})
@ActiveProfiles("test")
class CompletionInstanceLockIT {

    /** Поток C встал на паузу ПОСЛЕ guard (иначе тест вакуумен — см. ниже). */
    private final CountDownLatch cPaused = new CountDownLatch(1);
    /** Отпустить поток C (когда A гарантированно запаркован). */
    private final CountDownLatch releaseC = new CountDownLatch(1);
    /** Complete C закоммитил (finish прошёл — Y создана, svc done). */
    private final CountDownLatch cCommitted = new CountDownLatch(1);

    /** Pause-hook вооружён (поток C); отмена идёт при разоружённом. */
    private final AtomicBoolean armed = new AtomicBoolean(false);
    /** Имя потока C (для отличия A-хука от C-lock'а того же pi). */
    private final AtomicReference<String> cThread = new AtomicReference<>();

    @Autowired private ProcessInstanceRuntimeOperations processInstanceRuntimeOperations;
    @Autowired private ProcessDefinitionService processDefinitionService;
    @Autowired private RuntimeService runtimeService;
    @Autowired private QueryService queryService;
    @Autowired private ProcessInstanceRepository processInstanceRepository;
    @Autowired private ProcessDefinitionRepository processDefinitionRepository;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private ServiceTaskRepository serviceTaskRepository;
    @Autowired private UserTaskRepository userTaskRepository;
    @MockitoSpyBean private DBService dbService;

    @MockitoBean private AuditLogService auditLogService;
    @MockitoBean private RuntimeOperationSupport runtimeOperationSupport;

    private UUID cleanupPi, cleanupPd;

    @AfterEach
    void cleanup() {
        if (cleanupPi != null) {
            // Порядок — от листьев к корню (иначе FK SERVICE_TASKS→INSTANCES
            // блокирует удаление инстанса): сначала task-строки, потом
            // activity, потом инстанс.
            for (var st : serviceTaskRepository.findByProcessInstanceId(cleanupPi)) {
                try {
                    serviceTaskRepository.deleteById(st.getId());
                } catch (Exception ignored) {
                }
            }
            for (var ut : userTaskRepository.findByProcessInstanceId(cleanupPi)) {
                try {
                    userTaskRepository.deleteById(ut.getId());
                } catch (Exception ignored) {
                }
            }
            for (ActivityEntity a : activityRepository.findByProcessInstanceIdAndStatusIn(
                cleanupPi, List.of(ActivityStatus.values()))) {
                try {
                    activityRepository.deleteById(a.getId());
                } catch (Exception ignored) {
                }
            }
            try {
                processInstanceRepository.deleteById(cleanupPi);
            } catch (Exception ignored) {
            }
            cleanupPi = null;
        }
        if (cleanupPd != null) {
            try {
                processDefinitionRepository.deleteById(cleanupPd);
            } catch (Exception ignored) {
            }
            cleanupPd = null;
        }
        armed.set(false);
    }

    @Test
    void completeServiceTask_serialisesWithCancel_noZombieActivity() throws Exception {
        String bpmn = Files.readString(Paths.get("src/test/files/eng28-svc-user.bpmn"));
        ProcessDefinition model = processDefinitionService.addProcessDefinition(bpmn);
        cleanupPd = model.getId();

        StartProcessInstanceDTO dto = new StartProcessInstanceDTO();
        dto.setProcessDefinitionId(model.getId());
        UUID piId = runtimeService.startProcessInstance(dto).getId();
        cleanupPi = piId;

        ServiceTaskQuery q = new ServiceTaskQuery();
        q.setProcessInstanceId(piId);
        var tasks = queryService.findServiceTasks(q, null);
        assertThat(tasks.getData()).hasSize(1);
        UUID svcId = tasks.getData().get(0).getId();

        when(runtimeOperationSupport.resolveDefinitionKeyByInstance(piId))
            .thenReturn(model.getKey());
        org.mockito.Mockito.doNothing().when(runtimeOperationSupport)
            .requireOperate(any(), any());
        when(runtimeOperationSupport.getPrincipal()).thenReturn(
            new Principal.UserPrincipal(UUID.randomUUID(), "test", "USER"));

        // Пауза ВНУТРИ finish C: на completeActivity (крюк на spy
        // `DBService.completeActivity(activityId)` — срабатывает ОДИН раз,
        // только в C: флаг armed взводится СТРОГО перед сабмитом C (setup выше
        // сам завершает activity через start — ранний armed съел бы хук в
        // main-потоке); отмена activity-строки НЕ завершает — только
        // CANCELLED-UPDATE (её completeActivity-крюк не трогает). К этому
        // моменту C уже: прошёл guard, взял оба лока (activity-row И instance —
        // порядок activity→instance). svc ещё НЕ done (завершение ниже крюка),
        // usr-строка ещё НЕ создана (создаётся ниже по finish); вся
        // C-транзакция открыта и держит оба FOR UPDATE.
        // A, стартующая в этот момент, паркуется на instance-lock у входа
        // (C держит оба FOR UPDATE) — парк доказывается guard'ами ниже
        // (aEntered + aPassedLock + стабильность). Release — после них.
        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            // Пауза — только если это complete svc ЦЕЛЕВОГО инстанса (setup и
            // чужие пути тоже завершают activity через этот же метод; armed
            // взведён строго перед сабмитом C, а identity сверяем по id).
            if (svcId.equals(inv.getArgument(0))
                && armed.compareAndSet(true, false)
                && Thread.currentThread().getName().startsWith("pool-")) {
                cPaused.countDown();
                try {
                    releaseC.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return result;
        }).when(dbService).completeActivity(any());

        // Подтверждение парка: A реально УПЁРЛАСЬ в instance-lock (не просто
        // «стартовала»). Крюк — на `DBService.lockProcessInstance`: advice на
        // ВХОД метода срабатывает до блокировки (блокировка — внутри real
        // метода), а возврат из real метода = A ПРОШЛА парк. Различаем:
        // aEntered (вошла в lock — in-flight доказано), aPassedLock (прошла —
        // C отпустил/закоммитил). В POF-прогоне ожидаем: entered=true ДО
        // release, passed=false ДО release (парк), passed=true ПОСЛЕ.
        final CountDownLatch aEntered = new CountDownLatch(1);
        final CountDownLatch aPassedLock = new CountDownLatch(1);
        doAnswer(inv -> {
            // Хук — только на ОТМЕНУ целевого инстанса: C (complete) тоже
            // берёт lockProcessInstance того же pi (фикс!), различаем по
            // потоку — A живёт в pool-пуле вторым потоком; надёжнее: по имени
            // потока C (захватываем при сабмите) — чужой поток = A.
            if (piId.equals(inv.getArgument(0))
                && !Thread.currentThread().getName().equals(cThread.get())) {
                aEntered.countDown();
                Object r = inv.callRealMethod();
                aPassedLock.countDown();
                return r;
            }
            return inv.callRealMethod();
        }).when(dbService).lockProcessInstance(any());



        AtomicReference<Throwable> cError = new AtomicReference<>();
        AtomicReference<Throwable> aError = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // C: настоящий complete — пройдёт guard и встанет на паузу.
            armed.set(true);
            Future<?> completeFuture = pool.submit(() -> {
                cThread.set(Thread.currentThread().getName());
                try {
                    runtimeService.completeServiceTask(svcId, List.of());
                    cCommitted.countDown();
                } catch (Throwable t) {
                    cError.set(t);
                }
                return null;
            });
            // Guard пройден (иначе тест вакуумен — complete проигнорирован и
            // гонять нечего): пауза стоит внутри finish, после guard и обоих
            // локов, до создания usr.
            assertThat(cPaused.await(10, TimeUnit.SECONDS))
                .as("setup guard: complete must pass its status guard and pause inside finish")
                .isTrue();

            // A: настоящая отмена — стартует, когда C in-flight (пауза внутри
            // finish: svc ещё НЕ done, usr-строка ещё НЕ создана; вся
            // C-транзакция открыта и держит оба FOR UPDATE).
            // С фиксом A паркуется на instance-lock у входа (C держит оба
            // FOR UPDATE) — парк доказывается guard'ами ниже (aEntered +
            // aPassedLock + стабильность). Позиционный сон 800мс — только
            // чтобы A успела УПЕРЕТЬСЯ в блокировку (100× запас на локальный
            // путь entry→парк; failure mode короткого сна — громкий провал
            // guard'а, не тихий пропуск).
            Future<?> cancelFuture = pool.submit(() -> {
                try {
                    processInstanceRuntimeOperations.cancelProcessInstance(piId);
                } catch (Throwable t) {
                    aError.set(t);
                }
                return null;
            });

            Thread.sleep(800);
            // Парк доказан (не предположен): A вошла в lock (in-flight), но НЕ
            // прошла его (стоит на FOR UPDATE, который держит C). Без этого
            // guard'а тест мог бы гонять свободный проход и быть вакуумен.
            assertThat(aEntered.await(10, TimeUnit.SECONDS))
                .as("cancel must enter its instance-lock while complete is paused")
                .isTrue();
            assertThat(aPassedLock.getCount())
                .as("cancel must be PARKED on the lock (not passed it) while complete holds it")
                .isEqualTo(1L);
            // Стабильность парка: A стоит, а не пролетает (100мс — только
            // детект пролёта, не timing-гейт: запаркованная A стоит
            // секундами до release, пролетевшая — уже прошла).
            Thread.sleep(100);
            assertThat(aPassedLock.getCount())
                .as("cancel must STILL be parked (stable, not flying through)")
                .isEqualTo(1L);

            // Прямое доказательство сериализации: A прошла lock (aPassedLock)
            // и сняла post-commit снапшот (см. usr=CANCELLED ниже), а C
            // закоммитил раньше (cCommitted). Порядок C-COMMIT < A-BULK
            // детерминирован бытом lock'а: A физически не могла пройти lock до
            // коммита C (парк-guard выше), а её bulk-FIND идёт после lock —
            // т.е. после коммита C. Зомби при таком порядке невозможен по
            // построению; ассерты ниже фиксируют факт.
            releaseC.countDown();
            assertThat(cCommitted.await(15, TimeUnit.SECONDS))
                .as("complete must commit (C holds both locks till commit)")
                .isTrue();

            cancelFuture.get(20, TimeUnit.SECONDS);
            completeFuture.get(20, TimeUnit.SECONDS);

            // A-error разбираем СРАЗУ: 409 CONFLICT (экземпляр уже завершён)
            // — тоже валидный исход гонки (отмена проиграла завершению), но
            // ТОГДА зомби-ассерт ниже обязан проверять завершённый экземпляр,
            // а не отменённый. Развилка — по факту, не по предположению.

            assertThat(cError.get())
                .as("complete thread must not throw")
                .isNull();
            assertThat(aError.get())
                .as("cancel thread must not throw")
                .isNull();

            // Экземпляр отменён отменой: cancelled=true (отмена ставит и
            // completedAt — см. ProcessInstanceDbOperationsImpl: cancel ends
            // the instance too; completedAt НЕ различает cancel от complete,
            // различает cancelled-флаг).
            ProcessInstanceEntity after = processInstanceRepository.findById(piId).orElse(null);
            assertThat(after).isNotNull();
            assertThat(after.isCancelled()).isTrue();

            // P-67: ни одной живой activity (зомби = CREATED/IN_PROGRESS на
            // отменённом экземпляре). Движок пишет activity-строки и на
            // startEvent/sequenceFlow (COMPLETED-фон) — утверждаем пары
            // (element, status), не голые счётчики.
            List<ActivityEntity> live = activityRepository.findByProcessInstanceIdAndStatusIn(
                piId, List.of(ActivityStatus.CREATED, ActivityStatus.IN_PROGRESS));
            assertThat(live)
                .as("no zombie activities on cancelled instance")
                .isEmpty();
            List<ActivityEntity> all = activityRepository.findByProcessInstanceIdAndStatusIn(
                piId, List.of(ActivityStatus.values()));
            java.util.Map<String, ActivityStatus> byElement = new java.util.HashMap<>();
            for (ActivityEntity a : all) {
                byElement.put(a.getBpmnElementId(), a.getStatus());
            }
            assertThat(byElement)
                .as("svc done by complete, usr cancelled by cancel — serialization, not mutual exclusion")
                .containsEntry("svc", ActivityStatus.COMPLETED)
                .containsEntry("usr", ActivityStatus.CANCELLED);
        } finally {
            pool.shutdownNow();
        }
    }
}
