package com.zorrodev.bpm.handler.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.handler.JobHandler;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * WO-TEST-10 scenario 1: worker-процесс для kill -9.
 *
 * <p>Запускается тестом {@code WorkerKillChaosIT} в ОТДЕЛЬНОМ контейнере на том же
 * тестовом classpath (bind-mount /build — новый артефакт не собирается, в прод-jars
 * не попадает: test-sourceset). Стартует ОДИН консьюмер на {@code chaos.it.jobs.killprobe},
 * handler входит и ВИСИТ (создаёт latch-файл {@code entered}), пока процесс не умрёт.
 * Сообщение остаётся unacked → после SIGKILL брокер requeue'ит его survivor'у.
 *
 * <p>Env (прокидывает тест): {@code CHAOS_LATCH_DIR}, {@code CHAOS_TASK_ID},
 * {@code RABBITMQ_HOST/PORT/USER/PASSWORD}.
 */
public final class ChaosWorkerMain {

    private ChaosWorkerMain() {
    }

    private static void touchEntered(String latchDir) throws java.io.IOException {
        File marker = new File(latchDir + "/entered");
        if (!marker.createNewFile() && !marker.exists()) {
            throw new IllegalStateException("cannot create latch file in " + latchDir);
        }
    }

    public static void main(String[] args) throws Exception {
        String latchDir = System.getenv("CHAOS_LATCH_DIR");
        String host = System.getenv().getOrDefault("RABBITMQ_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", "5672"));
        String user = System.getenv().getOrDefault("RABBITMQ_USER", "zorrodev");
        String password = System.getenv().getOrDefault("RABBITMQ_PASSWORD", "zorrodev");

        CachingConnectionFactory cf = new CachingConnectionFactory(host, port);
        cf.setUsername(user);
        cf.setPassword(password);
        RabbitAdmin admin = new RabbitAdmin(cf);
        admin.setAutoStartup(true);
        admin.afterPropertiesSet();

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(cf);
        factory.setMessageConverter(new Jackson2JsonMessageConverter(new ObjectMapper()));

        JobHandler hanging = new JobHandler() {
            @Override
            public String getJob() {
                return "killprobe";
            }

            @Override
            public List<com.zorrodev.bpm.exchange.ProcessVariable> handleJob(JobDetailModel model) {
                try {
                    touchEntered(latchDir);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("cannot create latch file in " + latchDir, e);
                }
                try {
                    System.out.println("CHAOS-WORKER entered handler, hanging until SIGKILL");
                    System.out.flush();
                    // Висеть, пока не убьют (kill -9). Никакого завершения работы —
                    // эффект НЕ коммитится, сообщение остаётся unacked.
                    Thread.sleep(600_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("chaos worker should have been SIGKILLed, not released");
            }
        };

        ObjectMapper objectMapper = new ObjectMapper();
        RabbitTemplate template = new RabbitTemplate(cf);
        template.setMessageConverter(new Jackson2JsonMessageConverter(new ObjectMapper()));
        JobCompletionListener listener = new JobCompletionListener(
            hanging, template, objectMapper,
            "chaos.it.jobs.killprobe", "chaos.it.complete.killprobe");

        SimpleMessageListenerContainer container = factory.createListenerContainer();
        container.setQueueNames("chaos.it.jobs.killprobe");
        container.setMessageListener(listener);
        container.setConcurrentConsumers(1);
        container.start();
        System.out.println("CHAOS-WORKER subscribed, waiting for job");
        System.out.flush();
        Thread.currentThread().join();
    }
}
