package com.zorrodev.bpm.engine;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableJpaRepositories
@EntityScan(basePackages = {"com.zorrodev.bpm.engine.entity"})
@ComponentScan(basePackages = {"com.zorrodev.bpm.engine"})
public class BPMConfiguration {
}
