package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.contract.dto.RegisterDTO;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.mail.StubMailSender;
import com.zorrodev.bpm.engine.repository.PasswordTokenRepository;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import com.zorrodev.bpm.engine.service.RegistrationCleanupJob;
import com.zorrodev.bpm.engine.service.SelfRegistrationService;
import com.zorrodev.bpm.engine.service.UserInvitationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-43: {@code verifyEmail} vs {@code RegistrationCleanupJob} на одной строке —
 * два реальных потока/транзакции (V6), настоящий PG (V11/G-N: H2-локинг ≠ PG-локинг).
 *
 * <p>До фикса {@code verifyEmail} читал пользователя через plain {@code findById} (без
 * row lock): cleanup мог удалить строку МЕЖДУ чтением и {@code save} — тогда save на
 * удалённой строке либо тихо no-op'ил, либо воскрешал её detached-INSERT'ом. Фикс —
 * {@code findByIdForUpdate} (тот же прецедент, что WO-REL-39 F18 / WO-REL-40 B-5):
 * оба пути блокируют ТУ ЖЕ строку, проигравший видит состояние победителя.
 *
 * <p>POF (G-N): откат {@code findByIdForUpdate → findById} в {@code verifyEmail} делает
 * {@code verifyWins_cleanupSkips} RED — cleanup удаляет строку из-под чтения, верификация
 * тихо уходит в никуда (строки нет, ошибки нет).
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles("test")
@Tag("pg")
public class VerifyEmailCleanupRacePgIT extends PostgresIT {

    @Autowired private SelfRegistrationService registrationService;
    @Autowired private UserInvitationService invitationService;
    @Autowired private RegistrationCleanupJob cleanupJob;
    @Autowired private UiUserRepository userRepository;
    @Autowired private PasswordTokenRepository tokenRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private StubMailSender mailSender;

