package com.zorrodev.bpm.rabbitmq.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-QW-1 S-7: the AMQP deserialization allowlist must be narrowed to our own
 * exchange types. Behavioral pin would need a live broker round-trip (covered
 * by RabbitOutboxConfirmIT in CI); here we pin the wiring the unit can see.
 */
class MessageConverterTrustTest {

    @Test
    void messageConverter_trustsOnlyExchangePackage() {
        RabbitConfiguration configuration = new RabbitConfiguration();
        JacksonJsonMessageConverter converter =
            (JacksonJsonMessageConverter) configuration.messageConverter();

        // No public getter for the allowlist — pin it reflectively off the
        // DefaultJacksonJavaTypeMapper (the only mapper with trustedPackages state).
        Object mapper = converter.getJavaTypeMapper();
        assertThat(mapper.getClass().getSimpleName())
            .as("default mapper carries the allowlist")
            .isEqualTo("DefaultJacksonJavaTypeMapper");
        String[] trusted = readTrustedPackages(mapper);
        assertThat(java.util.List.of(trusted))
            .as("allowlist must be exactly com.zorrodev.bpm.exchange")
            .containsExactly("com.zorrodev.bpm.exchange");
    }

    private static String[] readTrustedPackages(Object mapper) {
        // Set<String> on DefaultJacksonJavaTypeMapper (private, no getter).
        Class<?> c = mapper.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("trustedPackages");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.Set<String> trusted = (java.util.Set<String>) f.get(mapper);
                return trusted.toArray(new String[0]);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot read trustedPackages", e);
            }
        }
        throw new AssertionError("trustedPackages field not found on " + mapper.getClass());
    }
}
