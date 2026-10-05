package com.zorrodev.bpm.httpconnector;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * WO-ENG-31 Фаза 2: конфигурация встроенного HTTP/REST outbound-коннектора.
 *
 * <p>Все ключи — {@code zorrobpm.http-connector.*}. Значения валидируются на старте
 * ({@link HttpConnectorStartupValidator}, fail-fast в стиле WO-SEC-68/WO-SEC-80),
 * здесь только биндинг с безопасными дефолтами.
 *
 * <p>Security-семантика дефолтов (решение CTO п.2): коннектор выключен, пока админ
 * явно не включит ({@code enabled=false}); пустой {@code allowed-hosts} = deny-all
 * (fail-closed); {@code allow-private-networks=true} допустим только вне prod-профиля.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "zorrobpm.http-connector")
public class HttpConnectorProperties {

    /** Мастер-выключатель. {@code false} (дефолт) — воркер отклоняет каждую задачу
     *  детерминированной BPMN-ошибкой {@code HTTP_CONNECTOR_DISABLED}. */
    private boolean enabled = false;

    /** Allowlist целевых хостов (DNS-имена/суффиксы/литеральные IP, через запятую).
     *  Пусто (дефолт) = deny-all. Совпадение: точное имя или суффикс через точку
     *  ({@code api.example.com} покрывает и {@code api.example.com}, и {@code *.api.example.com}). */
    private String allowedHosts = "";

    /** Разрешить приватные/зарезервированные диапазоны IP (dev/staging против внутренних API).
     *  {@code true} в prod-профиле — FATAL на старте (зеркало WO-SEC-68). */
    private boolean allowPrivateNetworks = false;

    /** Жёсткий кап тела ответа в байтах. Превышение — BPMN-ошибка, не silent-truncate. */
    private long maxResponseBytes = 1024L * 1024L;

    /** Дефолтные таймауты в секундах (зеркало Camunda-дефолтов 20/20). */
    private int defaultConnectionTimeoutSeconds = 20;
    private int defaultReadTimeoutSeconds = 20;

    /** Капы сверху: BPMN-конфиг не может поставить таймаут выше этих. */
    private int maxConnectionTimeoutSeconds = 120;
    private int maxReadTimeoutSeconds = 300;

    /** Сколько редиректов (3xx) следовать, каждый хоп re-валидируется SSRF-гейтом.
     *  {@code 0} (дефолт, зеркало Camunda {@code followRedirects=false}) — не следовать. */
    private int maxRedirects = 0;

    /**
     * Server-side secret store минимум Фазы 2 (решение CTO п.6: env/конфиг достаточен,
     * Vault — отдельный WO). Ключ — имя секрета ({@code http.authRef}), значение —
     * JSON-объект вида {@code {"type":"bearer","token":"..."}},
     * {@code {"type":"basic","username":"...","password":"..."}},
     * {@code {"type":"apiKey","name":"X-Key","value":"...","in":"header"}} («in» —
     * {@code header} или {@code query}, дефолт {@code header}).
     * Биндится из {@code zorrobpm.http-connector.secrets.<name>} (env/properties).
     */
    private Map<String, String> secrets = new HashMap<>();

    /**
     * NEW5-07 (WO-QW-10): те же секреты одним JSON-объектом в одной переменной —
     * {@code ZORROBPM_HTTP_CONNECTOR_SECRETS_JSON='{"svc":{"type":"bearer","token":"…"}}'}.
     *
     * <p>Зачем вторая ручка, если есть {@link #secrets}: произвольные имена секретов
     * невозможно перечислить в статическом {@code application.properties}
     * ({@code zorrobpm.http-connector.secrets.<name>}), а список из 7 скаляров в
     * {@code docker-compose.yml} оставлял {@code authType != none} физически
     * недостижимым в штатном compose-деплое — ручная правка compose была единственным
     * способом. Имена с дефисом в env-переменной тоже не выражаются (Spring склеит
     * {@code _} в путь), поэтому поштучный паттерн {@code SECRETS_<NAME>} не годится.
     *
     * <p>Приоритет: явный {@link #secrets} перекрывает значение отсюда (прямая
     * настройка важнее блоба). Разбирается один раз в конструкторе воркера; битый
     * JSON валит старт (fail-fast), а не первый HTTP-вызов в проде.
     *
     * <p>Осознанная граница: секрет в переменной окружения виден в {@code docker inspect}.
     * Это ровно так же, как все остальные секреты проекта (JWT, пароль почты), и смена
     * модели на смонтированный файл — отдельное решение (файл/том меняет топологию
     * деплоя), не молчаливый побочный эффект этого фикса.
     */
    private String secretsJson = "";
}
