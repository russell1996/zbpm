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
  *
  * <p>POF раунда 2 (HOLD deadlock): инверсия порядка обратно T→U ({@code consumeEmailVerifyToken}
  * ДО {@code findByIdForUpdate}, второй consume убран) делает
  * {@code verifySurvivesDeleteRace_noResurrection} RED именованным
  * {@code DEADLOCK ... lock order user→token broken} — настоящий
  * {@code ERROR: deadlock detected} от Postgres.
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

    /** Deadlock-детектор (HOLD п.3): ищет «deadlock» по всей cause-цепочке. */
    private static boolean containsDeadlock(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String msg = cur.getMessage();
            if (msg != null && msg.toLowerCase(java.util.Locale.ROOT).contains("deadlock")) {
                return true;
            }
        }
        return false;
    }

    private StaleRegistration registerStale(String tag) {
        String email = "rel43-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@x.com";
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("rel43-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8));
        dto.setPassword("MyStr0ng!P@ssw0rd");
        dto.setFullName("REL-43 Race");
        dto.setEmail(email);
        // WO-REL-43 раунд 2: уникальный IP на регистрацию (подсеть 10.11.x — больше
        // ни один тестовый класс её не использует). Фиксированный 10.0.0.1 упирался
        // в ipCapacity=20 общей PG-сюиты (бакет делится между классами в одном
        // прогоне: EmailVerification/RegistrationAdmin тоже льют в 10.0.0.1) —
        // мозаика падала 429 НЕ по своей причине. Детерминировано от tag+email
        // (без Math.random — воспроизводимо, коллизия практически исключена:
        // tag уникален на вызов внутри класса).
        int h = Math.abs((tag + "|" + email).hashCode());
        int ipOctet3 = h % 200 + 1;
        int ipOctet4 = (h / 200) % 200 + 1;
        registrationService.register(dto, "10.11." + ipOctet3 + "." + ipOctet4);
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
        // Без hook-парковки: мозаичный обстрел — в каждой итерации cleanup идёт
        // СРАЗУ своим потоком, verify — прод-путем в главном. Кто выиграет гонку —
        // решает шедулинг, и ОБА исхода честные, поэтому тест принимает каждый
        // детерминированно (никаких ретраев-лотерей):
        //  - verify выиграл → строка PENDING_APPROVAL + emailVerifiedAt, cleanup
        //    её не удалил (его re-check пропустил решённую строку);
        //  - cleanup выиграл → verify падает понятной ошибкой ссылки, а строка
        //    ОБЯЗАНА отсутствовать (удалена, не воскрешена позже).
        // Без фикса раунда 1 (plain findById) delete попадал в окно между read и
        // save — строка либо пропадала после «успеха», либо воскресала
        // detached-INSERT'ом, и обе ветки ассертов REDили.
        // Раунд 2 (HOLD CTO — реальный deadlock 4/4: verify держал ДВЕ строки в
        // порядке токен→юзер против юзер→токен у cleanup). Фикс — порядок ЮЗЕР→ТОКЕН
        // с обеих сторон (peek → FOR UPDATE → consume). Deadlock ловится здесь КАК
        // ОТДЕЛЬНЫЙ СЦЕНАРИЙ (п.3 HOLD): сообщение с «deadlock» внутри любого
        // исключения → именованный fail «DEADLOCK», а не молча в ретрай.
        // P-10: 5 внешних итераций (15 упирались в registration rate-limit общей
        // БД — 429 не по своей причине; плюс детерминированный IP в registerStale).
        for (int iter = 0; iter < 5; iter++) {
            StaleRegistration stale = registerStale("vr" + iter);
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
            } catch (com.zorrodev.bpm.contract.exception.EngineException e) {
                // Cleanup выиграл честно: ошибка обязана быть про ссылку («expired» —
                // обе фразы: «Invalid or expired token», «Registration expired — ...»),
                // а строка — отсутствовать. Присутствующая строка + ошибка ссылки =
                // баг (verify упал, не доведя дело до конца, а cleanup не удалил).
                cleaner.join(30000);
                assertThat(cleanupError.get()).isNull();
                assertThat(e.getMessage()).as("честная ошибка ссылки").containsIgnoringCase("expired");
                assertThat(userRepository.findById(stale.userId()))
                    .as("cleanup выиграл — строки нет, воскрешения нет").isEmpty();
                continue;
            } catch (Throwable e) {
                // Deadlock — отдельный именованный исход (HOLD п.3), НЕ ретрай:
                // выровненный порядок блокировок обязан исключить его структурно.
                if (containsDeadlock(e) || containsDeadlock(cleanupError.get())) {
                    cleaner.join(30000);
                    org.junit.jupiter.api.Assertions.fail(
                        "DEADLOCK on iter " + iter + ": lock order user→token broken", e);
                }
                cleaner.join(30000);
                throw e;
            }
            cleaner.join(30000);

            // Verify выиграл: строка PENDING_APPROVAL, cleanup её не удалил.
            assertThat(cleanupError.get()).isNull();
            UiUserEntity survivor = userRepository.findById(stale.userId()).orElseThrow();
            assertThat(survivor.getRegistrationStatus()).isEqualTo("PENDING_APPROVAL");
            assertThat(survivor.getEmailVerifiedAt()).isNotNull();
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
