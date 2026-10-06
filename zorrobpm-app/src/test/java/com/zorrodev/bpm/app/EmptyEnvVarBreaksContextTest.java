package com.zorrodev.bpm.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * WO-CFG-1, критерий 3, обратная сторона — почему в {@code docker-compose.yml} у перечисленных
 * ручек обязаны стоять ЯВНЫЕ умолчания, а не {@code ${VAR:-}}.
 *
 * <p><b>Ловушка, найденная при починке.</b> Spring применяет умолчание в
 * {@code ${VAR:default}} только когда переменной окружения НЕТ. Если переменная есть, но пустая,
 * подставляется ПУСТАЯ СТРОКА — умолчание не срабатывает (проверено живьём на
 * spring-core 7.0.9: {@code ${VAR:10}} при {@code VAR=""} даёт {@code ""}, при отсутствии
 * переменной — {@code 10}). А {@code docker-compose.yml} до правки объявлял девять таких ручек
 * как {@code ${VAR:-}}: {@code docker compose config} печатает для них
 * {@code SCRIPT_QUEUE_CAPACITY: ""} — переменная УСТАНОВЛЕНА и ПУСТА.
 *
 * <p>До правки это было безвредно: файл движка не читался, ключа в Environment не было, и
 * {@code @Value} брал умолчание из кода. Как только файл начали читать, пустые строки из
 * compose затирали дефолты файла, и бин {@code ScriptServiceImpl} переставал создаваться:
 * {@code ""} → {@code int} не конвертируется. То есть починка импорта в одиночку уронила бы
 * старт приложения.
 *
 * <p>Этот тест фиксирует механику на настоящем контексте приложения, чтобы «сокращение»
 * {@code ${VAR:-10}} обратно до {@code ${VAR:-}} было невозможно тихо. Партнёрская проверка
 * текста compose — Guard D в {@link ModuleConfigImportGuardTest}.
 *
 * <p>Что обязан роняться при откате правки: если убрать из файла движка плейсхолдер
 * {@code zorrobpm.engine.script-queue-capacity=${SCRIPT_QUEUE_CAPACITY:10}} (или вернуть ему
 * имя {@code application.properties}, и импорт перестанет его читать), контекст перестанет
 * падать — и утверждение ниже осмысленно потребует {@code SCRIPT_QUEUE_CAPACITY} равным
 * умолчанию {@code 10}, то есть тест покраснеет на несостоявшемся ассерте.
 */
class EmptyEnvVarBreaksContextTest {

    /**
     * Контекст приложения с ОДНОЙ переменной, подставленной ровно так, как её подставлял
     * compose до правки: имя есть, значение — пустая строка.
     */
    @Test
    void emptyEnvVarForEngineKnob_stopsTheApplicationContextFromStarting() {
        Map<String, Object> composeEmptyEnv = new LinkedHashMap<>();
        composeEmptyEnv.put("SCRIPT_QUEUE_CAPACITY", "");

        Throwable failure = catchThrowable(() -> runAppWithContainerEnv(composeEmptyEnv));

        assertThat(failure)
            .as("пустая переменная окружения обязана ломать бин ScriptServiceImpl (её значение "
                + "попадает в конструктор через @Value как int) — именно поэтому compose обязан "
                + "нести умолчание явно. Если этот ассерт перестанет падать, значит файл движка "
                + "снова не читается (вернулось перекрытие) — и дефект WO-CFG-1 воспроизведён")
            .isNotNull();

        // Сообщение Spring при неудачной конвертации @Value в параметр конструктора называет
        // бин и номер параметра («constructor parameter 6»), но НЕ имя свойства — поэтому
        // проверяем по бину-потребителю и по самой механике (пустая строка → int).
        assertThat(describeChain(failure))
            .as("падение должно быть ровно про конвертацию значения ручки пула в int, а не про "
                + "что-то побочное — иначе тест доказывает не то. ScriptServiceImpl принимает "
                + "zorrobpm.engine.script-queue-capacity параметром конструктора №6")
            .contains("scriptServiceImpl")
            .contains("constructor parameter 6")
            .contains("to required type 'int'")
            .contains("For input string: \"\"");
    }

    /**
     * Тот же контекст, но переменной НЕТ вовсе — так, как если бы compose её не объявлял.
     * Ручка обязана получить умолчание файла и контекст — подняться.
     */
    @Test
    void absentEnvVarForEngineKnob_fallsBackToThePropertiesFileDefault() {
        Throwable failure = catchThrowable(() -> runAppWithContainerEnv(Map.of()));

        assertThat(failure)
            .as("без переменной окружения контекст обязан подниматься, а ручка — получить "
                + "умолчание из файла. Падение здесь означает, что либо файл не импортирован, "
                + "либо его содержимое сломано")
            .isNull();
    }

    // ------------------------------------------------------------------ инфраструктура

    private static void runAppWithContainerEnv(Map<String, Object> containerEnv) {
        ConfigurableApplicationContext ctx = null;
        try {
            // Аргументы запуска, а НЕ properties(...): у SpringApplicationBuilder#properties
            // они попадают в defaultProperties, а те имеют НИЗШИЙ приоритет — application.properties
            // их перебил бы, и контекст пошёл бы в PostgreSQL вместо H2. Проверено: до этой
            // правки падение было «Connection to localhost:5432 refused», то есть про совсем другое.
            ctx = new SpringApplicationBuilder(APP.class)
                .web(WebApplicationType.SERVLET)
                // Профиль test — единственный, на котором AdminPasswordValidator (WO-SEC-14,
                // fail-fast на дефолтный 'admin') не валит старт. Без него падение было бы
                // про пароль, а не про конвертацию ручки пула.
                .profiles("test")
                .initializers(c -> c.getEnvironment().getPropertySources().addFirst(
                    new SystemEnvironmentPropertySource("containerEnv", containerEnv)))
                .run(
                    "--spring.datasource.url=jdbc:h2:mem:cfg1emptyenv",
                    // Каждый прогон — своя БД: H2 в памяти по имени переживает весь JVM, и падение
                    // на середине инициализации контекста не должно ломать следующий прогон.
                    "--spring.datasource.username=sa",
                    "--spring.datasource.password=",
                    "--spring.rabbitmq.host=localhost",
                    "--spring.rabbitmq.port=11002",
                    "--spring.rabbitmq.username=zorrodev",
                    "--spring.rabbitmq.password=zorrodev",
                    "--spring.rabbitmq.listener.simple.auto-startup=false",
                    "--app.filesDir=target/files",
                    "--zorrobpm.security.rate-limit.enabled=false",
                    "--server.forward-headers-strategy=framework",
                    "--server.port=0");
        } finally {
            if (ctx != null) {
                ctx.close();
            }
        }
    }

    private static String describeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cur = t; cur != null && cur != cur.getCause(); cur = cur.getCause()) {
            sb.append(cur.getClass().getName()).append(": ").append(cur.getMessage()).append('\n');
        }
        return sb.toString();
    }
}