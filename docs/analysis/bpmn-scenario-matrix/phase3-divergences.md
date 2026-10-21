# WO-OPS-28 ФАЗА 3 («контейнеры») — расхождения с живым Zeebe 8.5.0

- **Дата:** 2026-10-21
- **Оракул:** живой Zeebe 8.5.0 (8.5.0), стенд `zbpm-cmp-zeebe`, gRPC 127.0.0.1:36500
- **Сценариев:** 15 · совпали: 11 · расходятся: 0 · утечка у нас: 1 · отказ оракула на деплое: 2 · отказ у нас на деплое: 1

| # | Сценарий | Zeebe 8.5 live | ZorroBPM | Утечки (G-P) | Шаги (наши / Zeebe) | Вердикт |
|---|---|---|---|---|---|---|
| 1 | `f3-call-activity` | COMPLETED {} | COMPLETED {} | нет | ["childWork"] / ["childWork"] | **match** |
| 2 | `f3-call-activity-mi` | COMPLETED {"item":"b","items":["a","b"],"loopCounter":2,"results":[null,"b"]} | **ОТВЕРГ МОДЕЛЬ НА ДЕПЛОЕ** (UNSUPPORTED_MULTI_INSTANCE_CONTAINER) | нет | [] / ["childWork"] | **ours-deploy-rejected** |
| 3 | `f3-call-activity-vars` | COMPLETED {"childVar":"CV"} | COMPLETED {"childVar":"CV"} | нет | ["childWork"] / ["childWork"] | **match** |
| 4 | `f3-event-subprocess-interrupting` | NO_DUMP_JOB {} | NO_DUMP {} | **2 нарушение(й)**: type=activity element=after live activity, status=CREATED; type=task element=after open service_task | ["work","evWork"] / ["work","evWork"] | **ours-leaks** |
| 5 | `f3-event-subprocess-non-interrupting` | COMPLETED {} | COMPLETED {} | нет | ["work","evWork","after"] / ["work","evWork","after"] | **match** |
| 6 | `f3-mi-subprocess-completion-condition` | COMPLETED {"items":["a","b","c"],"results":["a",null,null]} | COMPLETED {"items":["a","b","c"],"results":["a",null,null]} | нет | ["work"] / ["work"] | **match** |
| 7 | `f3-mi-subprocess-parallel` | COMPLETED {"items":["a","b"],"results":["a","b"]} | COMPLETED {"items":["a","b"],"results":["a","b"]} | нет | ["work"] / ["work"] | **match** |
| 8 | `f3-mi-subprocess-sequential` | COMPLETED {"items":["a","b"],"results":["a","b"]} | COMPLETED {"items":["a","b"],"results":["a","b"]} | нет | ["work"] / ["work"] | **match** |
| 9 | `f3-mi-task-completion-condition` | COMPLETED {"items":["a","b","c"],"results":["a",null,null]} | COMPLETED {"items":["a","b","c"],"results":["a",null,null]} | нет | ["miTask"] / ["miTask"] | **match** |
| 10 | `f3-subprocess-nested` | COMPLETED {} | COMPLETED {} | нет | ["work"] / ["work"] | **match** |
| 11 | `f3-subprocess-nested-vars` | COMPLETED {"inner":"IV"} | COMPLETED {"inner":"IV"} | нет | ["work"] / ["work"] | **match** |
| 12 | `f3-subprocess-plain` | COMPLETED {} | COMPLETED {} | нет | ["work"] / ["work"] | **match** |
| 13 | `f3-subprocess-plain-vars` | COMPLETED {"inner":"IV"} | COMPLETED {"inner":"IV"} | нет | ["work"] / ["work"] | **match** |
| 14 | `f3-transaction-cancel` | **ОТВЕРГ МОДЕЛЬ НА ДЕПЛОЕ** | COMPLETED {} | нет | ["txWork","afterCancel"] / [] | **oracle-deploy-rejected** |
| 15 | `f3-transaction-plain` | **ОТВЕРГ МОДЕЛЬ НА ДЕПЛОЕ** | COMPLETED {} | нет | ["txWork"] / [] | **oracle-deploy-rejected** |

## Подробности расхождений

### `f3-call-activity-mi` — ours-deploy-rejected

