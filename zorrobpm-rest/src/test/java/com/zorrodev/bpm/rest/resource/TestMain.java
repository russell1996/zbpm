package com.zorrodev.bpm.rest.resource;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
    "com.zorrodev.bpm.rest.resource",
    "com.zorrodev.bpm.rest.security",
})
public class TestMain {

    public static void main(String[] args) {
        SpringApplication.run(TestMain.class, args);
    }
}
