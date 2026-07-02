# WO-SEC-7 — Убрать PII из логов FEEL     [P2 / MEDIUM]

## Цель
Устранить утечку значений процессных переменных в логи при FEEL-вычислениях.

## Проблема
```
zorrobpm-engine/.../service/impl/ScriptServiceImpl.java:65
```
```java
log.info("Variable {} = {}", variable.getName(), variable.getValue());
```
Все переменные (включая PII: ФИО, паспорт, сумма) выводятся на INFO при каждом FEEL-вычислении.

## Scope
**Можно трогать:** только `ScriptServiceImpl.java`.  
**Нельзя:** менять интерфейс `ScriptService`; трогать другие сервисы.

## Критерии приёмки

| # | Критерий | Команда проверки | Ожидаемый факт |
|---|---|---|---|
| 1 | Значения переменных не в INFO-логах | запустить процесс с FEEL → grep логов | `grep "Variable.*="` → пусто на INFO |
| 2 | Отладочная информация на DEBUG | включить DEBUG → grep | переменные видны на DEBUG |
| 3 | FEEL-вычисления продолжают работать | existing FEEL IT | зелёные |
| 4 | Регресс | `mvn clean verify` | BUILD SUCCESS |

## DoD / отчёт
Таблица критерий → команда → факт.
