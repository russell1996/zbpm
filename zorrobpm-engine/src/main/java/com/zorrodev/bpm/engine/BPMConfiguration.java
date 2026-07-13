package com.zorrodev.bpm.engine;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableJpaRepositories
@EntityScan(basePackages = {"com.zorrodev.bpm.engine.entity"})
@ComponentScan(basePackages = {"com.zorrodev.bpm.engine"},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.zorrodev\\.bpm\\.engine\\.test\\..*"))
public class BPMConfiguration {
}
