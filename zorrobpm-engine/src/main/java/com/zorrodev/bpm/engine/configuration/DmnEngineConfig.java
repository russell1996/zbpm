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
     *
     * <p>WO-ENG-27 (NEW2-05): {@code forJava} по умолчанию распаковывает числа в
     * {@code Double} с тихой потерей точности — подключён
     * {@link FeelBigDecimalNumberMapper}, отдающий {@code java.math.BigDecimal}.
     */
    @Bean
    public FeelEngineApi feelEngineApi() {
        return FeelEngineBuilder.forJava().withCustomValueMapper(new FeelBigDecimalNumberMapper()).build();
    }
}
