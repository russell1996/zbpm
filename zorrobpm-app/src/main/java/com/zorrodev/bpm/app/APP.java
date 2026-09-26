package com.zorrodev.bpm.app;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SpringBootApplication(scanBasePackages = {
    "com.zorrodev.bpm.app",
    "com.zorrodev.bpm.engine",
    "com.zorrodev.bpm.rest",
    "com.zorrodev.bpm.rabbitmq",
})
public class APP implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(APP.class);

    public static void main(String[] args) {
        // WO-QW-1 A-C-5d: no TimeZone.setDefault here — the JVM already honours TZ
        // (docker-compose + Dockerfile both set TZ=Asia/Almaty), and precise zone
        // handling lives in businessZone (@Value, ElementSupport etc). A global
        // static would silently move every zone-naive call site at once.
        SpringApplication.run(APP.class, args);
    }

    @Override
    public void run(String... args) {
        // WO-QW-1 Q-6: non-empty startup hook so the runner is intentional, not vestigial.
        log.info("ZorroBPM started");
    }

}
