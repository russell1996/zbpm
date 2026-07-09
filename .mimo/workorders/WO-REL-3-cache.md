# WO-REL-3 — BpmnServiceImpl cache eviction     [P1 / MEDIUM]

## Цель
Заменить unbounded `ConcurrentHashMap` кеш BPMN-моделей на кеш с ограничением размера и TTL.

## Проблема
```
zorrobpm-engine/.../service/impl/BpmnServiceImpl.java:20
```
```java
private final Map<UUID, BpmnProcessDefinitionModel> models = new ConcurrentHashMap<>();
```
При многих версиях процессов кеш растёт без ограничений → OOM на long-running сервере.

## Scope
**Можно трогать:** `BpmnServiceImpl.java`, `BPMConfiguration.java` (добавить Caffeine bean),
`zorrobpm-engine/pom.xml` (добавить `caffeine` dependency если нет).  
**Нельзя:** менять интерфейс `BpmnService`; трогать callers.

## Критерии приёмки

| # | Критерий | Команда проверки | Ожидаемый факт |
|---|---|---|---|
| 1 | Кеш не превышает max-size | IT: задеплоить N+1 процессов (N = max-size) | oldest entry evicted |
| 2 | Evicted entry перезагружается из БД | IT: после eviction → запрос модели | модель правильно загружается |
| 3 | `computeIfAbsent` атомарность сохранена | concurrent IT | не более 1 parse на один id |
| 4 | Размер кеша конфигурируемый | `zorrobpm.engine.bpmn-cache-max-size=500` в конфиге | работает |
| 5 | Регресс | `mvn clean verify` | BUILD SUCCESS |

## Запреты
Не менять интерфейс. Не убирать thread-safety.

## DoD / отчёт
Таблица критерий → команда → факт.
