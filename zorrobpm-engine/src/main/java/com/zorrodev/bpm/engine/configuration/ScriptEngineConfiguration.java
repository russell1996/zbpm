package com.zorrodev.bpm.engine.configuration;

import org.camunda.feel.impl.script.FeelScriptEngineFactory;
import org.camunda.feel.impl.script.FeelUnaryTestsScriptEngineFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.script.ScriptEngine;

@Configuration
public class ScriptEngineConfiguration {

    /** Unary-tests engine: evaluates sequence-flow conditions (returns a Boolean). */
    @Bean
    @Primary
    public ScriptEngine feelScriptEngine() {
        return new FeelUnaryTestsScriptEngineFactory().getScriptEngine();
    }

    /** Expression engine: evaluates full FEEL expressions returning any value (used by script tasks). */
    @Bean
    public ScriptEngine feelExpressionScriptEngine() {
        return new FeelScriptEngineFactory().getScriptEngine();
    }
}
