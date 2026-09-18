package com.zorrodev.bpm.rest.resource;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-OBS-9: broker version floor — the whole tree assumes RabbitMQ 4.x
 * (Khepri metadata, CQv2 classic queues, removed classic-mirroring code).
 * Pure AMQP handshake read ({@code connection.getServerProperties()}, no
 * management port needed — the CI rabbit job publishes only the AMQP port),
 * env-plumbed exactly like {@code RabbitOutboxConfirmIT} (P-23).
 *
 * <p>POF is version-shaped (precedent WO-OBS-7): RED on the 3.13 broker
 * ({@code 3.13.7}, major 3 &lt; 4), GREEN on the migrated 4.1 broker
 * ({@code 4.1.8}). After the CI/prod images sync to 4.1 this stays green as
 * a floor guard — a regressed 3.x broker fails the suite instead of booting
 * against v2-format queues it cannot read.
 */
@Tag("rabbit")
class BrokerVersionFloorIT {

    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) {
            v = System.getProperty(key);
        }
        return v != null ? v : dflt;
    }

    @Test
    void brokerMajorVersion_isAtLeast4() throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(cfg("RABBITMQ_HOST", "localhost"));
        f.setPort(Integer.parseInt(cfg("RABBITMQ_PORT", "5672")));
        f.setUsername(cfg("RABBITMQ_USER", "zorrodev"));
        f.setPassword(cfg("RABBITMQ_PASSWORD", "zorrodev"));
        f.setConnectionTimeout(10_000);
        try (Connection c = f.newConnection("obs9-version-floor")) {
            Map<String, Object> props = c.getServerProperties();
            assertThat(props).containsKey("version");
            assertThat(String.valueOf(props.get("product"))).isEqualTo("RabbitMQ");
            String version = String.valueOf(props.get("version"));
            int major;
            try {
                major = Integer.parseInt(version.split("\\.")[0]);
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                throw new AssertionError("unparseable broker version: " + version, e);
            }
            assertThat(major)
                .as("broker must be RabbitMQ 4.x+ (WO-OBS-9 floor), got %s", version)
                .isGreaterThanOrEqualTo(4);
        }
    }
}
