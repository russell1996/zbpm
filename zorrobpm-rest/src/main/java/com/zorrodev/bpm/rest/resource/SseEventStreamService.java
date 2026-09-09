package com.zorrodev.bpm.rest.resource;

import com.zorrodev.bpm.engine.repository.DomainEventRepository;
import com.zorrodev.bpm.engine.entity.DomainEventEntity;
import com.zorrodev.bpm.engine.security.Principal;
import com.zorrodev.bpm.rabbitmq.configuration.RabbitConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE bridge: subscribes to zorrobpm.events (exclusive queue per instance)
 * and pushes events to connected SSE clients with AuthZ filtering (ADR-7, WO-EVT-4).
 */
@Slf4j
@Service
public class SseEventStreamService {

    private final DomainEventRepository domainEventRepository;
    private final EventAuthzResolver eventAuthzResolver;
    private final RabbitAdmin rabbitAdmin;
    // WO-PERF-1 N4: single thread-safe Jackson 3 ObjectMapper instance (replaces per-message new)
    private final tools.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    public SseEventStreamService(DomainEventRepository domainEventRepository,
                                  EventAuthzResolver eventAuthzResolver,
                                  @Lazy @Autowired(required = false) RabbitAdmin rabbitAdmin,
                                  tools.jackson.databind.ObjectMapper objectMapper) {
        this.domainEventRepository = domainEventRepository;
        this.eventAuthzResolver = eventAuthzResolver;
        this.rabbitAdmin = rabbitAdmin;
        this.objectMapper = objectMapper;
    }

    /** Connected SSE clients: emitterId → client info */
    private final Map<String, SseClientInfo> clients = new ConcurrentHashMap<>();

    /** RabbitMQ listener container for this instance */
    private volatile SimpleMessageListenerContainer listenerContainer;

    /**
     * Guards bridge lifecycle transitions (start/stop/replace). The lock is
     * only ever held for fast local work (flag checks, declares, assign) —
     * never for {@code container.start()}, which may block up to the
     * consumer-start timeout on a sick broker.
     */
    private final Object bridgeLock = new Object();

    /** A bridge start is in flight on a background thread (at most one). */
    private final java.util.concurrent.atomic.AtomicBoolean bridgeStarting =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Functional interface for test event capture: receives clientId + envelope. */
    @FunctionalInterface
    public interface EventDispatchListener {
        void onEventSent(String clientId, Map<String, Object> envelope);
    }

    /** Test/observability hook: called when an event is sent to a client */
    private final List<EventDispatchListener> eventListeners = new CopyOnWriteArrayList<>();

    /** Register a listener that receives each event dispatch (clientId + envelope). */
    public void addEventListener(EventDispatchListener listener) {
        eventListeners.add(listener);
    }

    /** Remove all event listeners (for test cleanup). */
    public void clearEventListeners() {
        eventListeners.clear();
    }

    /**
     * Registers an SSE client and starts RabbitMQ subscription if this is the first client.
     */
    public String registerClient(SseEmitter emitter, Principal principal, String typeFilter,
                                  String processInstanceIdFilter, String processDefinitionKeyFilter) {
        String clientId = UUID.randomUUID().toString();
        Collection<UUID> allowedPdIds = eventAuthzResolver.readableRuntimePdIds(principal, processDefinitionKeyFilter);

        SseClientInfo info = new SseClientInfo(clientId, emitter, principal, allowedPdIds,
            typeFilter, processInstanceIdFilter, processDefinitionKeyFilter);
        clients.put(clientId, info);

        emitter.onCompletion(() -> {
            clients.remove(clientId);
            log.info("SSE client {} disconnected (completion)", clientId);
            stopRabbitMqListenerIfNoClients();
        });
        emitter.onTimeout(() -> {
            clients.remove(clientId);
            log.info("SSE client {} disconnected (timeout)", clientId);
            stopRabbitMqListenerIfNoClients();
        });
        emitter.onError(e -> {
            clients.remove(clientId);
            log.info("SSE client {} disconnected (error: {})", clientId, e.getMessage());
            stopRabbitMqListenerIfNoClients();
        });

        // If this is the first client, start RabbitMQ subscription.
        // Never on the HTTP thread: declares + container.start() are blocking
        // broker RPCs (consumer start waits up to 60s on a sick broker), and a
        // stalled broker once hung GET /events/stream with zero response
        // (WO-REL-20). The stream opens immediately; events flow once ready.
        ensureBridgeStarted();

        log.info("SSE client {} registered: type={}, processInstanceId={}, processDefinitionKey={}",
            clientId, typeFilter, processInstanceIdFilter, processDefinitionKeyFilter);
        return clientId;
    }

