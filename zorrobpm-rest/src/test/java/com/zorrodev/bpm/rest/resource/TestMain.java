package com.zorrodev.bpm.rest.resource;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Scans the whole rest module - not just this package - so that the test context wires it the
 * same way an application embedding the module does.
 */
@SpringBootApplication(scanBasePackages = "com.zorrodev.bpm.rest")
public class TestMain {

    public static void main(String[] args) {
        SpringApplication.run(TestMain.class, args);
    }
}
