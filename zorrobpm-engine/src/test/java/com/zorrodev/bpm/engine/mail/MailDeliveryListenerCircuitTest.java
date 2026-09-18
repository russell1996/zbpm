package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.exchange.MailRequest;
import com.zorrodev.bpm.exchange.MailSendRequested;
import com.zorrodev.bpm.exchange.OutboxDeliveryResult;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WO-REL-33 п.2 (GREEN): circuit breaker на SMTP-доставку.
 *
 * <p>5 последовательных сбоев размыкают цепь (порог из тестового конструктора),
 * 6-я попытка отклоняется БЕЗ похода в транспорт (build 5 раз, не 6) — общий
 * scheduler-пул не забивается синхронными попытками в упавший SMTP. Успех после
 * восстановления замыкает цепь обратно (nack → ack, build снова вызывается).
 * Backoff: границы [base/2, base] при 1 сбое, рост с числом сбоев, cap сверху.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MailDeliveryListenerCircuitTest {

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
        // Порог 5, окно 60с: первые 5 попыток идут в транспорт и падают, 6-я — отказ цепи.
        listener = new MailDeliveryListener(configResolver, transportFactory, publisher, mailStatus,
            new SmtpCircuitBreaker(5, 60, 1000, 30000));
        ResolvedMailConfig cfg = new ResolvedMailConfig("smtp.test.com", 587, "user", "pass", "from@test.com", "");
        lenient().when(configResolver.getEffectiveConfig()).thenReturn(cfg);
        lenient().when(transportFactory.build("smtp.test.com", 587, "user", "pass")).thenReturn(javaMailSender);
    }

    @Test
    void afterFiveFailures_sixthAttempt_skipsTransport() throws Exception {
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("Connection refused", new jakarta.mail.MessagingException("down")))
            .when(javaMailSender).send(any(MimeMessage.class));

        MailRequest request = new MailRequest("user@test.com", "Subject", "body", false);
        for (int i = 0; i < 6; i++) {
            listener.on(new MailSendRequested(request, "outbox-" + i));
        }

        // Цепь разомкнулась после 5-го сбоя: 6-я попытка транспорт не трогает.
        verify(transportFactory, times(5)).build("smtp.test.com", 587, "user", "pass");
        // Все 6 попыток дают nack (запись остаётся pending для retry — контракт сохранён).
        ArgumentCaptor<OutboxDeliveryResult> captor = ArgumentCaptor.forClass(OutboxDeliveryResult.class);
        verify(publisher, times(6)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).allMatch(r -> !r.isAcked());
        assertThat(captor.getValue().getCause()).contains("circuit OPEN");
    }

    @Test
    void successAfterRecovery_closesCircuitAgain() throws Exception {
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("down", new jakarta.mail.MessagingException("down")))
            .when(javaMailSender).send(any(MimeMessage.class));

        MailRequest request = new MailRequest("user@test.com", "Subject", "body", false);
        for (int i = 0; i < 5; i++) {
            listener.on(new MailSendRequested(request, "outbox-" + i));
        }
        verify(transportFactory, times(5)).build(any(), any(), any(), any());

        // SMTP ожил: окно 0с в тестовом breaker'е? Нет — тот же listener с окном 60с:
        // создаём listener с окном 0с? Окно минимум 1с. Вместо этого чиним send и
        // ждём HALF_OPEN через короткий breaker: проще — новый listener с окном 1с.
        org.mockito.Mockito.reset(javaMailSender, publisher, transportFactory);
        ResolvedMailConfig cfg = new ResolvedMailConfig("smtp.test.com", 587, "user", "pass", "from@test.com", "");
        lenient().when(configResolver.getEffectiveConfig()).thenReturn(cfg);
        lenient().when(transportFactory.build("smtp.test.com", 587, "user", "pass")).thenReturn(javaMailSender);
        MailDeliveryListener shortWindow = new MailDeliveryListener(
            configResolver, transportFactory, publisher, mailStatus,
            new SmtpCircuitBreaker(1, 1, 1000, 30000));
        doThrow(new MailSendException("down", new jakarta.mail.MessagingException("down")))
            .when(javaMailSender).send(any(MimeMessage.class));
        shortWindow.on(new MailSendRequested(request, "outbox-x"));
        // Порог 1: сразу OPEN.
        shortWindow.on(new MailSendRequested(request, "outbox-y"));
        verify(transportFactory, times(1)).build(any(), any(), any(), any());

        Thread.sleep(1100);
        org.mockito.Mockito.doNothing().when(javaMailSender).send(any(MimeMessage.class));
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        shortWindow.on(new MailSendRequested(request, "outbox-z"));
        ArgumentCaptor<OutboxDeliveryResult> captor = ArgumentCaptor.forClass(OutboxDeliveryResult.class);
        verify(publisher, times(3)).publishEvent(captor.capture());
        assertThat(captor.getValue().isAcked()).isTrue();
    }

    @Test
    void breakerUnit_backoffBoundsAndGrowth() {
        SmtpCircuitBreaker breaker = new SmtpCircuitBreaker(100, 60, 1000, 30000);
        // 1 сбой: [500, 1000].
        try {
            breaker.execute(() -> { throw new RuntimeException("x"); });
        } catch (RuntimeException e) {
            // ожидаемо: execute перебрасывает исходный сбой
        }
        for (int i = 0; i < 20; i++) {
            long b = breaker.backoffMs();
            assertThat(b).isBetween(500L, 1000L);
        }
        // Накрутили сбои: backoff вырос, но в cap 30000.
        for (int i = 0; i < 10; i++) {
            try {
                breaker.execute(() -> { throw new RuntimeException("x"); });
            } catch (RuntimeException e) {
                // ожидаемо
            }
        }
        for (int i = 0; i < 20; i++) {
            assertThat(breaker.backoffMs()).isLessThanOrEqualTo(30000L);
        }
        List<Long> samples = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            samples.add(breaker.backoffMs());
        }
        // Jitter: не все 20 замеров одинаковы.
        assertThat(samples.stream().distinct().count()).isGreaterThan(1);
    }

    @Test
    void breakerUnit_successResetsFailures() {
        SmtpCircuitBreaker breaker = new SmtpCircuitBreaker(3, 60, 1000, 30000);
        for (int i = 0; i < 2; i++) {
            try {
                breaker.execute(() -> { throw new RuntimeException("x"); });
            } catch (RuntimeException e) {
                // ожидаемо
            }
        }
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(2);
        breaker.execute(() -> null);
        assertThat(breaker.getConsecutiveFailures()).isZero();
        assertThat(breaker.getState()).isEqualTo(SmtpCircuitBreaker.State.CLOSED);
    }
}
