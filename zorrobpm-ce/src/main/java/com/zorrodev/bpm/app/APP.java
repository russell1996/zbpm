package com.zorrodev.bpm.app;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication(scanBasePackages = {
    "com.zorrodev.bpm.app",
    "com.zorrodev.bpm.engine",
    "com.zorrodev.bpm.rest",
    "com.zorrodev.bpm.rabbitmq",
})
public class APP implements CommandLineRunner {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Aqtau"));
        SpringApplication.run(APP.class, args);
    }

    @Override
    public void run(String... args) {

    }

}
