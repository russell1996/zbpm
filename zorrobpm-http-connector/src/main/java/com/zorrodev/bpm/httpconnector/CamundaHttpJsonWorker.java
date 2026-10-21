package com.zorrodev.bpm.httpconnector;

import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.handler.JobHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * WO-ENG-32: движок понимает камундовский REST-коннектор — service-task, смоделированный
 * штатным темплейтом Camunda Modeler ({@code io.camunda.connectors.HttpJson.v2}),
 * исполняется БЕЗ переписывания элемента на наш диалект.
 *
 * <p>Структура решения — ровно та же, что у варианта (b) из WO-ENG-31 Фазы 1: движок не
 * трогаем, совместимость живёт в коннекторе. Здесь она сделана ВТОРЫМ бином
 * {@link JobHandler} с {@link #JOB_TYPE}: имя очереди строится стартером воркеров из
 * {@code getJob()} ({@code HandlerAutoConfiguration}: {@code zorrobpm.jobs.} + type), а
 * один бин = одна очередь, поэтому алиас — отдельный бин, а не параметр.
 *
 * <p>ЕДИНЫЙ вход в исполнение: перевод строит НОВОЕ тело задания в нашем диалекте
 * ({@code http.*}) и отдаёт его тому же {@link HttpConnectorWorker}. Второго параллельного
 * пути нет — значит, один SSRF-гейт, одно разрешение секретов, один набор кап и одна
 * раскладка ошибок (прецедент P-66: единственная живая точка контроля).
 *
 * <p>Диалекты совместимости (поле → поле):
 * <pre>
 *   url                          → http.url
 *   method                       → http.method
 *   headers                      → http.headers
 *   queryParameters              → http.queryParameters
 *   body                         → http.body
 *   connectionTimeoutInSeconds   → http.connectionTimeout
 *   readTimeoutInSeconds         → http.readTimeout
 *   authentication.type          → http.authType
 *   http.authRef                 → http.authRef   (в Camunda-шаблоне такого входа нет,
 *                                                 автор дописывает один input с ИМЕНЕМ секрета)
 * </pre>
 *
 * <p>Fail-closed на всём, что мы выполнить не можем (решения по вопросам 2-5 WO —
 * эскалация от 2026-10-06 в {@code docs/internal}, здесь по коду):
 * <ul>
 *   <li>любой вход {@code authentication.*}, кроме {@code .type}, и любой {@code clientTls.*}
 *       — reject: инлайн-секреты в модели процесса не принимаются (тот же принцип, что
 *       {@code rejectLiteralSecrets} у нашего диалекта);</li>
 *   <li>{@code authentication.type} вне {@code noAuth/basic/bearer/apiKey} (то есть OAuth) —
 *       reject «отдельный WO», а НЕ молчаливый «none»;</li>
 *   <li>{@code followRedirects=true} — reject: бюджет редиректов задаёт администратор
 *       ({@code zorrobpm.http-connector.max-redirects}), расширять его из BPMN нельзя;</li>
 *   <li>taskHeader {@code resultVariable} / {@code errorExpression} — reject: это авторские
 *       ожидания («положи ответ в переменную X» / «это моя логика ошибок»), которые мы не
 *       исполняем, а молчать о них нельзя;</li>
 *   <li>taskHeader {@code resultExpression} — WARN и игнор (см. {@link #warnResultExpression}).</li>
 * </ul>
 *
 * <p>«Пустое FEEL» ({@code value="="}, как Camunda Modeler пишет нетронутое обязательное
 * FEEL-поле) считается ОТСУТСТВИЕМ, а не ожиданием — см. {@link #isSet}. Проверено мутацией:
 * с наивной проверкой «не пусто» свежеприменённый шаблон отвергался целиком.
 *
 * <p>Ключи taskHeader сравниваются без учёта регистра и обрезки краёв: иначе отказ
 * обходится опечаткой автора в имени ключа.
 *
 * <p>Вход {@code authenticationConfiguration} (ссылка на credentials-конфигурацию Camunda)
 * отвергается наравне с инлайн-секретами: у такого элемента нет входа
 * {@code authentication.type}, и без отказа он ушёл бы в НЕАУТЕНТИФИЦИРОВАННЫЙ вызов.
 *
 * <p>Игнорируются без предупреждения (свойства шаблона, не влияющие на результат и не
 * обещающие переменных): {@code storeResponse}, {@code ignoreNullValues}, {@code skipEncoding},
 * {@code documentReturnFormat.*}, {@code urlOverride} (тот же input {@code url}),
 * taskHeaders {@code elementTemplateId}/{@code elementTemplateVersion}.
 *
 * <p>Отдельная оговорка: taskHeaders {@code retryBackoff} и {@code jobTimeout} тоже
 * игнорируются, но их не понимает и движок (греп по `zorrobpm-engine`, `zorrobpm-rest`,
 * `zorrobpm-contract` — ни одного упоминания):
 * ретраи идут по {@code retries} из {@code zeebe:taskDefinition}. Поддержка backoff —
 * отдельная задача движка, не эта.
 */
@Slf4j
@Component
public class CamundaHttpJsonWorker implements JobHandler {

    /**
     * Ровно тот {@code type}, который пишет применяемый шаблон
     * {@code io.camunda.connectors.HttpJson.v2} (Hidden-свойство {@code type} в файле
     * {@code http-json-connector.json}). Префикс {@code io.camunda:http-json:*} сознательно
     * НЕ поддержан: на каждую версию нужен свой бин со своим именем очереди, а несуществующая
     * версия — это гипотеза. Честно фиксируем известный пробел в доке: на
     * {@code io.camunda:http-json:2} воркера не будет, и задача будет ждать воркера вечно —
     * это поведение движка для ЛЮБОГО неизвестного job-type, deploy-валидатор «известный
     * job-type» в этой WO запрещён отдельным пунктом и заводится отдельно.
     */
    static final String JOB_TYPE = "io.camunda:http-json:1";

    /** Префиксы входов Camunda, которые несут секрет или неподдерживаемую конфигурацию. */
    private static final List<String> REJECTED_INPUT_PREFIXES =
        List.of("authentication.", "clienttls.", "authenticationconfiguration");

    /** Единственный вход {@code authentication.*}, который мы читаем. */
    private static final String AUTH_TYPE_INPUT = "authentication.type";

    /**
     * Входы Camunda, которые переводятся или отвергаются и потому НЕ переносятся в
     * переведённое тело (в нижнем регистре — сравнение регистронезависимое).
     */
    private static final java.util.Set<String> CONSUMED_INPUTS = java.util.Set.of(
        AUTH_TYPE_INPUT, "url", "method", "headers", "queryparameters", "body",
        "connectiontimeoutinseconds", "readtimeoutinseconds", "followredirects");

    /**
     * WO-C8-58: вложенные контексты, которые переводятся/отвергаются и потому НЕ
     * копируются в переведённое тело как есть. После перехода движка на вложенные
     * target'ы точечный {@code authentication.type} приходит контекстом
     * {@code authentication: {type: …}} — его нельзя тащить в тело задания: это
     * пространство Camunda-диалекта, которое переводчик сам разбирает.
     */
    private static final java.util.Set<String> NESTED_CONSUMED_CONTEXTS = java.util.Set.of(
        "authentication", "clienttls");

    /**
     * WO-C8-58: пространство {@code http.*} в Camunda-диалекте принадлежит ПЕРЕВОДУ
     * (в него пишется {@code url → http.url} и т.д.). Вложенный контекст {@code http}
     * из процесса (например, completion предыдущего коннектора) в переведённое тело
     * НЕ копируется — иначе чужой контекст (заголовки/тело ЧУЖОГО ответа) уехал бы в
     * запрос этого элемента. Вместо копирования он разворачивается в плоские
     * {@code http.*}-ключи (данные не теряются, пространство остаётся плоским).
     */
    private static final String HTTP_CONTEXT = "http";

    /** Вложенный контекст авторизации Camunda ({@code authentication: {type: …}}). */
    private static final String AUTH_CONTEXT = "authentication";

    /** Вложенный контекст client TLS ({@code clientTls: {…}}) — не поддерживается. */
    private static final String CLIENT_TLS_CONTEXT = "clienttls";

    /** Единственный ключ, который принимается во вложенном контексте авторизации. */
    private static final String AUTH_CONTEXT_ALLOWED_KEY = "type";

    /**
     * Тип авторизации Camunda → наш {@code http.authType}. Ключи в нижнем регистре:
     * значение приходит строкой, регистр из шаблона менялся между версиями
     * ({@code noAuth} в v2). {@code apiKey} → {@code apikey}: наш {@code resolveAuth}
     * сравнивает тип секрета в нижнем регистре, и {@code apiKey} там уже приводится.
     */
    private static final Map<String, String> AUTH_TYPES = Map.of(
        "noauth", "none",
        "none", "none",
        "basic", "basic",
        "bearer", "bearer",
        "apikey", "apikey");

    private final HttpConnectorWorker delegate;
    private final ActivityService activityService;
    private final HttpConnectorProperties properties;

    public CamundaHttpJsonWorker(HttpConnectorWorker delegate, ActivityService activityService,
            HttpConnectorProperties properties) {
        this.delegate = delegate;
        this.activityService = activityService;
        this.properties = properties;
    }

    @Override
    public String getJob() {
        return JOB_TYPE;
    }

    @Override
    public List<ProcessVariable> handleJob(JobDetailModel model) {
        // Проверка «включён ли коннектор» — ПЕРВОЙ, ровно как на нашем диалекте. Иначе
        // выключенный коннектор отвечал бы на OAuth-элемент ошибкой про OAuth, а автор
        // читал бы «у меня что-то с авторизацией» вместо «об этом не думайте, это выключено
        // администратором» (оформительское #5 security review).
        if (!properties.isEnabled()) {
            HttpConnectorWorker.throwDeterministic(activityService, model,
                new HttpConnectorWorker.HttpConnectorConfigException(HttpConnectorWorker.ERR_DISABLED,
                    "zorrobpm.http-connector.enabled=false — ask the administrator to enable the connector"));
            return List.of();
        }
        JobDetailModel translated;
        try {
            translated = translate(model);
        } catch (HttpConnectorWorker.HttpConnectorConfigException e) {
            // Детерминированно и ДО сокета: ровно та же раскладка, что у нашего диалекта.
            HttpConnectorWorker.throwDeterministic(activityService, model, e);
            return List.of();
        }
        return delegate.handleJob(translated);
    }

    // ── перевод Camunda-входов → наш диалект ───────────────────────────────────

    /**
     * Новое тело задания в нашем диалекте. Идентификаторы, taskHeaders и приоритет
     * переносятся как есть (о них воркер ничего не решает), {@code variables} заменяются
     * переведёнными.
     *
     * <p>Прочие переменные процесса копируются без изменений — так работает авторский
     * input {@code http.authRef}, которого в шаблоне Camunda нет. Обратная сторона
     * (и она же — документированное свойство диалекта): переменная процесса, названная
     * {@code url}/{@code body}/{@code method}/… будет принята за вход коннектора. Camunda
     * предупреждает ровно об этом у своего коннектора.
     */
    static JobDetailModel translate(JobDetailModel model) {
        Map<String, ProcessVariable> source =
            model.getVariables() == null ? Map.of() : model.getVariables();
        Map<String, ProcessVariable> byLowerName = new LinkedHashMap<>();
        source.forEach((name, pv) -> byLowerName.put(name.toLowerCase(Locale.ROOT), pv));

        // WO-C8-58: чтение входов в ОБЕИХ формах — плоский ключ и вложенный путь
        // (движок после перехода пишет target с точкой как вложенный контекст).
        // Приоритет — вложенная форма; плоский ключ — fallback (сохранённое состояние
        // живых инстансов connCamunda v1 — плоские authentication.type).
        ConnectorInputLookup lookup = new ConnectorInputLookup(byLowerName);

        rejectUnsupportedAuthInputs(lookup);
        Map<String, String> taskHeaders =
            model.getTaskHeaders() == null ? Map.of() : model.getTaskHeaders();
        rejectUnsupportedHeaders(taskHeaders, model.getServiceTaskId());
        rejectFollowRedirects(lookup);

        // Всё, что переводится или отвергается, из тела уходит: Camunda-имена нам не нужны,
        // а лишние копии входов — это лишний шанс пробросить что-то вниз по конвейеру.
        // Остальные переменные процесса переносятся как есть (так работает авторский input
        // `http.authRef`, которого в шаблоне Camunda нет).
        Map<String, ProcessVariable> translated = new LinkedHashMap<>();
        source.forEach((name, pv) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (CONSUMED_INPUTS.contains(lower) || NESTED_CONSUMED_CONTEXTS.contains(lower)) {
                return;
            }
            if (HTTP_CONTEXT.equals(lower)) {
                // Вложенный контекст http — не копия: см. flattenHttpContext ниже.
                return;
            }
            translated.put(name, pv);
        });
        flattenHttpContext(lookup, translated);

        translateAuthType(lookup, translated);
        copy(lookup, translated, "url", "http.url");
        copy(lookup, translated, "method", "http.method");
        copy(lookup, translated, "headers", "http.headers");
        copy(lookup, translated, "queryparameters", "http.queryParameters");
        copy(lookup, translated, "body", "http.body");
        copy(lookup, translated, "connectiontimeoutinseconds", "http.connectionTimeout");
        copy(lookup, translated, "readtimeoutinseconds", "http.readTimeout");

        JobDetailModel translatedModel = new JobDetailModel();
        translatedModel.setServiceTaskId(model.getServiceTaskId());
        translatedModel.setProcessInstanceId(model.getProcessInstanceId());
        translatedModel.setProcessDefinitionId(model.getProcessDefinitionId());
        translatedModel.setServiceTaskKey(model.getServiceTaskKey());
        translatedModel.setJob(model.getJob());
        translatedModel.setVariables(translated);
        translatedModel.setTaskHeaders(model.getTaskHeaders());
        translatedModel.setPriority(model.getPriority());
        translatedModel.setDispatchPhase(model.getDispatchPhase());
        translatedModel.setDispatchIndex(model.getDispatchIndex());
        return translatedModel;
    }

    /**
     * Инлайн-секрет или неподдерживаемый блок авторизации/TLS в модели процесса —
     * {@code HTTP_CONNECTOR_CONFIG} ДО сокета. Единственный принимаемый вход —
     * {@code authentication.type} (тип, не значение); значение секрета приходит только
     * ссылкой {@code http.authRef} и хранится на сервере.
     *
     * <p>Правило «всё {@code authentication.*}, кроме {@code .type}» выбрано сознательно
     * вместо перечисления известных имён: шаблон Camunda версионируется, список полей
     * ({@code token}, {@code password}, {@code value}, {@code clientSecret},
     * {@code refreshToken}, {@code scopes}, …) меняется, а перечисление однажды устареет
     * и тихо пропустит новый секрет мимо reject'а.
     */
    private static void rejectUnsupportedAuthInputs(ConnectorInputLookup lookup) {
        // Обход в ОТСОРТИРОВАННОМ порядке: входы приходят из карты переменных, а порядок
        // обхода HashMap не задан. Без сортировки сообщение об ошибке называло бы произвольный
        // из отвергнутых входов — а это единственное, что читает автор процесса.
        for (String name : lookup.names().stream().sorted().toList()) {
            if (AUTH_TYPE_INPUT.equals(name)) {
                continue;
            }
            if (REJECTED_INPUT_PREFIXES.stream().anyMatch(name::startsWith)) {
                ProcessVariable rejected = lookup.flat(name);
                String original = rejected != null && rejected.getName() != null ? rejected.getName() : name;
                throw new HttpConnectorWorker.HttpConnectorConfigException(
                    HttpConnectorWorker.ERR_CONFIG,
                    "Camunda input '" + original + "' is not supported: " + authHint(name));
            }
        }
        // WO-C8-58: те же правила — для ВЛОЖЕННОЙ формы. После перехода движка на
        // вложенные target'ы точечный authentication.type приходит контекстом
        // authentication: {type: …}, а инлайн-секрет — authentication: {token: …}.
        // Без этой проверки правило «инлайн-секрет в модели не живёт» обходилось бы
        // простой сменой формы записи.
        com.fasterxml.jackson.databind.JsonNode clientTls = lookup.context(CLIENT_TLS_CONTEXT);
        if (clientTls != null) {
            throw new HttpConnectorWorker.HttpConnectorConfigException(
                HttpConnectorWorker.ERR_CONFIG,
                "Camunda input 'clientTls' is not supported: client TLS (mutual TLS) is not "
                    + "implemented by the connector — separate work order");
        }
        com.fasterxml.jackson.databind.JsonNode auth = lookup.context(AUTH_CONTEXT);
        if (auth != null) {
            List<String> keys = new java.util.ArrayList<>();
            auth.fieldNames().forEachRemaining(keys::add);
            for (String key : keys.stream().sorted().toList()) {
                if (!AUTH_CONTEXT_ALLOWED_KEY.equals(key)) {
                    throw new HttpConnectorWorker.HttpConnectorConfigException(
                        HttpConnectorWorker.ERR_CONFIG,
                        "Camunda input 'authentication." + key + "' is not supported: "
                            + authHint("authentication." + key));
                }
            }
        }
    }

    /** Подсказка в отказе — по названию входа (секрет или client TLS). */
    private static String authHint(String name) {
        return name.startsWith("clienttls.") || name.startsWith("clientTls.")
            ? "client TLS (mutual TLS) is not implemented by the connector — separate work order"
            : "inline secrets must not live in the process model — add an input with "
                + "target='http.authRef' holding the secret NAME and keep the secret "
                + "server-side (zorrobpm.http-connector.secrets / secrets-json)";
    }

    /**
     * taskHeaders, которые обещают результат или логику ошибок, которых у нас нет.
     * Пустое значение — отсутствие (шаблон пишет пустые строки), полное — ожидание.
     */
    private static void rejectUnsupportedHeaders(Map<String, String> taskHeaders, UUID serviceTaskId) {
        // Ключи zeebe:header приходят из BPMN дословно. Сравнение без учёта регистра и
        // обрезки краёв — иначе отказ обходится тривиальной опечаткой автора
        // (key="ResultVariable"), и заголовок молча ничего не сделает (L-1 security review).
        Map<String, String> normalized = new LinkedHashMap<>();
        taskHeaders.forEach((key, value) -> normalized.put(key.trim().toLowerCase(Locale.ROOT), value));
        if (isSet(normalized.get("resultvariable"))) {
            throw new HttpConnectorWorker.HttpConnectorConfigException(
                HttpConnectorWorker.ERR_CONFIG,
                "Camunda task header 'resultVariable' is not supported: the response is exposed as "
                    + "http.status / http.headers / http.body — map it with a zeebe:output io-mapping "
                    + "(e.g. source='http.body', target='myResponseBody')");
        }
        if (isSet(normalized.get("errorexpression"))) {
            throw new HttpConnectorWorker.HttpConnectorConfigException(
                HttpConnectorWorker.ERR_CONFIG,
                "Camunda task header 'errorExpression' is not supported (separate work order): "
                    + "a non-2xx response always raises the strict HTTP_<status> BPMN error, "
                    + "which a boundary error event may catch");
        }
        if (isSet(normalized.get("resultexpression"))) {
            warnResultExpression(serviceTaskId);
        }
    }

    /**
     * {@code resultExpression} приходит в КАЖДОМ элементе, к которому применён шаблон
     * Camunda (у свойства непустое значение по умолчанию), поэтому отказ здесь означал бы,
     * что «применить шаблон» не работает никогда — ровно то, ради чего WO заводилась.
     * FEEL-выражение мы не исполняем (второй движок выражений не заводим), ответ уходит в
     * штатную тройку {@code http.*}. Значение выражения в лог НЕ пишем — оно может
     * ссылаться на {@code secrets.*}.
     */
    private static void warnResultExpression(UUID serviceTaskId) {
        log.warn("Camunda task header 'resultExpression' on task {} is NOT evaluated by ZorroBPM: "
            + "the response is exposed as http.status / http.headers / http.body — select it with a "
            + "zeebe:output io-mapping", serviceTaskId);
    }

    /**
     * {@code followRedirects=true} из BPMN не расширяет бюджет редиректов: тот задаёт
     * администратор ({@code zorrobpm.http-connector.max-redirects}), и каждый хоп
     * заново проходит SSRF-гейт. Дефолт шаблона {@code false} совпадает с нашим
     * {@code max-redirects=0} и проходит молча.
     */
    private static void rejectFollowRedirects(ConnectorInputLookup lookup) {
        String raw = lookup.text("followredirects");
        if (raw == null || raw.isBlank()) {
            return;
        }
        String value = raw.strip();
        if ("false".equalsIgnoreCase(value)) {
            return;
        }
        if (!"true".equalsIgnoreCase(value)) {
            throw new HttpConnectorWorker.HttpConnectorConfigException(
                HttpConnectorWorker.ERR_CONFIG,
                "Camunda input 'followRedirects' must be 'true' or 'false' (got '"
                    + HttpConnectorWorker.sanitizeDiag(value) + "')");
        }
        throw new HttpConnectorWorker.HttpConnectorConfigException(
            HttpConnectorWorker.ERR_CONFIG,
            "Camunda input 'followRedirects=true' is not supported: the redirect budget is an "
                + "administrator setting (zorrobpm.http-connector.max-redirects) and must not be "
                + "widened from the process model — raise that setting and use the zorrobpm:http "
                + "dialect, or leave followRedirects=false");
    }

    /** {@code authentication.type} → {@code http.authType}; неизвестный тип (OAuth) — reject. */
    private static void translateAuthType(ConnectorInputLookup lookup,
            Map<String, ProcessVariable> translated) {
        // WO-C8-58: обе формы — плоский ключ authentication.type (сохранённое состояние
        // живых инстансов connCamunda v1) и вложенный контекст authentication: {type: …}
        // (то, что пишет движок после перехода на вложенные target'ы).
        String raw = lookup.text(AUTH_TYPE_INPUT);
        if (raw == null || raw.isBlank()) {
            return;
        }
        String type = AUTH_TYPES.get(raw.strip().toLowerCase(Locale.ROOT));
        if (type == null) {
            throw new HttpConnectorWorker.HttpConnectorConfigException(
                HttpConnectorWorker.ERR_CONFIG,
                "Camunda authentication.type '" + HttpConnectorWorker.sanitizeDiag(raw) + "' is not supported "
                    + "(separate work order): this connector supports no authentication, api keys, "
                    + "and username/password or token schemes; the secret itself is referenced by "
                    + "the input 'http.authRef'");
        }
        translated.put("http.authType", textVar("http.authType", type));
    }

    /**
     * Перенос входа в наше имя.
     *
     * <p>Кладём НОВЫЙ {@link ProcessVariable} с переименованным {@code name}, а не тот же
     * объект под другим ключом: {@code name} читается в местах, которые не обязаны смотреть
     * только на ключ карты (логи, диагностика, будущий потребитель), и расхождение «ключ
     * {@code http.url}, имя {@code url}» там выглядит как баг, который потом чинят полчаса.
     */
    private static void copy(ConnectorInputLookup lookup,
            Map<String, ProcessVariable> translated, String from, String to) {
        ProcessVariable pv = lookup.flat(from);
        if (pv != null) {
            ProcessVariable renamed = new ProcessVariable();
            renamed.setName(to);
            renamed.setValue(pv.getValue());
            renamed.setType(pv.getType());
            translated.put(to, renamed);
        }
    }

    /**
     * WO-C8-58: вложенный контекст {@code http} разворачивается в плоские
     * {@code http.*}-ключи. Пространство {@code http.*} в Camunda-диалекте принадлежит
     * ПЕРЕВОДУ, поэтому сам контекст в переведённое тело не копируется: иначе чужой
     * контекст процесса (completion предыдущего коннектора — его заголовки/тело ответа)
     * уехал бы в запрос этого элемента. Разворачивание сохраняет данные и оставляет
     * воркеру плоское пространство, как было до перехода.
     */
    private static void flattenHttpContext(ConnectorInputLookup lookup,
            Map<String, ProcessVariable> translated) {
        com.fasterxml.jackson.databind.JsonNode http = lookup.context(HTTP_CONTEXT);
        if (http == null) {
            return;
        }
        http.fields().forEachRemaining(entry -> {
            String key = HTTP_CONTEXT + "." + entry.getKey();
            ProcessVariable pv = new ProcessVariable();
            pv.setName(key);
            boolean container = entry.getValue().isContainerNode();
            pv.setValue(container ? entry.getValue().toString() : entry.getValue().asText());
            pv.setType(container ? "JSON" : "STRING");
            translated.put(key, pv);
        });
    }

    private static ProcessVariable textVar(String name, String value) {
        ProcessVariable pv = new ProcessVariable();
        pv.setName(name);
        pv.setValue(value);
        pv.setType("STRING");
        return pv;
    }

    /**
     * Есть ли в заголовке СОДЕРЖИМОЕ.
     *
     * <p>Camunda Modeler пишет нетронутое обязательное FEEL-поле как «пустое FEEL» — просто
     * {@code value="="}. Доказательство из документации Camunda: пустые значения не
     * персистятся ТОЛЬКО у биндингов с {@code optional: true}, а у свойства
     * {@code errorExpression} в шаблоне {@code io.camunda.connectors.HttpJson.v2}
     * (version 18) {@code feel: required} и {@code optional} не задан. Поэтому «просто
     * применить шаблон» без заполнения полей ошибки приносит заголовок
     * {@code errorExpression="="}, и наивная проверка «не пусто» отвергла бы валидный
     * элемент — ровно то, против чего заводился критерий 1 этой WO. Отличать надо по
     * СОДЕРЖИМОМУ: FEEL-маркер без выражения — это отсутствие, а не авторское ожидание.
     *
     * <p>Проверка fail-safe в обе стороны: {@code "="} и {@code "=   "} — отсутствие
     * (исполнять нечего), а любое выражение после маркера — авторское ожидание, режется.
     */
    private static boolean isSet(String value) {
        if (value == null) {
            return false;
        }
        String stripped = value.strip();
        if (stripped.startsWith("=")) {
            stripped = stripped.substring(1).strip();
        }
        return !stripped.isEmpty();
    }
}