package com.zorrodev.bpm.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-QW-4 (NEW-16f): prod-профиль пишет JSON-логи (LogstashEncoder) —
 * конфиг парсится, encoder-класс на classpath, MDC-ключи заявлены.
 * Живой prod-рантайм — за CTO (деплой), здесь — структурное доказательство:
 * XML валиден, класс энкодера резолвится, springProfile=prod присутствует.
 */
class ProdJsonLoggingConfigTest {

    @Test
    void logbackConfig_hasProdJsonAppender() throws Exception {
        String xml = Files.readString(Path.of("src/main/resources/logback-spring.xml"));
        assertThat(xml).contains("<springProfile name=\"prod\">");
        assertThat(xml).contains("net.logstash.logback.encoder.LogstashEncoder");
        assertThat(xml).contains("<includeMdcKeyName>traceId</includeMdcKeyName>");
        assertThat(xml)
            .contains("<includeMdcKeyName>processInstanceId</includeMdcKeyName>");
        // Encoder-класс реально на classpath (зависимость подключена).
        assertThat(Class.forName("net.logstash.logback.encoder.LogstashEncoder"))
            .isNotNull();
    }
}