- **Zeebe 8.5:** статус COMPLETED, переменные {"item":"b","items":["a","b"],"results":[null,"b"],"loopCounter":2}, остаток []
- **Мы:** статус DEPLOY_REJECTED, переменные {}, нарушения инварианта [], инциденты []
- **Ошибка у нас:** UNSUPPORTED_MULTI_INSTANCE_CONTAINER: Multi-instance is supported on serviceTask, userTask and embedded subProcess: call carries multiInstanceLoopCharacteristics on a container and would run once. Move the multi-instance marker onto a task inside the container, or drop it.
- **Вывод:** мы отвергли модель на деплое, а Zeebe 8.5 её исполнил (вывод о паритете приёма модели).

### `f3-event-subprocess-interrupting` — ours-leaks

- **Zeebe 8.5:** статус NO_DUMP_JOB, переменные {}, остаток []
- **Мы:** статус NO_DUMP, переменные {}, нарушения инварианта ["type=activity element=after live activity, status=CREATED","type=task element=after open service_task"], инциденты []
- **Ошибка оракула:** dump job never appeared
- **Вывод:** у нас остаток в завершённом инстансе: ["type=activity element=after live activity, status=CREATED","type=task element=after open service_task"]; у Zeebe остатка нет; LEAK-INVARIANT (G-P): ["type=activity element=after live activity, status=CREATED","type=task element=after open service_task"]

### `f3-transaction-cancel` — oracle-deploy-rejected

- **Zeebe 8.5:** статус DEPLOY_REJECTED, переменные {}, остаток []
- **Мы:** статус COMPLETED, переменные {}, нарушения инварианта [], инциденты []
- **Ошибка оракула:** 3 INVALID_ARGUMENT: Command 'CREATE' rejected with code 'INVALID_ARGUMENT': Expected to deploy new resources, but encountered the following errors: | отвергнутые элементы: cad1, cad2, cb, tx, txEnd | причины: Boundary events must be one of: timer, message, error, signal, escalation / Elements of type 'Transaction' are currently not supported. Please refer to the documentation for a list of supported elements: https://docs.camunda.io/docs/components/modeler/bpmn/bpmn-coverage/ / End events must be one of: none, error, message, terminate, signal, escalation or compensation / Event definition of this type is not supported
- **Вывод:** Zeebe 8.5 отверг модель на деплое — сравнивать нечего (вывод о паритете приёма модели).

### `f3-transaction-plain` — oracle-deploy-rejected

- **Zeebe 8.5:** статус DEPLOY_REJECTED, переменные {}, остаток []
- **Мы:** статус COMPLETED, переменные {}, нарушения инварианта [], инциденты []
- **Ошибка оракула:** 3 INVALID_ARGUMENT: Command 'CREATE' rejected with code 'INVALID_ARGUMENT': Expected to deploy new resources, but encountered the following errors: | отвергнутые элементы: tx | причины: Elements of type 'Transaction' are currently not supported. Please refer to the documentation for a list of supported elements: https://docs.camunda.io/docs/components/modeler/bpmn/bpmn-coverage/
- **Вывод:** Zeebe 8.5 отверг модель на деплое — сравнивать нечего (вывод о паритете приёма модели).

## Отказы оракула на деплое — отдельный вывод (не «ours-differs»)

Zeebe 8.5 отверг модель на деплое — оракул её не исполнил, сравнивать нечего.
Это НЕ «ours-differs», а вывод о ПАРИТЕТЕ ПРИЁМА МОДЕЛИ (мы permissive, оракул — нет).

- `f3-transaction-cancel`: 3 INVALID_ARGUMENT: Command 'CREATE' rejected with code 'INVALID_ARGUMENT': Expected to deploy new resources, but encountered the following errors: | отвергнутые элементы: cad1, cad2, cb, tx, txEnd | причины: Boundary events must be one of: timer, message, error, signal, escalation / Elements of type 'Transaction' are currently not supported. Please refer to the documentation for a list of supported elements: https://docs.camunda.io/docs/components/modeler/bpmn/bpmn-coverage/ / End events must be one of: none, error, message, terminate, signal, escalation or compensation / Event definition of this type is not supported
- `f3-transaction-plain`: 3 INVALID_ARGUMENT: Command 'CREATE' rejected with code 'INVALID_ARGUMENT': Expected to deploy new resources, but encountered the following errors: | отвергнутые элементы: tx | причины: Elements of type 'Transaction' are currently not supported. Please refer to the documentation for a list of supported elements: https://docs.camunda.io/docs/components/modeler/bpmn/bpmn-coverage/
