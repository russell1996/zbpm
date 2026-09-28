package com.zorrodev.bpm.engine.integration;

import com.zorrodev.bpm.engine.TestMain;
import com.zorrodev.bpm.engine.entity.ActivityEntity;
import com.zorrodev.bpm.engine.entity.ActivityStatus;
import com.zorrodev.bpm.engine.entity.ProcessDefinitionEntity;
import com.zorrodev.bpm.engine.entity.ProcessInstanceEntity;
import com.zorrodev.bpm.engine.repository.ActivityRepository;
import com.zorrodev.bpm.engine.repository.ProcessDefinitionRepository;
import com.zorrodev.bpm.engine.repository.ProcessInstanceRepository;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WO-REL-59 probe: какой SQL реально генерит
 * {@code ActivityRepository.findByIdForUpdate} на PostgreSQL.
 *
 * <p>Честный premise, на котором стоит весь фикс WO-REL-59: несмотря на JOIN
 * в JPQL, Hibernate генерит {@code ... FOR NO KEY UPDATE OF <один алиас>} —
 * лочится ТОЛЬКО activity, а {@code process_instances} остаётся без лока.
 * Попытка расширить лок через {@code lock.scope=EXTENDED} НЕ сработала
 * (живой прогон: SQL байтово тот же — EXTENDED распространяется только на
 * жадно-подгружаемые ассоциации, а второй корень theta-join'а ассоциацией не
 * является). Поэтому сериализация с cancel достигается НЕ этим запросом, а
 * единым порядком захвата instance→activity ({@code lockInstanceFirst}).
 *
 * <p>P-67: ассерты завязаны на форму SQL, а не на факт «запрос выполнился»:
 * удаление JOIN'а из JPQL валит «обе таблицы», а гипотетический переход на
 * native {@code OF a, p} (оба алиаса) валит «ровно один алиас» — проба честно
 * покраснеет, если premise изменится, вместо того чтобы молча врать дальше.
 */
@SpringBootTest(classes = TestMain.class)
@ActiveProfiles({"test", "pgtest"})
@Tag("pg")
class Rel59SqlProbePgIT {

    /**
     * Перехват сырого SQL через штатный Hibernate-механизм
     * {@code hibernate.session_factory.statement_inspector} (без show-sql/логов).
     * Публичный класс с публичным no-arg конструктором — Hibernate создаёт по имени.
     */
    public static class CapturingInspector implements StatementInspector {
        static final List<String> SQL = new CopyOnWriteArrayList<>();

        @Override
        public String inspect(String sql) {
            SQL.add(sql);
            return sql;
        }
    }

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        String host = cfg("PG_HOST", "127.0.0.1");
        String port = cfg("PG_PORT", "5432");
        String db = cfg("PG_DB", "zorrobpm-db");
        String user = cfg("PG_USER", "zorrodev");
        String pass = cfg("PG_PASSWORD", "zorrodev");

        registry.add("spring.datasource.url",
            () -> "jdbc:postgresql://" + host + ":" + port + "/" + db + "?sslmode=disable");
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> pass);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.hikari.connection-timeout", () -> "60000");
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",
            CapturingInspector.class::getName);
    }

    private static String cfg(String key, String dflt) {
        String v = System.getenv(key);
        if (v == null) {
            v = System.getProperty(key);
        }
        return v != null ? v : dflt;
    }

    @Autowired ActivityRepository activityRepository;
    @Autowired ProcessInstanceRepository processInstanceRepository;
    @Autowired ProcessDefinitionRepository processDefinitionRepository;
    @Autowired com.zorrodev.bpm.engine.repository.TokenRepository tokenRepository;
    @Autowired PlatformTransactionManager txManager;

    private UUID activityId;
    private UUID piId;
    private UUID pdId;
    private UUID tokenId;

    @AfterEach
    void cleanup() {
        if (activityId != null) {
            activityRepository.deleteById(activityId);
        }
        if (piId != null) {
            processInstanceRepository.deleteById(piId);
        }
        if (pdId != null) {
            processDefinitionRepository.deleteById(pdId);
        }
        if (tokenId != null) {
            tokenRepository.deleteById(tokenId);
        }
        activityId = null;
        piId = null;
        pdId = null;
        tokenId = null;
    }

    @Test
    void findByIdForUpdate_locksOnlyActivityAlias_joinedInstanceIsNotLocked() {
        pdId = UUID.randomUUID();
        ProcessDefinitionEntity pd = new ProcessDefinitionEntity();
        pd.setId(pdId);
        pd.setKey("rel59probe");
        pd.setVersion(1);
        pd.setCreatedAt(Instant.now());
        pd.setSha256(UUID.randomUUID().toString());
        pd.setName("probe");
        processDefinitionRepository.saveAndFlush(pd);

        piId = UUID.randomUUID();
        ProcessInstanceEntity pi = new ProcessInstanceEntity();
        pi.setId(piId);
        pi.setProcessDefinitionId(pdId);
        pi.setStartedAt(Instant.now());
        pi.setCancelled(false);
        processInstanceRepository.saveAndFlush(pi);

        activityId = UUID.randomUUID();
        tokenId = UUID.randomUUID();
        com.zorrodev.bpm.engine.entity.TokenEntity tok =
            new com.zorrodev.bpm.engine.entity.TokenEntity();
        tok.setId(tokenId);
        tokenRepository.saveAndFlush(tok);
        ActivityEntity a = new ActivityEntity();
        a.setId(activityId);
        a.setProcessInstanceId(piId);
        // activities.token NOT NULL + FK → настоящий tokens-ряд.
        a.setToken(tokenId);
        a.setBpmnElementId("task1");
        a.setStatus(ActivityStatus.CREATED);
        a.setCreatedAt(Instant.now());
        activityRepository.saveAndFlush(a);

        CapturingInspector.SQL.clear();
        new TransactionTemplate(txManager).execute(status -> {
            activityRepository.findByIdForUpdate(activityId);
            return null;
        });

        List<String> locking = CapturingInspector.SQL.stream()
            .filter(s -> s.toLowerCase().contains("for ")
                && s.toLowerCase().contains("update of "))
            .toList();
        System.out.println("REL59-PROBE-SQL: " + locking);
        assertThat(locking)
            .as("findByIdForUpdate обязан выпустить ровно один SELECT ... FOR UPDATE")
            .hasSize(1);
        String sql = locking.get(0).toLowerCase();
        assertThat(sql).contains("process_instances");
        // Premise фикса: OF-список называет РОВНО ОДИН алиас (только activity).
        // Удаление JOIN'а из JPQL валит ассерт выше; native OF с двумя алиасами
        // завалит этот — проба зафиксирует смену premise, а не смолчит.
        assertThat(Pattern.compile("for\\s+(no\\s+key\\s+)?update\\s+of\\s+\\w+\\s*$")
            .matcher(sql.trim()).find())
            .as("FOR UPDATE OF называет ровно один алиас (только activity), SQL был: " + locking.get(0))
            .isTrue();
    }
}