    /**
     * Removes an SSE client.
     */
    public void removeClient(String clientId) {
        clients.remove(clientId);
        log.info("SSE client {} removed", clientId);
        stopRabbitMqListenerIfNoClients();
    }

    /**
     * Called when a domain event arrives from RabbitMQ.
     * Pushes to all connected clients that match the filter and AuthZ.
     */
    public void onDomainEvent(String messageBody) {
        Map<String, Object> envelope;
        try {
            envelope = objectMapper.readValue(messageBody, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse domain event envelope", e);
            return;
        }

        String eventType = (String) envelope.get("type");
        String processInstanceId = (String) envelope.get("processInstanceId");
        String processDefinitionId = (String) envelope.get("processDefinitionId");
        Object sequenceObj = envelope.get("sequence");
        long sequence = sequenceObj instanceof Number n ? n.longValue() : 0;

        for (SseClientInfo client : clients.values()) {
            try {
                // Check type filter
                if (client.typeFilter != null && !client.typeFilter.isBlank()
                    && !client.typeFilter.equals(eventType)) {
                    continue;
                }

                // Check processInstanceId filter
                if (client.processInstanceIdFilter != null && !client.processInstanceIdFilter.isBlank()
                    && !client.processInstanceIdFilter.equals(processInstanceId)) {
                    continue;
                }

                // Check AuthZ: processDefinitionId must be in allowed set (fail-closed: G-L)
                if (client.allowedPdIds != null) {
                    if (processDefinitionId == null || !client.allowedPdIds.contains(UUID.fromString(processDefinitionId))) {
                        continue;
                    }
                }

                // Push event to client
                SseEmitter.SseEventBuilder event = SseEmitter.event()
                    .id(String.valueOf(sequence))
                    .name(eventType)
                    .data(envelope)
                    .reconnectTime(3000);

                // Notify listeners (test/observability hook)
                for (EventDispatchListener listener : eventListeners) {
                    try {
                        listener.onEventSent(client.clientId, envelope);
                    } catch (Exception ex) {
                        log.warn("Event listener error", ex);
                    }
                }

                client.emitter.send(event);
            } catch (IOException e) {
                log.warn("Failed to send event to client {}: {}", client.clientId, e.getMessage());
                clients.remove(client.clientId);
            } catch (Exception e) {
                log.error("Error sending event to client {}", client.clientId, e);
            }
        }
    }

    /**
     * Sends catchup events from the database for reconnect (Last-Event-ID).
     */
    public void sendCatchupEvents(SseEmitter emitter, long sinceSequence, Principal principal,
                                   String processDefinitionKeyFilter) {
        Collection<UUID> allowedPdIds = eventAuthzResolver.readableRuntimePdIds(principal, processDefinitionKeyFilter);

        int limit = 100;
        List<DomainEventEntity> events;
        if (allowedPdIds == null) {
            events = domainEventRepository.findSince(sinceSequence, limit);
        } else {
            events = domainEventRepository.findSinceForPrincipal(sinceSequence, allowedPdIds, limit);
        }

        for (DomainEventEntity event : events) {
            try {
                Map<String, Object> envelope = Map.of(
                    "sequence", event.getSequence(),
                    "id", event.getId().toString(),
                    "type", event.getType(),
                    "version", event.getVersion(),
                    "occurredAt", event.getOccurredAt().toString(),
                    "processDefinitionId", event.getProcessDefinitionId() != null ? event.getProcessDefinitionId().toString() : null,
                    "processInstanceId", event.getProcessInstanceId() != null ? event.getProcessInstanceId().toString() : null,
                    "elementId", event.getElementId() != null ? event.getElementId() : null,
                    "ownerScope", event.getOwnerScope() != null ? event.getOwnerScope() : null,
                    "data", event.getData() != null ? event.getData() : Map.of()
                );

                SseEmitter.SseEventBuilder sseEvent = SseEmitter.event()
                    .id(String.valueOf(event.getSequence()))
                    .name(event.getType())
                    .data(envelope)
                    .reconnectTime(3000);

                emitter.send(sseEvent);
            } catch (Exception e) {
                log.error("Error sending catchup event {}", event.getSequence(), e);
                break;
            }
        }
    }

    /**
     * Triggers a bridge start unless one is already running or starting.
     * Returns immediately — the start itself runs on a background thread.
     * Safe to call from every registration (not just the first client): the
     * flag makes concurrent/duplicate starts impossible, and a failed start
     * simply leaves the bridge DOWN so the next registration retries.
     */
    private void ensureBridgeStarted() {
        synchronized (bridgeLock) {
            if ((listenerContainer == null || !listenerContainer.isRunning())
                    && bridgeStarting.compareAndSet(false, true)) {
                Thread.ofVirtual().name("sse-bridge-starter").start(() -> {
                    try {
                        startBridgeNow();
                    } catch (Exception e) {
                        log.error("SSE bridge: failed to start RabbitMQ listener (next registration retries)", e);
                    } finally {
                        bridgeStarting.set(false);
                    }
                });
            }
        }
    }

    private void startBridgeNow() {
        if (rabbitAdmin == null) {
            log.warn("RabbitAdmin not available — SSE bridge not started (no RabbitMQ)");
            return;
        }

        String queueName = "zorrobpm.sse-bridge." + UUID.randomUUID();
        Queue queue = new Queue(queueName, false, true, true); // exclusive, auto-delete
        // Blocking broker RPCs run WITHOUT holding bridgeLock: a stalled
        // broker must not serialize registrations (or stop()) behind them —
        // holding the lock across declares reintroduced the very
        // request pile-up WO-REL-20 removes (caught by SseBridgeStartupTest).
        try {
            rabbitAdmin.declareQueue(queue);
            Binding binding = BindingBuilder.bind(queue)
                .to(new TopicExchange(RabbitConfiguration.EVENTS_EXCHANGE, true, false))
                .with("#");
            rabbitAdmin.declareBinding(binding);
        } catch (RuntimeException e) {
            // Partial declare must not leave an orphan queue behind.
            deleteQueueQuietly(queueName);
            throw e;
        }
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
        container.setConnectionFactory(rabbitAdmin.getRabbitTemplate().getConnectionFactory());
        container.setQueueNames(queueName);
        container.setMessageListener((message) -> {
            String body = new String(message.getBody());
            onDomainEvent(body);
        });
        // Outside the lock as well: may block up to the consumer-start
        // timeout on a sick broker.
        try {
            container.start();
        } catch (RuntimeException e) {
            stopAndDestroy(container);
            deleteQueueQuietly(queueName);
            throw e;
        }
        synchronized (bridgeLock) {
            if (clients.isEmpty()) {
                // Everyone left while we were starting: stop immediately
                // instead of leaking a running bridge nobody consumes.
                stopAndDestroyLocked(container);
                log.info("SSE bridge: starter finished with no clients left — stopped immediately");
                return;
            }
            // A newer container may have been assigned concurrently (stop+start
            // race): keep exactly one — the running one wins, ours goes down.
            if (listenerContainer != null && listenerContainer.isRunning()) {
                stopAndDestroyLocked(container);
                return;
            }
            destroyContainerLocked();
            this.listenerContainer = container;
        }
        log.info("SSE bridge: started RabbitMQ listener on queue {}", queueName);
    }

    private void stopRabbitMqListenerIfNoClients() {
        synchronized (bridgeLock) {
            if (!clients.isEmpty()) {
                return;
            }
            SimpleMessageListenerContainer container = listenerContainer;
            listenerContainer = null;
            stopAndDestroyLocked(container);
            log.info("SSE bridge: stopped RabbitMQ listener (no clients)");
        }
    }

    /** Must hold {@link #bridgeLock}. Stops + destroys the current container, if any. */
    private void destroyContainerLocked() {
        SimpleMessageListenerContainer container = listenerContainer;
        listenerContainer = null;
        stopAndDestroy(container);
    }

    /**
     * Stops + destroys a container owned solely by the caller (a failed local
     * start that was never published). No lock needed — nobody else can see it.
     */
    private void stopAndDestroy(SimpleMessageListenerContainer container) {
        if (container == null) {
            return;
        }
        try {
            if (container.isRunning()) {
                container.stop();
            }
        } catch (Exception e) {
            log.warn("SSE bridge: error stopping listener container", e);
        } finally {
            try {
                container.destroy();
            } catch (Exception e) {
                log.warn("SSE bridge: error destroying listener container", e);
            }
        }
    }

    /** Must hold {@link #bridgeLock}. */
    private void stopAndDestroyLocked(SimpleMessageListenerContainer container) {
        stopAndDestroy(container);
    }

    private void deleteQueueQuietly(String queueName) {
        try {
            rabbitAdmin.deleteQueue(queueName);
        } catch (Exception cleanupEx) {
            log.warn("SSE bridge: failed to clean up queue {} after failed start", queueName, cleanupEx);
        }
    }

    private record SseClientInfo(
        String clientId,
        SseEmitter emitter,
        Principal principal,
        Collection<UUID> allowedPdIds,
        String typeFilter,
        String processInstanceIdFilter,
        String processDefinitionKeyFilter
    ) {}
}
