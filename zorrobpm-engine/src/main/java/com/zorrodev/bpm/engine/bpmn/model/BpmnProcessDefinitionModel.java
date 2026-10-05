package com.zorrodev.bpm.engine.bpmn.model;

import lombok.Getter;
import lombok.Setter;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class BpmnProcessDefinitionModel {
    @Getter
    @Setter
    private String executionPlatformVersion;
    @Getter
    @Setter
    private String key;
    @Getter
    @Setter
    private String name;
    @Getter
    @Setter
    private String startFormKey;
    /**
     * WO-C8-26: linked-form id of the plain start event ({@code zeebe:formDefinition/@formId}).
     * Wins over the legacy {@code startFormKey} property (docs: the Modeler offers one Form
     * type at a time — linked XOR embedded XOR custom — so coexistence is invalid input).
     */
    @Getter
    @Setter
    private String startFormId;
    /** WO-C8-26: resource binding of the start form ({@code latest} default / {@code deployment}). */
    @Getter
    @Setter
    private String startFormBindingType;
    /**
     * WO-C8-26: parsed into the model ONLY (⛔ граница — место тега в .form-ресурсе не
     * выяснено, WO-C8-27; реализация запрещена).
     */
    @Getter
    @Setter
    private String startFormVersionTag;
    @Getter
    @Setter
    private String versionTag;
    /**
     * WO-ENG-17: сырое значение {@code camunda:historyTimeToLive} (nullable).
     * Строка, не Integer: парсинг/валидация — на деплое рядом с остальными
     * 400-контрактами, модель только несёт значение (паттерн versionTag).
     */
    @Getter
    @Setter
    private String historyTimeToLive;
    @Getter
    @Setter
    private List<com.zorrodev.bpm.engine.bpmn.xml.extension.UserTaskFormModel> userTaskForms;
    @Getter
    @Setter
    private String defaultJobPriority;
    private final Map<String, BpmnElementModel> elements = new HashMap<>();
    /** WO-C8-35 раунд 5: memo индекса «хост → его граничные события» (см. getBoundaryEventsAttachedTo). */
    private final Map<String, List<BpmnElementModel>> boundaryEventsByHost = new ConcurrentHashMap<>();
    /** Мемо для {@link #hasRowBackedBoundaryEvent()} (см. комментарий метода). */
    private volatile Boolean rowBackedBoundary;
    @Getter
    @Setter
    private BpmnElementModel startEvent;
    private final Map<String, BpmnFlowModel> flows = new HashMap<>();

    /**
     * WO-ENG-34: executable constructs this engine does NOT implement that the source XML asked for
     * (standard loop, complex gateway, multi-instance on a container, conditional start event,
     * several {@code <process>} in one resource, sequence flow pointing at a node that is not in
     * the model). Empty for a fully supported model.
     *
     * <p>Recorded, never thrown: parsing must keep working for models stored by an earlier release,
     * so that an upgrade cannot make a running process unparseable. The refusal lives in
     * {@code ProcessDefinitionServiceImpl.addProcessDefinition} — the single deploy gate, same
     * place and shape as {@code SERVICE_TASK_MISSING_JOB}.
     */
    @Getter
    @Setter
    private List<UnsupportedBpmnConstruct> unsupportedConstructs = List.of();

    /**
     * Adds an element to the process. The process-level start event is set explicitly by the parser
     * (see {@code BpmnParseService}) — it is NOT inferred here, because flattened nested start events
     * (e.g. inside an embedded subprocess) must not be mistaken for the process start.
     */
    public void addElement(BpmnElementModel element) {
        elements.put(element.getId(), element);
    }

    public BpmnElementModel getElement(String bpmnId) {
        return elements.get(bpmnId);
    }

    public void addFlow(BpmnFlowModel flow) {
        flows.put(flow.getFlowId(), flow);
    }

    public BpmnFlowModel getFlow(String flowId) {
        return flows.get(flowId);
    }

    public List<BpmnElementModel> getElements() {
        return elements.values().stream().toList();
    }

    public List<BpmnFlowModel> getFlows() {
        return flows.values().stream().toList();
    }

    public List<BpmnElementModel> getMessageStartEvents() {
        return elements.values().stream()
            .filter(e -> e.getType() == BpmnElementType.MESSAGE_START_EVENT)
            .filter(e -> e.getEventSubProcessId() == null)
            .toList();
    }

    /** Event sub-processes flattened into this definition (containers triggered by an event, not a flow). */
    public List<BpmnElementModel> getEventSubProcesses() {
        return elements.values().stream()
            .filter(e -> e.getType() == BpmnElementType.EVENT_SUB_PROCESS)
            .toList();
    }

    public List<BpmnElementModel> getTimerStartEvents() {
        return elements.values().stream()
            .filter(e -> e.getType() == BpmnElementType.TIMER_START_EVENT)
            .toList();
    }

    public List<BpmnElementModel> getSignalStartEvents() {
        return elements.values().stream()
            .filter(e -> e.getType() == BpmnElementType.SIGNAL_START_EVENT)
            .filter(e -> e.getEventSubProcessId() == null)
            .toList();
    }

    /**
     * WO-C8-35 раунд 5, эскалация: есть ли в определении граничное событие, у которого ЕСТЬ
     * персистентная armed-запись (timer/message/signal). Только такие границы могут уже сработать,
     * и только их «сработала/не сработала» движок знает из БД; у conditional/error/escalation/
     * cancel-границы такой записи нет НИКОГДА, и они остаются возможными доставщиками, пока жив хост
     * (движок действительно может перевыстрелить условную границу при следующей смене переменных).
     *
     * <p>Ответ кэшируется в модели: правило готовности спрашивает это на каждой проверке, а модель
     * лежит в Caffeine-кэше общей на все потоки.
     */
    public boolean hasRowBackedBoundaryEvent() {
        if (rowBackedBoundary != null) {
            return rowBackedBoundary;
        }
        rowBackedBoundary = elements.values().stream()
            .filter(e -> e.getExtensions() != null && e.getExtensions().getBoundaryEventExtension() != null)
            .map(e -> e.getType())
            .anyMatch(BpmnElementType::isRowBackedBoundaryEvent);
        return rowBackedBoundary;
    }

    /**
     * WO-C8-35 (CR-09, раунд 5 / BLOCKER-3): граничные события, привязанные к элементу
     * {@code hostElementId} ({@code boundaryEventExtension.attachedToRef == host}), ЛЮБОГО типа
     * (timer/message/signal/conditional/error/escalation/compensation/cancel), прерывающие и нет.
     *
     * <p>Индекс строится лениво и кэшируется в самой модели: модель разбирается один раз и дальше
     * живёт в Caffeine-кэше {@code BpmnServiceImpl} общей на все потоки, поэтому здесь нужен
     * потокобезопасный memo, а не пересборка на каждый запрос достижимости (правило готовности
     * inclusive-join зовёт его на каждое живое исполнение).
     *
     * <p>Стартовый триггер event-subprocess сюда НЕ попадает: он привязан к контейнеру как
     * дочерний элемент, а не через {@code attachedToRef} (решение CTO 2, раунд 4) — и не должен
     * попадать: запуск event-subprocess создаёт собственный scope-инстанс и не доставляет ветвь
     * в уже идущий join.
     *
     * @return элементы-границы хоста; пустой список, если границ нет (в т.ч. {@code null} хоста)
     */
    public List<BpmnElementModel> getBoundaryEventsAttachedTo(String hostElementId) {
        if (hostElementId == null) {
            return List.of();
        }
        return boundaryEventsByHost.computeIfAbsent(hostElementId, host -> elements.values().stream()
            .filter(e -> e.getExtensions() != null && e.getExtensions().getBoundaryEventExtension() != null)
            .filter(e -> host.equals(e.getExtensions().getBoundaryEventExtension().getAttachedToRef()))
            .sorted(Comparator.comparing(BpmnElementModel::getId,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .toList());
    }

    /**
     * WO-REL-16: distinct job-worker types this definition dispatches to. Deliberately not filtered
     * by element type — a {@code zeebe:taskDefinition} turns a script task or a send task into a job
     * worker too (see BpmnParseServiceImpl), so the presence of a job name is the criterion, not
     * {@code SERVICE_TASK}. Used to declare the job queues at deployment time instead of lazily on
     * the first message. Insertion-ordered for stable logs/tests.
     */
    public Set<String> getJobTypes() {
        return elements.values().stream()
            .map(BpmnElementModel::getExtensions)
            .filter(Objects::nonNull)
            .map(BpmnElementExtensionModel::getServiceTaskExtension)
            .filter(Objects::nonNull)
            .map(ServiceTaskExtensionModel::getJob)
            .filter(job -> job != null && !job.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

}
