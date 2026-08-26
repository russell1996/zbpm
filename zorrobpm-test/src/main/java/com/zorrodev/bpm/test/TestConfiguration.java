package com.zorrodev.bpm.test;

import com.zorrodev.bpm.engine.service.ServiceTaskEnqueueService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TestConfiguration {

    @Bean
    public ServiceTaskEnqueueService serviceTaskEnqueueService() {
        return new TestServiceTaskEnqueueService();
    }

    // WO-INT-5: the mail stub is no longer registered here. StubMailSender is now a
    // @Component with @Profile("test") inside the engine itself, so every test-profile
    // context binds it automatically (this auto-configuration was never on any classpath).

}
