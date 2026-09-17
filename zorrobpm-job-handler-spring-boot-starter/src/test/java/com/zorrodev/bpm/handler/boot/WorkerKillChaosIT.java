package com.zorrodev.bpm.handler.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.handler.JobHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-TEST-10 scenario 1 (WB-002): kill -9 воркера посреди job — реальный инжект.
 *
 * <p>Топология: один и тот же job-консьюмер ({@code JobCompletionListener} на прод-пути
 * {@code HandlerAutoConfiguration}, prefetch=1) в ДВУХ процессах — victim-контейнер
 * (получает сообщение, входит в handler, висит) и survivor в этом процессе. Инжект:
 * SIGKILL victim-контейнера через Docker Engine API (эквивалент хостового
 * {@code docker kill -s 9}, exit 137 — проверяем). Брокер видит смерть TCP-соединения →
 * unacked сообщение уходит в requeue → survivor подбирает и доводит до completion.
 * Конечное состояние: completion ровно один (content-check по taskId), бизнес-эффект
 * ровно один (счётчик survivor), victim умер SIGKILL'ом.
 *
 * <p>Отличие от {@code CompletionTransportRabbitIT} (WO-REL-36): там рвётся транспорт
 * фабрики (`destroy()` = обрыв соединения), а процесс жив; здесь умирает САМ ПРОЦЕСС
 * посреди handler'а — проверяем, что незавершённая работа не теряется вместе с ним.
 *
 * <p>POF — парой (V3/G-N): GREEN на прод-пути ниже + RED {@code kill9WithoutSurvivor_red}
 * (тот же инжект, но survivor'а нет — completion не приходит, тест RED таймаутом).
 * Доказывает, что позитивный тест проверяет именно requeue+recovery, а не «сообщение
 * всегда доходит».
 *
 * <p>Запуск: {@code ci/run-chaos-tests.sh} (топология + toxiproxy; victim-контейнер на
 * той же сети, classpath из bind-mount /build — нового артефакта нет). Docker Engine
 * доступен тесту через проброшенный сокет (см. {@code ChaosDocker} — docker CLI в
 * mvn-контейнере отсутствует, созданный victim управляется по Engine API).
 */
@Tag("chaos")
class WorkerKillChaosIT {

    private static final String JOB_QUEUE = "chaos.it.jobs.killprobe";
    private static final String COMPLETE_QUEUE = "chaos.it.complete.killprobe";

    private String host;
    private int port;
    private String user;
    private String password;
    private String net;
    private String uidGid;
    private String hostBuildDir;
    private String workerPrefix;

    private CachingConnectionFactory cf;
    private RabbitAdmin admin;
    private SimpleRabbitListenerContainerFactory containerFactory;
    private RabbitTemplate senderTemplate;
    private ObjectMapper objectMapper;
    private ChaosDocker docker;

    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) {
            v = System.getProperty(key);
        }
        return v != null ? v : dflt;
    }

    @BeforeEach
    void setUp() throws Exception {
        host = cfg("CHAOS_RABBIT_HOST", cfg("RABBITMQ_HOST", "localhost"));
        port = Integer.parseInt(cfg("CHAOS_RABBIT_PORT", cfg("RABBITMQ_PORT", "5672")));
        user = cfg("RABBITMQ_USER", "zorrodev");
        password = cfg("RABBITMQ_PASSWORD", "zorrodev");
        net = cfg("CHAOS_NET", "zbpm-chaosci");
        uidGid = cfg("CHAOS_UID", "1000") + ":" + cfg("CHAOS_GID", "1000");
        hostBuildDir = cfg("CHAOS_HOST_BUILD_DIR", System.getProperty("user.dir"));
        workerPrefix = cfg("CHAOS_WORKER_PREFIX", "chaosci");

        cf = new CachingConnectionFactory(host, port);
        cf.setUsername(user);
        cf.setPassword(password);
        admin = new RabbitAdmin(cf);
        admin.setAutoStartup(true);
        admin.afterPropertiesSet();
        admin.declareQueue(new org.springframework.amqp.core.Queue(JOB_QUEUE, true, false, false));
        admin.declareQueue(new org.springframework.amqp.core.Queue(COMPLETE_QUEUE, true, false, false));
        admin.purgeQueue(JOB_QUEUE, false);
        admin.purgeQueue(COMPLETE_QUEUE, false);
        assertThat(serverMessageCount(JOB_QUEUE)).as("вход пуст на старте").isZero();

        containerFactory = new SimpleRabbitListenerContainerFactory();
        containerFactory.setConnectionFactory(cf);
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(new ObjectMapper());
        containerFactory.setMessageConverter(converter);

        senderTemplate = new RabbitTemplate(cf);
        senderTemplate.setMessageConverter(converter);
        objectMapper = new ObjectMapper();
        docker = new ChaosDocker();
    }

    @AfterEach
    void tearDown() {
        try {
            admin.purgeQueue(JOB_QUEUE, false);
            admin.purgeQueue(COMPLETE_QUEUE, false);
        } catch (Exception ignored) {
        }
        try {
            cf.destroy();
        } catch (Exception ignored) {
        }
    }

    private int serverMessageCount(String queue) throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setConnectionTimeout(5000);
        f.setHandshakeTimeout(5000);
        f.setShutdownTimeout(5000);
        f.setHost(host);
        f.setPort(port);
        f.setUsername(user);
        f.setPassword(password);
        try (Connection c = f.newConnection(); Channel ch = c.createChannel()) {
            return ch.queueDeclarePassive(queue).getMessageCount();
        }
    }

    private static void await(String what, Duration timeout, java.util.function.BooleanSupplier cond)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("timed out waiting for: " + what);
    }

    /**
     * Victim-контейнер: тот же тестовый classpath через bind-mount /build (новый артефакт
     * не собирается, в прод-jars не попадает — ChaosWorkerMain живёт в test-sourceset).
     * Latch-каталог — под target модуля (общий mount, переживает victim; чистится в finally).
     * Путь передаётся КАК ЕСТЬ (latchDir.toString()): mvn-контейнер и victim монтируют
     * один и тот же host-каталог в /build, раскладка путей идентична с обеих сторон.
     * Хардкод "/build/target/..." здесь был багом (урок run8): user.dir форка failsafe —
     * это builddir МОДУЛЯ (/build/&lt;module&gt;), а не корень /build, victim писал latch
     * в несуществующий каталог, handleJob падал, сообщение гонялось redelivery-циклом,
     * а тест 60с ждал файл, который никто не создаст.
     */
    private String startVictim(String victimName, Path latchDir) throws Exception {
        String classpath = System.getProperty("java.class.path");
        // Fail-fast: failsafe обязан форкать с полным classpath (useManifestOnlyJar=false
        // в pom). Manifest-only jar (один surefirebooter*.jar) даёт victim'у обрезанный
        // classpath → NoClassDefFoundError и 120с висячего ожидания latch-файла. Громкая
        // ошибка сразу вместо тихого таймаута.
        if (classpath == null || classpath.contains("surefirebooter")
            || classpath.contains("failsafebooter")) {
            throw new IllegalStateException(
                "manifest-only classpath in forked JVM (useManifestOnlyJar must be false): "
                    + classpath);
        }
        Map<String, String> env = new TreeMap<>();
        env.put("CHAOS_LATCH_DIR", latchDir.toString());
        env.put("RABBITMQ_HOST", host);
        env.put("RABBITMQ_PORT", String.valueOf(port));
        env.put("RABBITMQ_USER", user);
        env.put("RABBITMQ_PASSWORD", password);
        String id = docker.createContainer(victimName, "maven:3.9.9-eclipse-temurin-21",
            new String[]{"java", "-cp", classpath,
                "com.zorrodev.bpm.handler.boot.ChaosWorkerMain"},
            env,
            new String[]{hostBuildDir + ":/build", "zbpm_m2:/tmp/.m2:ro"},
            net, uidGid);
        docker.startContainer(id);
        return id;
    }

    private static JobDetailModel jobDetail(UUID serviceTaskId) {
        JobDetailModel detail = new JobDetailModel();
        detail.setServiceTaskId(serviceTaskId);
        detail.setProcessInstanceId(UUID.randomUUID());
        detail.setProcessDefinitionId(UUID.randomUUID());
        detail.setServiceTaskKey("k");
        detail.setJob("killprobe");
        detail.setVariables(Map.of());
        return detail;
    }

    @Test
    void kill9WorkerMidJob_jobRedelivered_effectOnce() throws Exception {
        UUID taskId = UUID.randomUUID();
        String correlationId = "chaos-kill-" + UUID.randomUUID();

        // 1. Кладём 1 job во входную очередь.
        senderTemplate.convertAndSend(JOB_QUEUE, jobDetail(taskId), m -> {
            m.getMessageProperties().setCorrelationId(correlationId);
            return m;
        });

        // 2. Victim-контейнер: отдельный процесс, входит в handler и ВИСИТ (latch-файл
        // под общим /build-mount — виден и тесту, и victim'у).
        String victimName = workerPrefix + "-victim-" + UUID.randomUUID().toString().substring(0, 8);
        Path latchDir = Path.of(System.getProperty("user.dir"), "target",
            "chaos-" + UUID.randomUUID());
        Files.createDirectories(latchDir);
        String victimId = null;
        try {
            victimId = startVictim(victimName, latchDir);

            // 3. Ждём: victim ЗАБРАЛ сообщение (unacked — ready=0) и вошёл в handler.
            await("victim took the message", Duration.ofSeconds(60), () -> {
                try {
                    return serverMessageCount(JOB_QUEUE) == 0
                        && Files.exists(latchDir.resolve("entered"));
                } catch (Exception e) {
                    return false;
                }
            });

            // 4. ИНЖЕКТ: kill -9 живого процесса посреди handler'а (не graceful).
            docker.kill9(victimName);
            await("victim dead by SIGKILL", Duration.ofSeconds(30), () -> {
                try {
                    String inspect = docker.inspect(victimName);
                    Integer code = ChaosDocker.exitCodeOf(inspect);
                    String status = ChaosDocker.statusOf(inspect);
                    return code != null && code == 137 && !"running".equals(status);
                } catch (Exception e) {
                    return false;
                }
            });

            // 5. Survivor в ЭТОМ процессе подбирает requeue и доводит до completion.
            AtomicInteger survivorCalls = new AtomicInteger(0);
            JobHandler survivorHandler = new JobHandler() {
                @Override
                public String getJob() {
                    return "killprobe";
                }

                @Override
                public List<com.zorrodev.bpm.exchange.ProcessVariable> handleJob(JobDetailModel model) {
                    survivorCalls.incrementAndGet();
                    com.zorrodev.bpm.exchange.ProcessVariable v =
                        new com.zorrodev.bpm.exchange.ProcessVariable();
                    v.setName("x");
                    v.setValue("1");
                    v.setType("STRING");
                    return List.of(v);
                }
            };
            RabbitTemplate survivorTemplate = new RabbitTemplate(cf);
            survivorTemplate.setMessageConverter(new Jackson2JsonMessageConverter(new ObjectMapper()));
            JobCompletionListener survivorListener = new JobCompletionListener(
                survivorHandler, survivorTemplate, objectMapper, JOB_QUEUE, COMPLETE_QUEUE);
            SimpleMessageListenerContainer survivor =
                containerFactory.createListenerContainer();
            survivor.setQueueNames(JOB_QUEUE);
            survivor.setMessageListener(survivorListener);
            survivor.setConcurrentConsumers(1);
            survivor.start();
            try {
                // 6. Конечное состояние: completion именно НАШЕГО taskId (content-check),
                // бизнес-эффект survivor ровно один (victim умер до commit'а эффекта —
                // его инкремент жил только в убитом процессе). Наблюдатель потребляет
                // СРАЗУ — drain-await вместо sleep+count (гонка «недочитал»).
                awaitCompletionForTask(taskId, Duration.ofSeconds(45));
                await("complete queue drained by observer", Duration.ofSeconds(15), () -> {
                    try {
                        return serverMessageCount(COMPLETE_QUEUE) == 0;
                    } catch (Exception e) {
                        return false;
                    }
                });
                assertThat(survivorCalls.get())
                    .as("survivor отработал ровно один раз (requeue, не дубль)")
                    .isEqualTo(1);
                assertThat(serverMessageCount(COMPLETE_QUEUE))
                    .as("completion потреблён наблюдателем")
                    .isZero();
            } finally {
                survivor.stop();
            }
        } finally {
            if (victimId != null) {
                try {
                    docker.removeContainer(victimName);
                } catch (Exception ignored) {
                }
            }
            deleteRecursively(latchDir);
        }
    }

    @Test
    void kill9WithoutSurvivor_red() throws Exception {
        // POF-инверсия критерия: тот же инжект (kill -9 посреди handler'а), но survivor'а
        // НЕТ — completion прийти НЕ может, await обязан RED (таймаут). Доказывает, что
        // позитивный тест проверяет именно requeue+recovery survivor'ом, а не «сообщение
        // всегда доходит». Сообщение при этом НЕ потеряно брокером — оно в requeue
        // (ready=1 после смерти victim'а), просто некому его довести.
        UUID taskId = UUID.randomUUID();
        String correlationId = "chaos-kill-nobody-" + UUID.randomUUID();

        senderTemplate.convertAndSend(JOB_QUEUE, jobDetail(taskId), m -> {
            m.getMessageProperties().setCorrelationId(correlationId);
            return m;
        });

        String victimName = workerPrefix + "-victim-" + UUID.randomUUID().toString().substring(0, 8);
        Path latchDir = Path.of(System.getProperty("user.dir"), "target",
            "chaos-" + UUID.randomUUID());
        Files.createDirectories(latchDir);
        String victimId = null;
        try {
            victimId = startVictim(victimName, latchDir);
            await("victim took the message", Duration.ofSeconds(60), () -> {
                try {
                    return serverMessageCount(JOB_QUEUE) == 0
                        && Files.exists(latchDir.resolve("entered"));
                } catch (Exception e) {
                    return false;
                }
            });

            docker.kill9(victimName);
            await("victim dead by SIGKILL", Duration.ofSeconds(30), () -> {
                try {
                    Integer code = ChaosDocker.exitCodeOf(docker.inspect(victimName));
                    return code != null && code == 137;
                } catch (Exception e) {
                    return false;
                }
            });

            // Survivor'а нет: completion ждать не от кого — короткий await обязан RED.
            boolean arrived = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (System.nanoTime() < deadline && !arrived) {
                try {
                    awaitCompletionForTask(taskId, Duration.ofSeconds(2));
                    arrived = true;
                } catch (IllegalStateException e) {
                    // ожидаемо: доводить некому
                }
            }
            assertThat(arrived).as("без survivor'а completion невозможен — обязано RED").isFalse();

            // …но сообщение НЕ потеряно: брокер requeue'ил его после смерти victim'а.
            await("requeued message visible", Duration.ofSeconds(15), () -> {
                try {
                    return serverMessageCount(JOB_QUEUE) == 1;
                } catch (Exception e) {
                    return false;
                }
            });
        } finally {
            if (victimId != null) {
                try {
                    docker.removeContainer(victimName);
                } catch (Exception ignored) {
                }
            }
            try {
                admin.purgeQueue(JOB_QUEUE, false);
            } catch (Exception ignored) {
            }
            deleteRecursively(latchDir);
        }
    }

    private void awaitCompletionForTask(UUID taskId, Duration timeout) throws Exception {
        // Наблюдатель идёт НАПРЯМУЮ в брокер (raw ConnectionFactory): соединения пула
        // приложения могут висеть в partition — наблюдатель обязан оставаться независимым.
        long deadline = System.nanoTime() + timeout.toNanos();
        ConnectionFactory f = new ConnectionFactory();
        f.setConnectionTimeout(5000);
        f.setHandshakeTimeout(5000);
        f.setShutdownTimeout(5000);
        f.setHost(host);
        f.setPort(port);
        f.setUsername(user);
        f.setPassword(password);
        while (System.nanoTime() < deadline) {
            try (Connection c = f.newConnection(); Channel ch = c.createChannel()) {
                ch.basicQos(1);
                com.rabbitmq.client.GetResponse resp = ch.basicGet(COMPLETE_QUEUE, true);
                if (resp != null) {
                    String body = new String(resp.getBody(), StandardCharsets.UTF_8);
                    try {
                        com.zorrodev.bpm.exchange.ServiceTaskCompleteData data =
                            objectMapper.readValue(body,
                                com.zorrodev.bpm.exchange.ServiceTaskCompleteData.class);
                        if (taskId.equals(data.getServiceTaskId())
                            && "SUCCESS".equals(data.getStatus())) {
                            return;
                        }
                    } catch (Exception ignored) {
                    }
                } else {
                    Thread.sleep(200);
                }
            } catch (Exception e) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("timed out waiting for completion of task " + taskId);
    }

    private static void deleteRecursively(Path dir) {
        try {
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    for (Path kid : stream.toList()) {
                        deleteRecursively(kid);
                    }
                }
            }
            Files.deleteIfExists(dir);
        } catch (Exception ignored) {
        }
    }
}
