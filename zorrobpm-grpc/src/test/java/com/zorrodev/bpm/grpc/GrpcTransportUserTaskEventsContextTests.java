package com.zorrodev.bpm.grpc;

import com.zorrodev.bpm.exchange.UserTaskEventPublisher;
import com.zorrodev.bpm.rabbitmq.ServiceTaskListener;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine on {@code zorrobpm.transport=grpc} with the user task events on and no broker: it starts,
 * the event publisher is there, the job queues and their listener are not.
 */
@SpringBootTest(classes = GrpcTestApplication.class, properties = "zorrobpm.events.user-task.enabled=true")
class GrpcTransportUserTaskEventsContextTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void grpcTransportPublishesUserTaskEventsWithoutJobQueues() {
        assertThat(context.getBeansOfType(JobGrpcService.class)).hasSize(1);
        assertThat(context.getBeansOfType(UserTaskEventPublisher.class)).hasSize(1);

        assertThat(context.getBeansOfType(ServiceTaskListener.class)).isEmpty();
        assertThat(context.containsBean("completeServiceTaskContainerFactory")).isFalse();
        assertThat(context.getBeansOfType(Queue.class)).isEmpty();
    }
}
