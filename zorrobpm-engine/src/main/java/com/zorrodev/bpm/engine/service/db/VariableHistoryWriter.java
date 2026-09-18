package com.zorrodev.bpm.engine.service.db;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.contract.model.ProcessVariableType;
import com.zorrodev.bpm.engine.entity.VariableHistoryEntity;
import com.zorrodev.bpm.engine.repository.VariableHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * WO-ENG-16 (WB-003): узкий бин записи истории переменных.
 *
 * <p>Почему отдельный бин, а не метод в {@code VariableDbOperationsImpl}:
 * история — другой домен (audit-след), чем текущие значения; второй сервис
 * с тем же назначением был бы god-дрейфом (прецедент
 * {@code TaskFormDataService} из WO-DEBT-7). Вызывается ровно из двух мест —
 * {@code VariableDbOperationsImpl} (каждое изменение) и
 * {@code ProcessInstanceDbOperationsImpl} (стартовые переменные, источник
 * {@code INIT}) — INSERT не размазан по вызывающим (P-24).
 *
 * <p>Запись — синхронный JPA-{@code save} в ТЕКУЩЕЙ транзакции вызывающего
 * (та же причина, что UPSERT_PG в WO-REL-31 CR-1): история обязана коммититься
 * атомарно со значением. Асинхронность (outbox-паттерн) здесь отвергнута
 * сознательно: оторванная от транзакции запись теряет порядок и переживает
 * откат значения — для audit-следа это неверные данные, а не задержка.
 * Накладные расходы — один INSERT на переменную (замер — критерий 2 WO).
 */
@Service
@RequiredArgsConstructor
public class VariableHistoryWriter {

    /** Стартовые переменные инстанса (пишет createProcessInstance). */
    public static final String SOURCE_INIT = "INIT";
    /** Первая запись переменной (upsert сообщил create). */
    public static final String SOURCE_CREATE = "CREATE";
    /** Перезапись существующей переменной (upsert сообщил update). */
    public static final String SOURCE_UPDATE = "UPDATE";
    /** Добавление JSON-элемента (appendJsonElement). */
    public static final String SOURCE_APPEND = "APPEND";

    private final VariableHistoryRepository historyRepository;

    /**
     * Фиксирует одно изменение переменной. Никогда не бросает наружу:
     * история — audit-след, её сбой не должен ронять запись самого значения.
     */
    public void record(@NonNull UUID processInstanceId, UUID scopeId,
            @NonNull ProcessVariable variable, @NonNull String source) {
        try {
            VariableHistoryEntity e = new VariableHistoryEntity();
            e.setId(UUID.randomUUID());
            e.setProcessInstanceId(processInstanceId);
            e.setName(variable.getName());
            e.setTextValue(variable.getValue() != null ? variable.getValue() : "");
            e.setType(variable.getType() != null ? variable.getType() : ProcessVariableType.STRING);
            e.setScopeId(scopeId);
            e.setSource(source);
            e.setChangedAt(Instant.now());
            historyRepository.save(e);
        } catch (RuntimeException ex) {
            // Audit-след не входит в контракт записи: тот же принцип, что
            // evictVariable в VariableDbOperationsImpl (шейпинг видимости,
            // не часть write-контракта).
            org.slf4j.LoggerFactory.getLogger(VariableHistoryWriter.class)
                .warn("Failed to record history for variable {} of process {}",
                    variable.getName(), processInstanceId, ex);
        }
    }
}
