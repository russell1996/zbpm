package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.dto.UpdateUiUserDTO;
import com.zorrodev.bpm.contract.exception.EngineException;
import com.zorrodev.bpm.engine.PostgresIT;
import com.zorrodev.bpm.engine.entity.UiUserEntity;
import com.zorrodev.bpm.engine.repository.UiUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-AUDIT-21 (PostgreSQL): защита «последнего активного SUPER_ADMIN» при двух
 * КОНКУРЕНТНЫХ понижениях — на ПРОД-СУБД, а не на H2.
 *
 * Почему PG, а не H2 (V11, P-17/P-22): сама защита — это
 * {@code SELECT ... FOR UPDATE} по строкам SUPER_ADMIN + пересчёт количества
 * ({@code UiUserServiceImpl.update} → {@code UiUserRepository
 * .findByRoleAndActiveAndUserTypeForUpdate}). Семантика «FOR UPDATE ждёт
 * блокировку соперника и ПЕРЕЧИТЫВАЕТ уже изменённые строки» — ровно то, где
 * H2 и PostgreSQL расходятся, поэтому H2-зелёный здесь ничего не доказывает.
 *
 * Что доказывает тест (не «гонка в тесте», а поведение ПРОД-ЗАЩИТЫ):
 * при двух одновременных понижениях двух последних активных HUMAN
 * SUPER_ADMIN ровно одно понижение проходит, второе отклоняется
 * (EngineException «last active SUPER_ADMIN»), и в системе остаётся ровно
 * один активный SUPER_ADMIN — то есть «0 суперадминов» недостижимо.
 *
 * Два настоящих потока и две настоящие транзакции (V6): вызовы идут напрямую
 * в боевой бин {@link UiUserServiceImpl}, каждый поток открывает свою
 * транзакцию (тест НЕ помечен @Transactional — иначе обе гонки попали бы в одну
 * транзакцию и гонки не было бы вовсе).
 */
@Tag("pg")
public class LastSuperAdminGuardPgIT extends PostgresIT {

    @Autowired private UiUserServiceImpl userService;
    @Autowired private UiUserRepository userRepository;

    private final List<UUID> cleanupIds = Collections.synchronizedList(new ArrayList<>());
    /** Суперадмины, временно снятые с «резерва», чтобы их не считал guard. */
    private final List<UUID> temporarilyDeactivated = Collections.synchronizedList(new ArrayList<>());
    private UUID seededAdminId;
    private String seededAdminOriginalRole;
    private boolean seededAdminOriginalActive;
    private String seededAdminOriginalUserType;

    @AfterEach
    void restoreWorld() {
        for (UUID id : List.copyOf(temporarilyDeactivated)) {
            try {
                UiUserEntity row = userRepository.findById(id).orElse(null);
                if (row != null) {
                    row.setActive(true);
                    userRepository.saveAndFlush(row);
                }
            } catch (Exception ignored) {
                // best effort — восстановление не должно ронять прогон
            }
        }
        temporarilyDeactivated.clear();
        if (seededAdminId != null) {
            try {
                UiUserEntity admin = userRepository.findById(seededAdminId).orElse(null);
                if (admin != null) {
                    admin.setRole(seededAdminOriginalRole);
                    admin.setActive(seededAdminOriginalActive);
                    admin.setUserType(seededAdminOriginalUserType);
                    userRepository.saveAndFlush(admin);
                }
            } catch (Exception ignored) {
                // best effort
            }
        }
        for (UUID id : List.copyOf(cleanupIds)) {
            try {
                userRepository.deleteById(id);
            } catch (Exception ignored) {
                // already gone
            }
        }
        cleanupIds.clear();
    }

    @Test
    void concurrentDemotions_lastTwoSuperAdmins_exactlyOneRejected_onPostgres() throws Exception {
        seededAdminId = userRepository.findByUsername("admin").orElseThrow().getId();
        UiUserEntity admin = userRepository.findById(seededAdminId).orElseThrow();
        seededAdminOriginalRole = admin.getRole();
        seededAdminOriginalActive = admin.isActive();
        seededAdminOriginalUserType = admin.getUserType();

        // «Резерв» защиты считает только активных HUMAN SUPER_ADMIN. На время
        // теста снимаем с Reserve засеянного админа и любые посторонние следы
        // других тестов — чтобы предпосылка «ровно два» была точной. Всё
        // возвращается в @AfterEach (restoreWorld).
        for (UiUserEntity row : userRepository.findAll()) {
            if ("SUPER_ADMIN".equals(row.getRole()) && row.isActive() && "HUMAN".equals(row.getUserType())) {
                row.setActive(false);
                userRepository.saveAndFlush(row);
                temporarilyDeactivated.add(row.getId());
            }
        }
        userRepository.flush();

        // Два суперадмина, которых тест создал сам и которых он же и снимает.
        UUID firstId = createSuperAdmin("pg-a21-first");
        UUID secondId = createSuperAdmin("pg-a21-second");
        cleanupIds.add(firstId);
        cleanupIds.add(secondId);

        assertThat(countActiveHumanSuperAdmins())
            .as("Precondition: exactly two active HUMAN SUPER_ADMIN")
            .isEqualTo(2);

        CyclicBarrier bothInside = new CyclicBarrier(2);
        AtomicInteger succeeded = new AtomicInteger(0);
        AtomicReference<Throwable> rejected = new AtomicReference<>();

        Runnable demoteFirst = () -> attempt(bothInside, firstId, succeeded, rejected);
        Runnable demoteSecond = () -> attempt(bothInside, secondId, succeeded, rejected);

        Thread first = new Thread(demoteFirst, "a21-demote-first");
        Thread second = new Thread(demoteSecond, "a21-demote-second");
        first.start();
        second.start();
        first.join(60_000);
        second.join(60_000);

        assertThat(succeeded.get())
            .as("Exactly one demotion must succeed (the other must be rejected by the guard)")
            .isEqualTo(1);
        assertThat(rejected.get())
            .as("The losing demotion must be rejected by the last-SUPER_ADMIN guard")
            .isInstanceOf(EngineException.class)
            .hasMessageContaining("last active SUPER_ADMIN");
        assertThat(countActiveHumanSuperAdmins())
            .as("After the race exactly one active HUMAN SUPER_ADMIN must remain (never zero)")
            .isEqualTo(1);
    }

    private void attempt(CyclicBarrier barrier, UUID targetId, AtomicInteger succeeded, AtomicReference<Throwable> rejected) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
            UpdateUiUserDTO dto = new UpdateUiUserDTO();
            dto.setRole("USER");
            userService.update(targetId, dto);
            succeeded.incrementAndGet();
        } catch (Throwable t) {
            rejected.compareAndSet(null, t);
        }
    }

    private long countActiveHumanSuperAdmins() {
        return userRepository.findAll().stream()
            .filter(u -> "SUPER_ADMIN".equals(u.getRole()) && u.isActive() && "HUMAN".equals(u.getUserType()))
            .count();
    }

    private UUID createSuperAdmin(String prefix) {
        UiUserEntity entity = new UiUserEntity();
        entity.setId(UUID.randomUUID());
        entity.setUsername(prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        entity.setPasswordHash("hashed");
        entity.setFullName("pg");
        entity.setRole("SUPER_ADMIN");
        entity.setActive(true);
        entity.setUserType("HUMAN");
        entity.setForcePasswordChange(false);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return userRepository.saveAndFlush(entity).getId();
    }
}
