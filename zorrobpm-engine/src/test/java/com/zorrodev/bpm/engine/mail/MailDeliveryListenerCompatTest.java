package com.zorrodev.bpm.engine.mail;

import com.zorrodev.bpm.exchange.MailRequest;
import com.zorrodev.bpm.exchange.MailSendRequested;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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
 * WO-REL-33: контракт 4-arg конструктора {@code MailDeliveryListener} (без breaker'а).
 *
 * <p>Старые тесты конструируют listener вручную — для них цепь эквивалентна вечно
 * закрытой (порог {@code Integer.MAX_VALUE}): транспорт вызывается на каждую
 * попытку, поведение побайтово как до WO. Этот тест фиксирует контракт: если
 * кто-то «починит» compat-ctor настоящим порогом, тест покраснеет и заставит
 * переписать старые тесты явно, а не молча изменить их поведение.
 * Защита от недоступного SMTP доказана в {@code MailDeliveryListenerCircuitTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MailDeliveryListenerCompatTest {

    @Mock JavaMailSenderImpl javaMailSender;
    @Mock ApplicationEventPublisher publisher;
    @Mock MimeMessage mimeMessage;
    @Mock MailConfigResolver configResolver;
    @Mock MailTransportFactory transportFactory;

    private MailDeliveryListener listener;

    @BeforeEach
    void setUp() {
        listener = new MailDeliveryListener(configResolver, transportFactory, publisher, new MailStatus());
        ResolvedMailConfig cfg = new ResolvedMailConfig("smtp.test.com", 587, "user", "pass", "from@test.com", "");
        lenient().when(configResolver.getEffectiveConfig()).thenReturn(cfg);
        lenient().when(transportFactory.build("smtp.test.com", 587, "user", "pass")).thenReturn(javaMailSender);
    }

    @Test
    void compatCtor_neverOpensCircuit_transportAlwaysHit() throws Exception {
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("Connection refused", new jakarta.mail.MessagingException("down")))
            .when(javaMailSender).send(any(MimeMessage.class));

        MailRequest request = new MailRequest("user@test.com", "Subject", "body", false);
        for (int i = 0; i < 6; i++) {
            listener.on(new MailSendRequested(request, "outbox-" + i));
        }

        // Compat-контракт: цепь не размыкается никогда — все 6 попыток идут в транспорт.
        verify(transportFactory, times(6)).build("smtp.test.com", 587, "user", "pass");
    }
}
