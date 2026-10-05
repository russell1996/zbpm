package com.zorrodev.bpm.exchange;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.UUID;

/**
 * Тело задания, которое движок отдаёт воркеру.
 *
 * <p><b>WO-C8-36 (H-1): ignoreUnknown обязателен, а не украшение.</b> Воркер
 * читает это тело своим десериализатором; добавление ЛЮБОГО нового поля в
 * движке иначе роняет чтение на всех воркерах, которые ещё не обновлены
 * (штатный порядок апгрейда — движок раньше воркеров). Ошибка чтения приводит
 * к тихому ACK'у без результата, то есть к безвозвратной потере задания.
 * Обратная совместимость на этот класс — не «лучшая практика», а условие, при
 * котором rolling upgrade вообще возможен.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobDetailModel {
    private UUID serviceTaskId;
    private UUID processInstanceId;
    private UUID processDefinitionId;
    private String serviceTaskKey;
    private String job;
    private Map<String, ProcessVariable> variables;
    /** Custom headers from {@code zeebe:taskHeaders} (WO-C8-7) — null when the task declares none. */
    private Map<String, String> taskHeaders;
    /** Job priority from {@code zeebe:jobPriorityDefinition} (WO-C8-9, corrected WO-C8-13/A-1 — activation order hint) — null when absent or unresolvable. */
    private Integer priority;
    /**
     * WO-C8-36 (CR-01): фаза отправки этого вызова (словарь
     * {@link ServiceTaskDispatchPhase}; nullable — null у старых продюсеров,
     * движок трактует как legacy без проверки). Воркер обязан вернуть В
     * сообщении о завершении без изменений.
     *
     * <p><b>WO-C8-36 (H-1, п.д): {@code NON_NULL} обязателен на обоих phased-полях.</b>
     * Просто «не проставлять сеттер» НЕ делает тело прежним: Jackson по умолчанию
     * печатает {@code "dispatchPhase":null}, то есть ключ в теле появляется и старый
     * строгий воркер на нём падает. Без аннотации тело меняется при любом флаге —
     * это и поймал golden-тест (RED: {@code "dispatchPhase":null} в payload).
     * Аннотация на ПОЛЕ, а не на классе: у остальных полей null в теле был и до
     * этого WO, и классовый NON_NULL тихо переписал бы и их.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String dispatchPhase;
    /**
     * WO-C8-36 (CR-01): индекс внутри фазы (позиция слушателя; null для
     * {@code real} и для legacy). Пара (dispatchPhase, dispatchIndex) —
     * идентификатор конкретного вызова. {@code NON_NULL} — см. {@link #dispatchPhase}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer dispatchIndex;
}
