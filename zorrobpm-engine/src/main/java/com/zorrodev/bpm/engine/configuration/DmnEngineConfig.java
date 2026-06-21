package com.zorrodev.bpm.engine.configuration;

import org.camunda.feel.api.FeelEngineApi;
import org.camunda.feel.api.FeelEngineBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DmnEngineConfig {

    /**
     * A FEEL engine (the same feel-scala line Camunda 8 uses) used by the custom DMN decision-table
     * evaluator. Built directly on the project's feel-engine — no separate DMN engine dependency.
     */
    @Bean
    public FeelEngineApi feelEngineApi() {
        return FeelEngineBuilder.forJava().build();
    }
}
