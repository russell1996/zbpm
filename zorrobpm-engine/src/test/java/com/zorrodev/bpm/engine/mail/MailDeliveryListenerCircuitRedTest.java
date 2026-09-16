package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.exchange.MailRequest;
import com.zorrodev.bpm.exchange.MailSendRequested;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-33 п.2 (POF, RED-вариант для pre-fix базы): после серии последовательных
 * сбоев SMTP транспорт НЕ должен вызываться на каждую следующую попытку —
 * circuit breaker обязан разомкнуть цепь и отвечать отказом без похода во внешнюю
 * систему (иначе недоступный SMTP забивает общий scheduler-пул синхронными
 * попытками с 10-секундными таймаутами).
 *
 * <p>На pre-fix базе у listener'а нет breaker'а (4-arg ctor) — транспорт вызывается
 * всегда → RED. На ветке этот же сценарий живёт в
 * {@code MailDeliveryListenerCircuitTest} (5-arg ctor + breaker) → GREEN.
 * Утверждение одно и то же, отличается только конструкция listener'а, которую
 * навязывает сам фикс.
 */
@ExtendWith(MockitoExtension.class)
class MailDeliveryListenerCircuitRedTest {

    @Mock JavaMailSenderImpl javaMailSender;
    @Mock ApplicationEventPublisher publisher;
    @Mock MimeMessage mimeMessage;
    @Mock MailConfigResolver configResolver;
    @Mock MailTransportFactory transportFactory;

    private MailStatus mailStatus;
    private MailDeliveryListener listener;

    @BeforeEach
    void setUp() {
        mailStatus = new MailStatus();
        listener = new MailDeliveryListener(configResolver, transportFactory, publisher, mailStatus);
        ResolvedMailConfig cfg = new ResolvedMailConfig("smtp.test.com", 587, "user", "pass", "from@test.com", "");
        lenient().when(configResolver.getEffectiveConfig()).thenReturn(cfg);
        lenient().when(transportFactory.build("smtp.test.com", 587, "user", "pass")).thenReturn(javaMailSender);
    }

    @Test
    void afterConsecutiveFailures_transportIsNotHitOnNextAttempt() throws Exception {
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("Connection refused", new jakarta.mail.MessagingException("down")))
            .when(javaMailSender).send(any(MimeMessage.class));

        MailRequest request = new MailRequest("user@test.com", "Subject", "body", false);
        for (int i = 0; i < 6; i++) {
            listener.on(new MailSendRequested(request, "outbox-" + i));
        }

        // 5 сбоев подряд обязаны разомкнуть цепь: 6-я попытка — без build/send.
        // Pre-fix: breaker'а нет, build вызывается все 6 раз → RED.
        verify(transportFactory, times(5)).build("smtp.test.com", 587, "user", "pass");
    }
}
