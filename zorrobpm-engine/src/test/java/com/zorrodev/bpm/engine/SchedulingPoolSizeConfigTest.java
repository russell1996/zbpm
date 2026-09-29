package com.zorrodev.bpm.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-SEC-59 #5: the app runtime sets spring.task.scheduling.pool.size = 4 so that multiple
 * @Scheduled tasks (timer batch, retention, mail retry, ...) are not serialized onto a single
 * scheduler thread (the Spring default is a 1-thread pool). This config test asserts the property
 * is present in the zorrobpm-app application.properties that actually ships.
 */
class SchedulingPoolSizeConfigTest {

    @Test
    void schedulingPoolSize_isSetToFour() throws Exception {
        Path p = Path.of("../zorrobpm-app/src/main/resources/application.properties");
        assertThat(Files.exists(p)).as("zorrobpm-app application.properties must exist").isTrue();
        List<String> lines = Files.readAllLines(p);
        assertThat(lines.stream().anyMatch(l -> l.trim().equals("spring.task.scheduling.pool.size=4")))
            .as("WO-SEC-59 #5: scheduler pool size must be 4 so parallel @Scheduled tasks are not serialized")
            .isTrue();
    }
}
