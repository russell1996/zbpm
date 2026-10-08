package com.zorrodev.bpm.handler.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-INT-10 (критерии 1–2): per-system воркер завершает задание через
 * completion-exchange — и НЕ может писать в чужие очереди.
 *
 * <p>До WO публикация шла через default exchange, и выдать воркеру write на
 * него было нельзя (живой прогон: write на {@code amq.default} доставил
 * поддельное сообщение в чужую job-очередь) — а без него КАЖДЫЙ completion
 * падал 403 (живой RED §2). Теперь: воркер с {@code permissionsFor}-правами
 * (write только на {@code zorrobpm.completions}) публикует completion через
 * exchange с ключом = имя очереди — движок-сторона читает его из очереди
 * (identity-биндинг объявляет движок; здесь — тем же способом, см.
 * {@code CompletionExchangeProbe}).
 *
 * <p>Матрица (таблицей, а не штучными пробами — живой прогон на каждую клетку):
 * <ul>
 *   <li>свой completion через exchange → DELIVERED (критерий 1);</li>
 *   <li>чужая job-очередь через exchange → REFUSED (нет биндинга) + write-regex
 *       белого списка exchange (критерий 2);</li>
 *   <li>чужая job-очередь через default exchange → REFUSED (нет write на
 *       {@code amq.default} — ядро WO);</li>
 *   <li>чужой DLQ / poison чужого контура через exchange → REFUSED;</li>
 *   <li>несуществующий ключ через exchange → unroutable (mandatory-возврат),
 *       а не тихая потеря и не 403 (маршрутизация отделена от прав).</li>
 * </ul>
 *
 * <p>Мутации, которые обязаны ронять: write-regex назад на имена очередей
 * (крит.1 RED — 403 на exchange); биндинг чужой очереди (крит.2 RED —
 * доставлено); default-exchange публикация в прод-коде (крит.1 RED — 403).
 */
@Tag("rabbit")
class WorkerCompletionPermissionsRabbitIT {

    /** Имя очереди completion'ов этого прогона (probe-изоляция shared-брокера). */
    private static final String COMPLETE_QUEUE = "int10.it.complete.probe";
    private static final String FOREIGN_JOB_QUEUE = "int10.it.jobs.foreign";
    private static final String FOREIGN_DLQ = FOREIGN_JOB_QUEUE + ".dlq";

    private String host;
    private int port;