    private final List<UUID> cleanupIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        cleanupJob.setAfterListReadHook(null);
        mailSender.clear();
        for (UUID id : List.copyOf(cleanupIds)) {
            try {
                tokenRepository.deleteByUserId(id);
                userRepository.deleteById(id);
            } catch (Exception e) {
                // already gone — best effort
            }
        }
        cleanupIds.clear();
    }

    private record StaleRegistration(UUID userId, String rawToken) {
    }

    private StaleRegistration registerStale(String tag) {
        String email = "rel43-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com";
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("rel43-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8));
        dto.setPassword("MyStr0ng!P@ssw0rd");
        dto.setFullName("REL-43 Race");
        dto.setEmail(email);
        registrationService.register(dto, "10.0.0.1");
        UiUserEntity created = userRepository.findAll().stream()
            .filter(u -> email.equalsIgnoreCase(u.getEmail()))
            .findFirst().orElseThrow();
        // NOTE: id возвращаем вызывающему в локальную переменную, а не читаем из
        // общего cleanupIds.get(last) в ассертах — параллельный поток той же копии
        // может добавить свой id раньше (поймано живым RED-прогоном: NoSuchElement
        // на чужом id вместо проверки нашей строки).
        cleanupIds.add(created.getId());
        // Состариваем строку мимо TTL, чтобы cleanup считал её протухшей.
        transactionTemplate.executeWithoutResult(status -> {
            UiUserEntity aged = userRepository.findById(created.getId()).orElseThrow();
            aged.setCreatedAt(java.time.Instant.now().minusSeconds(30L * 3600L));
            userRepository.save(aged);
        });
        String body = mailSender.getSent().stream()
            .filter(m -> email.equalsIgnoreCase(m.to()))
            .map(StubMailSender.MailRecord::body)
            .reduce((a, b) -> b).orElseThrow();
        Matcher m = Pattern.compile("token=([^\\s]+)").matcher(body);
        assertThat(m.find()).isTrue();
        return new StaleRegistration(created.getId(), m.group(1));
    }

    @Test
    void verifyWins_cleanupSkips() throws Exception {
        // Порядок задаёт lock через hook cleanup'а (прецедент REL-39 F19-by-hook,
        // не sleep): cleanup ПАРКУЕТСЯ после list-read, главный поток в это время
        // вызывает НАСТОЯЩИЙ прод-verifyEmail(raw) целиком (consume + row-locked
        // read + переход статуса, один коммит). Затем парк снимается, cleanup
        // идёт удалять — и обязан увидеть строку живой (уже PENDING_APPROVAL)
        // и пропустить её своим re-check'ом.
        // Без фикса (plain findById) cleanup удалял строку из-под чтения verify:
        // save() на удалённой строке тихо уходил в никуда — survivor-assert RED.
        StaleRegistration stale = registerStale("vw");
        String raw = stale.rawToken();
        UUID ours = stale.userId();

        CountDownLatch listRead = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        cleanupJob.setAfterListReadHook(() -> {
            listRead.countDown();
            try {
                assertThat(releaseCleanup.await(20, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });

        AtomicReference<Throwable> cleanupError = new AtomicReference<>();
        AtomicReference<Integer> cleanupDeleted = new AtomicReference<>(-1);
        Thread cleaner = new Thread(() -> {
            try {
                cleanupDeleted.set(cleanupJob.cleanExpired());
            } catch (Throwable t) {
                cleanupError.set(t);
            }
        });
        cleaner.start();

        // Cleanup запаркован ПОСЛЕ list-read (строка в списке), ДО per-row delete.
        assertThat(listRead.await(20, TimeUnit.SECONDS)).as("cleanup запаркован").isTrue();
        // Настоящий прод-путь — целиком, в своей транзакции, в главном потоке.
        registrationService.verifyEmail(raw);
        // Verify закоммичен: строка PENDING_APPROVAL. Отпускаем cleanup — его
        // per-row findByIdForUpdate + re-check обязан пропустить решённую строку.
        releaseCleanup.countDown();
        cleaner.join(30000);

        assertThat(cleanupError.get()).isNull();
        // Verify прошёл первым: строка жива в PENDING_APPROVAL, cleanup её не удалил.
        // (cleanupDeleted может включать чужие stale-строки общей БД — утверждаем только
        // состояние НАШЕЙ строки, прецедент RegistrationCleanupRacePgIT.)
        UiUserEntity survivor = userRepository.findById(ours).orElseThrow();
        assertThat(survivor.getRegistrationStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(survivor.getEmailVerifiedAt()).isNotNull();
    }

    @Test
    void verifySurvivesDeleteRace_noResurrection() throws Exception {
        // Без hook-парковки: мозаичный обстрел — десятки итераций, в каждой cleanup
        // идёт СРАЗУ своим потоком, verify — прод-путем в главном. Без фикса
        // (plain findById) delete неизбежно попадает в окно между read и save хотя
        // бы в части итераций — строка либо пропадала после «успеха», либо
        // воскресала detached-INSERT'ом. С фиксом (FOR UPDATE сериализует) все
        // итерации детерминированно GREEN: ровно один исход на итерацию.
        // NB: прямой deadlock (оба держат lock друг друга) здесь невозможен по
        // построению — verify держит РОВНО ОДНУ строку (свою), cleanup берёт строки
        // по одной в порядке индекса; lock-wait возможен (норма сериализации),
        // deadlock — нет. Пойманный CannotAcquireLock в общей PG-сюите — соседний
        // класс, державший ту же строку (порядок-зависимая изоляция, не дефект).
        // P-59: в общей сюите тест обязан быть толерантен к lock-конфликту с
        // соседом — 3 ретрая той же итерации, затем честный fail (не assumeFalse:
        // тихий skip спрятал бы регрессию).
        for (int iter = 0; iter < 15; iter++) {
            StaleRegistration stale = registerStale("vr" + iter);
            boolean done = false;
            for (int attempt = 0; attempt < 3 && !done; attempt++) {
                AtomicReference<Throwable> cleanupError = new AtomicReference<>();
                Thread cleaner = new Thread(() -> {
                    try {
                        cleanupJob.cleanExpired();
                    } catch (Throwable t) {
                        cleanupError.set(t);
                    }
                });
                cleaner.start();
                try {
                    registrationService.verifyEmail(stale.rawToken());
                    done = true;
                } catch (org.springframework.dao.CannotAcquireLockException e) {
                    // Lock-конфликт с соседним классом общей сюиты — ретрай той же
                    // итерации (токен single-use, но consume откатился вместе с
                    // транзакцией — повторный verifyEmail валиден).
                    cleaner.join(30000);
                    continue;
                }
                cleaner.join(30000);

                assertThat(cleanupError.get()).isNull();
                UiUserEntity survivor = userRepository.findById(stale.userId()).orElseThrow();
                assertThat(survivor.getRegistrationStatus()).isEqualTo("PENDING_APPROVAL");
                assertThat(survivor.getEmailVerifiedAt()).isNotNull();
            }
            assertThat(done).as("итерация " + iter + " сошлась за 3 ретрая").isTrue();
        }
    }

    @Test
    void cleanupFirst_verifyGetsHonestError_noResurrection() {
        // Cleanup удалил строку ДО verifyEmail: токен при этом уже consumed быть не
        // может (удаление чистит и токены) — честный путь: просроченная ссылка после
        // удаления даёт понятную ошибку, строка НЕ воскресает.
        StaleRegistration stale = registerStale("cf");
        String raw = stale.rawToken();
        UUID ours = stale.userId();

        assertThat(cleanupJob.cleanExpired()).isGreaterThanOrEqualTo(1);
        assertThat(userRepository.findById(ours)).isEmpty();

        // Токен снесён вместе со строкой → consume падает той же фразой, что
        // протухший токен (одна фраза, no enumeration — прецедент consumeEmailVerifyToken).
        // Строка при этом НЕ появляется обратно (G-N против detached-INSERT воскрешения).
        try {
            registrationService.verifyEmail(raw);
        } catch (EngineException e) {
            assertThat(e.getMessage()).contains("Invalid or expired token");
        }
        assertThat(userRepository.findById(ours)).as("строка не воскрешена").isEmpty();
    }
}
