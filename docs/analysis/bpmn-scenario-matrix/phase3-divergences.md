# WO-OPS-28 ФАЗА 3 («контейнеры») — расхождения с живым Zeebe 8.5.0

- **Дата:** 2026-10-21
- **Оракул:** живой Zeebe 8.5.0 (8.5.0), стенд `zbpm-cmp-zeebe`, gRPC 127.0.0.1:36500
- **Сценариев:** 15 · совпали: 11 · расходятся: 0 · утечка у нас: 1 · отказ оракула на деплое: 2 · отказ у нас на деплое: 1

| # | Сценарий | Zeebe 8.5 live | ZorroBPM | Утечки (G-P) | Вердикт |
|---|---|---|---|---|---|
| 1 | `f3-call-activity` | COMPLETED {} | COMPLETED {} | нет | **match** |
| 2 | `f3-call-activity-mi` | COMPLETED {"item":"b","items":["a","b"],"loopCounter":2,"results":[null,"b"]} | **ОТВЕРГ МОДЕЛЬ НА ДЕПЛОЕ** (UNSUPPORTED_MULTI_INSTANCE_CONTAINER) | нет | **ours-deploy-rejected** |
| 3 | `f3-call-activity-vars` | COMPLETED {"childVar":"CV"} | COMPLETED {"childVar":"CV"} | нет | **match** |
| 4 | `f3-event-subprocess-interrupting` | NO_DUMP_JOB {} | NO_DUMP {} | **2 нарушение(й)**: type=activity element=after live activity, status=CREATED; type=task element=after open service_task | **ours-leaks** |
| 5 | `f3-event-subprocess-non-interrupting` | COMPLETED {} | COMPLETED {} | нет | **match** |
| 6 | `f3-mi-subprocess-completion-condition` | COMPLETED {"items":["a","b","c"],"results":["a",null,null]} | COMPLETED {"items":["a","b","c"],"results":["a",null,null]} | нет | **match** |
| 7 | `f3-mi-subprocess-parallel` | COMPLETED {"items":["a","b"],"results":["a","b"]} | COMPLETED {"items":["a","b"],"results":["a","b"]} | нет | **match** |
| 8 | `f3-mi-subprocess-sequential` | COMPLETED {"items":["a","b"],"results":["a","b"]} | COMPLETED {"items":["a","b"],"results":["a","b"]} | нет | **match** |
| 9 | `f3-mi-task-completion-condition` | COMPLETED {"items":["a","b","c"],"results":["a",null,null]} | COMPLETED {"items":["a","b","c"],"results":["a",null,null]} | нет | **match** |
| 10 | `f3-subprocess-nested` | COMPLETED {} | COMPLETED {} | нет | **match** |
| 11 | `f3-subprocess-nested-vars` | COMPLETED {"inner":"IV"} | COMPLETED {"inner":"IV"} | нет | **match** |
| 12 | `f3-subprocess-plain` | COMPLETED {} | COMPLETED {} | нет | **match** |
| 13 | `f3-subprocess-plain-vars` | COMPLETED {"inner":"IV"} | COMPLETED {"inner":"IV"} | нет | **match** |
| 14 | `f3-transaction-cancel` | **ОТВЕРГ МОДЕЛЬ НА ДЕПЛОЕ** | COMPLETED {} | нет | **oracle-deploy-rejected** |
| 15 | `f3-transaction-plain` | **ОТВЕРГ МОДЕЛЬ НА ДЕПЛОЕ** | COMPLETED {} | нет | **oracle-deploy-rejected** |

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
- **Ошибка оракула:** 3 INVALID_ARGUMENT: Command 'CREATE' rejected with code 'INVALID_ARGUMENT': Expected to deploy new resources, but encountered the following errors:
- **Вывод:** Zeebe 8.5 отверг модель на деплое — сравнивать нечего (вывод о паритете приёма модели).

### `f3-transaction-plain` — oracle-deploy-rejected

- **Zeebe 8.5:** статус DEPLOY_REJECTED, переменные {}, остаток []
- **Мы:** статус COMPLETED, переменные {}, нарушения инварианта [], инциденты []
- **Ошибка оракула:** 3 INVALID_ARGUMENT: Command 'CREATE' rejected with code 'INVALID_ARGUMENT': Expected to deploy new resources, but encountered the following errors:
- **Вывод:** Zeebe 8.5 отверг модель на деплое — сравнивать нечего (вывод о паритете приёма модели).

## Отказы оракула на деплое — отдельный вывод (не «ours-differs»)

Zeebe 8.5 отверг модель на деплое — оракул её не исполнил, сравнивать нечего.
Это НЕ «ours-differs», а вывод о ПАРИТЕТЕ ПРИЁМА МОДЕЛИ (мы permissive, оракул — нет).

- `f3-transaction-cancel`: 3 INVALID_ARGUMENT: Command 'CREATE' rejected with code 'INVALID_ARGUMENT': Expected to deploy new resources, but encountered the following errors: / 'f3-transaction-cancel.bpmn': - Element: cad2 /     - ERROR: Event definition of this type is not supported / - Element: tx
- `f3-transaction-plain`: 3 INVALID_ARGUMENT: Command 'CREATE' rejected with code 'INVALID_ARGUMENT': Expected to deploy new resources, but encountered the following errors: / 'f3-transaction-plain.bpmn': - Element: tx /     - ERROR: Elements of type 'Transaction' are currently not supported. Please refer to the documentation for a list of supported elements: https://docs.camunda.io/docs/components/modeler/bpmn/bpmn-coverage/
