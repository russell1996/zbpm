package com.zorrodev.bpm.engine.mail;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * WO-INT-5: enables mail configuration properties binding.
 */
@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class MailAutoConfiguration {
}
