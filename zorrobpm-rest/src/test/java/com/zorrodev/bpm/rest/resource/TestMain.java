package com.zorrodev.bpm.rest.resource;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
    "com.zorrodev.bpm.rest.resource",
    "com.zorrodev.bpm.rest.security",
    "com.zorrodev.bpm.rest.configuration",
    // WO-REL-12: RabbitOutboxConfirmIT runs the FULL production wiring — outbox → rabbitmq
    // listeners (DomainEventOutboxListener/ServiceTaskListener) → RabbitTemplate with
    // confirm/return callbacks (RabbitConfiguration) → broker → OutboxDeliveryResultListener.
    // Without this package the IT would silently use Boot's plain RabbitTemplate (no callbacks).
    "com.zorrodev.bpm.rabbitmq",
})
public class TestMain {

    public static void main(String[] args) {
        SpringApplication.run(TestMain.class, args);
    }
}