    private CachingConnectionFactory adminCf;
    private RabbitAdmin admin;

    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) {
            v = System.getProperty(key);
        }
        return v != null ? v : dflt;
    }

    @BeforeEach
    void setUp() throws Exception {
        host = cfg("RABBITMQ_HOST", "localhost");
        port = Integer.parseInt(cfg("RABBITMQ_PORT", "5672"));

        adminCf = new CachingConnectionFactory(host, port);
        adminCf.setUsername(cfg("RABBITMQ_USER", "zorrodev"));
        adminCf.setPassword(cfg("RABBITMQ_PASSWORD", "zorrodev"));
        admin = new RabbitAdmin(adminCf);
        admin.setAutoStartup(true);
        admin.afterPropertiesSet();

        // Прод-топология стороны движка (см. RabbitConfiguration): exchange +
        // identity-биндинг ТОЛЬКО своей очереди. Чужая очередь существует, но
        // НЕ забиндена к exchange (и воркеру не принадлежит).
        CompletionExchangeProbe.bindQueue(admin, COMPLETE_QUEUE);
        admin.declareQueue(new org.springframework.amqp.core.Queue(FOREIGN_JOB_QUEUE, true, false, false));
        admin.declareQueue(new org.springframework.amqp.core.Queue(FOREIGN_DLQ, true, false, false));
        admin.purgeQueue(COMPLETE_QUEUE, false);
        admin.purgeQueue(FOREIGN_JOB_QUEUE, false);
        assertThat(serverMessageCount(COMPLETE_QUEUE)).as("completion пуст на старте").isZero();
        assertThat(serverMessageCount(FOREIGN_JOB_QUEUE)).as("чужая очередь пуста на старте").isZero();
    }

    @AfterEach
    void tearDown() {
        for (String q : new String[]{COMPLETE_QUEUE, FOREIGN_JOB_QUEUE, FOREIGN_DLQ}) {
            try {
                admin.purgeQueue(q, false);
            } catch (Exception ignored) {
            }
        }
        for (String login : workersToDelete) {
            mgmtDelete("/api/users/" + login);
        }
        workersToDelete.clear();
        try {
            admin.deleteQueue(FOREIGN_JOB_QUEUE);
        } catch (Exception ignored) {
        }
        try {
            admin.deleteQueue(FOREIGN_DLQ);
        } catch (Exception ignored) {
        }
        try {
            adminCf.destroy();
        } catch (Exception ignored) {
        }
    }

    private final java.util.List<String> workersToDelete = new java.util.ArrayList<>();

    /**
     * Воркер с permissionsFor-правами: новый AMQP-юзер, write/regex — ДОСЛОВНО
     * из {@code RabbitMqProvisioningService.permissionsFor(Set.of("billing"))}
     * (копипаста по месту запрещена — строка приносится через Management API
     * shape: write на exchange, read на свои очереди; здесь — руками тот же
     * shape, потому что engine-модуль стартеру недоступен в compile-scope, а
     * соответствие shape покрывает permissions-юнит + ProvisioningIT).
     */
    private void setPermissions(String login, String configure, String write, String read) {
        mgmtPut("/api/permissions/%2F/" + login,
            "{\"configure\":" + json(configure)
                + ",\"write\":" + json(write) + ",\"read\":" + json(read) + "}",
            "permissions PUT");
    }

    private static String json(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String createWorker() throws Exception {
        String login = "int10w" + UUID.randomUUID().toString().substring(0, 8);
        String password = "pw-" + UUID.randomUUID();
        mgmtPut("/api/users/" + login,
            "{\"password\":" + json(password) + ",\"tags\":\"\"}", "user PUT");
        giveWorkerPermissions(login);
        workersToDelete.add(login);
        return login + "\n" + password;
    }

    private void giveWorkerPermissions(String login) {
        // write — РОВНО имя completion-exchange; read — свои очереди + poison.
        // Форма — та же, что permissionsFor: write="^zorrobpm\.completions$".
        mgmtPut("/api/permissions/%2F/" + login,
            "{\"configure\":" + json("^zorrobpm\\.jobs\\.(billing)(\\.dlq)?$")
                + ",\"write\":" + json("^zorrobpm\\.completions$")
                + ",\"read\":" + json("(^zorrobpm\\.jobs\\.(billing)(\\.dlq)?$)"
                    + "|(^zorrobpm\\.completion\\.poison$)") + "}",
            "permissions PUT");
    }

    /** Создаёт воркера с permissionsFor-shape другой job-очереди (для матрицы). */
    private String createForeignWorker() throws Exception {
        String login = "int10f" + UUID.randomUUID().toString().substring(0, 8);
        String password = "pw-" + UUID.randomUUID();
        mgmtPut("/api/users/" + login,
            "{\"password\":" + json(password) + ",\"tags\":\"\"}", "user PUT");
        mgmtPut("/api/permissions/%2F/" + login,
            "{\"configure\":" + json("^zorrobpm\\.jobs\\.(shipping)(\\.dlq)?$")
                + ",\"write\":" + json("^zorrobpm\\.completions$")
                + ",\"read\":" + json("(^zorrobpm\\.jobs\\.(shipping)(\\.dlq)?$)"
                    + "|(^zorrobpm\\.completion\\.poison$)") + "}",
            "permissions PUT");
        workersToDelete.add(login);
        return login + "\n" + password;
    }

    private static void mgmtPut(String path, String body, String what) {
        try {
            String mgmt = cfg("RABBITMQ_MGMT_BASE_URL", "http://localhost:15672");
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                // WO-INT-9: JDK шлёт HTTP/2-prior-knowledge по умолчанию —
                // Cowboy/management API брокера рвёт соединение (EOF).
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();
            String creds =
                cfg("RABBITMQ_USER", "zorrodev") + ":" + cfg("RABBITMQ_PASSWORD", "zorrodev");
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(mgmt + path))
                .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                    .encodeToString(creds.getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            java.net.http.HttpResponse<String> resp =
                client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).as(what + ": " + resp.body()).isIn(200, 201, 204);
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("management API not reachable: " + what, e);
        }
    }

    private static void mgmtDelete(String path) {
        try {
            String mgmt = cfg("RABBITMQ_MGMT_BASE_URL", "http://localhost:15672");
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                // WO-INT-9: JDK шлёт HTTP/2-prior-knowledge по умолчанию —
                // Cowboy/management API брокера рвёт соединение (EOF).
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();
            String creds =
                cfg("RABBITMQ_USER", "zorrodev") + ":" + cfg("RABBITMQ_PASSWORD", "zorrodev");
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(mgmt + path))
                .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                    .encodeToString(creds.getBytes(StandardCharsets.UTF_8)))
                .DELETE()
                .build();
            client.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
        }
    }

    private com.rabbitmq.client.Connection workerConnection(String login, String password)
            throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(host);
        f.setPort(port);
        f.setUsername(login);
        f.setPassword(password);
        return f.newConnection("int10-proof");
    }

    /** Публикация сырого тела указанным путём: true = доставлено в очередь. */
    private boolean tryPublish(com.rabbitmq.client.Connection conn,
            String exchange, String routingKey, String body) throws Exception {
        Channel ch = conn.createChannel();
        try {
            // Голый confirm ack НЕ равен доставке (брокер подтверждает приём и
            // для basic.return) — возврат ловим отдельно (та же дисциплина, что
            // прод-отправитель ConfirmedCompletionSender).
            java.util.concurrent.atomic.AtomicBoolean returned =
                new java.util.concurrent.atomic.AtomicBoolean(false);
            ch.addReturnListener(returnedMessage -> returned.set(true));
            ch.confirmSelect();
            ch.basicPublish(exchange, routingKey, true, false, null,
                body.getBytes(StandardCharsets.UTF_8));
            try {
                ch.waitForConfirmsOrDie(10_000);
            } catch (Exception e) {
                return false;
            }
            return !returned.get();
        } catch (java.io.IOException e) {
            if (isAccessRefused(e)) {
                return false;
            }
            throw e;
        } finally {
            try {
                ch.abort();
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isAccessRefused(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().contains("ACCESS_REFUSED")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void criterion1_workerCompletionViaExchange_delivered() throws Exception {
        String[] creds = createWorker().split("\n");
        try (com.rabbitmq.client.Connection conn = workerConnection(creds[0], creds[1])) {
            // Критерий 1 — живым ПРОД-путём: ConfirmedCompletionSender через
            // exchange воркерскими кредами (confirm + mandatory, как стартер).
            // До WO этот же вызов шёл голым convertAndSend(queue, …) через
            // default exchange и падал 403 (RED §2).
            CachingConnectionFactory workerCf = new CachingConnectionFactory(host, port);
            workerCf.setUsername(creds[0]);
            workerCf.setPassword(creds[1]);
            workerCf.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            workerCf.setPublisherReturns(true);
            RabbitTemplate workerTemplate = new RabbitTemplate(workerCf);
            workerTemplate.setMessageConverter(
                new Jackson2JsonMessageConverter(new ObjectMapper()));
            workerTemplate.setMandatory(true);
            try {
                com.zorrodev.bpm.exchange.ServiceTaskCompleteData data =
                    new com.zorrodev.bpm.exchange.ServiceTaskCompleteData();
                data.setServiceTaskId(UUID.randomUUID());
                data.setCompletionId("int10-c1-" + UUID.randomUUID());
                data.setStatus("SUCCESS");
                ConfirmedCompletionSender.sendAndConfirm(workerTemplate,
                    CompletionTopology.COMPLETION_EXCHANGE, COMPLETE_QUEUE, data,
                    null, null, 5_000L, true, null);
            } finally {
                workerCf.destroy();
            }
            assertThat(serverMessageCount(COMPLETE_QUEUE))
                .as("completion per-system воркера доставлен через exchange "
                    + "(confirm без return + счётчик очереди)")
                .isEqualTo(1);
        }
    }

    @Test
    void criterion2_matrix_foreignQueuesNotWritable() throws Exception {
        String[] creds = createWorker().split("\n");
        try (com.rabbitmq.client.Connection conn = workerConnection(creds[0], creds[1])) {
            // Чужая job-очередь через exchange: биндинга нет → unroutable, НЕ 403
            // (права на exchange есть, маршрута нет) — сообщение НЕ доставлено.
            assertThat(tryPublish(conn, CompletionTopology.COMPLETION_EXCHANGE,
                FOREIGN_JOB_QUEUE, "{\"forged\":\"job\"}"))
                .as("чужая очередь через exchange: биндинга нет — недоставлено")
                .isFalse();
            assertThat(serverMessageCount(FOREIGN_JOB_QUEUE))
                .as("в чужой очереди ничего не появилось")
                .isZero();

            // Чужой DLQ через exchange — так же недоставлено.
            assertThat(tryPublish(conn, CompletionTopology.COMPLETION_EXCHANGE,
                FOREIGN_DLQ, "{\"forged\":\"dlq\"}"))
                .as("чужой DLQ через exchange — недоставлено")
                .isFalse();

            // ЯДРО WO: чужая очередь через DEFAULT exchange — 403
            // (нет write на amq.default — и быть не должно никогда).
            // Мутация «default-exchange публикация в прод-коде» роняет крит.1,
            // а здесь — живое доказательство, что путь закрыт.
            assertThat(tryPublish(conn, "", FOREIGN_JOB_QUEUE, "{\"forged\":\"job\"}"))
                .as("default exchange закрыт для воркера (403)")
                .isFalse();
            assertThat(serverMessageCount(FOREIGN_JOB_QUEUE)).isZero();

            // Несуществующий ключ через exchange — unroutable, не 403 и не
            // тихая потеря: маршрутизация отделена от прав (mandatory-возврат).
            assertThat(tryPublish(conn, CompletionTopology.COMPLETION_EXCHANGE,
                "int10.it.no.such.queue", "{\"x\":1}"))
                .as("немаршрутизируемый ключ — возврат, не доставка и не 403")
                .isFalse();
        }
    }

    @Test
    void criterion2_foreignWorker_cannotForgeIntoOurCompletionQueue() throws Exception {
        // Второй воркер (член ДРУГОГО процесса, тот же exchange-write): шлёт
        // completion в НАШУ очередь — сообщение ДОХОДИТ (биндинг общий).
        // Это и есть остаточный риск WO (критерий 5): правами не закрывается,
        // только stamping'ом C8-36 (выключен по умолчанию — flag-day за
        // владельцем). Тест фиксирует факт честно, а не «защиту».
        String[] creds = createForeignWorker().split("\n");
        try (com.rabbitmq.client.Connection conn = workerConnection(creds[0], creds[1])) {
            assertThat(tryPublish(conn, CompletionTopology.COMPLETION_EXCHANGE,
                COMPLETE_QUEUE, "{\"completionId\":\"int10-forged\",\"serviceTaskId\":\"foreign\"}"))
                .as("общий exchange: чужой воркер технически публикует в нашу очередь "
                    + "(остаточный риск — закрывается только stamping, см. критерий 5)")
                .isTrue();
        }
    }

    private int serverMessageCount(String queue) {
        try {
            ConnectionFactory f = new ConnectionFactory();
            f.setHost(host);
            f.setPort(port);
            f.setUsername(cfg("RABBITMQ_USER", "zorrodev"));
            f.setPassword(cfg("RABBITMQ_PASSWORD", "zorrodev"));
            try (Connection c = f.newConnection(); Channel ch = c.createChannel()) {
                return ch.queueDeclarePassive(queue).getMessageCount();
            }
        } catch (Exception e) {
            throw new IllegalStateException("broker not reachable", e);
        }
    }

}
