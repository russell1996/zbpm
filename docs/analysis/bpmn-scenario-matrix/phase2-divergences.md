# WO-OPS-28 ФАЗА 2 («потоки») — наш движок vs живой Zeebe 8.5.0

- Оракул: живой Zeebe 8.5.0 (gateway 8.5.0), контейнер `zbpm-o28c-zeebe`, прогон `docs/analysis/c8-oracle/scenario-matrix.js`.
- Наш движок: те же схемы и тот же список действий (манифест `zorrobpm-engine/target/scenario-matrix/manifest-flows.json`).
- Сценариев: 16; совпали: 14; расхождений: 0.
- Наблюдение: переменные корневого скоупа в «наблюдательной» задаче + «что осталось активным» (у нас — нарушения инварианта утечек G-P, у оракула — оставшиеся активируемыми job'ы).

| # | Сценарий | Zeebe 8.5 live (переменные / что осталось активным) | ours (переменные / утечки / инциденты) | Вердикт |
|---|---|---|---|---|
| 1 | `f2-event-based-message` | COMPLETED {"bA":"A","keyA":"KA","keyB":"KB","msg":"A"} / leftovers=[] | COMPLETED {"bA":"A","keyA":"KA","keyB":"KB","msg":"A"} / leaks=[] | match |
| 2 | `f2-event-based-message-race` | COMPLETED {"bA":"A","keyA":"KA","keyB":"KB","msg":"A"} / leftovers=[] | COMPLETED {"bA":"A","keyA":"KA","keyB":"KB","msg":"A"} / leaks=[] | match |
| 3 | `f2-exclusive-condition-a` | COMPLETED {"bA":"A","flagA":true,"flagB":false} / leftovers=[] | COMPLETED {"bA":"A","flagA":"true","flagB":"false"} / leaks=[] | match |
| 4 | `f2-exclusive-condition-b` | COMPLETED {"bB":"B","flagA":false,"flagB":true} / leftovers=[] | COMPLETED {"bB":"B","flagA":"false","flagB":"true"} / leaks=[] | match |
| 5 | `f2-exclusive-default` | COMPLETED {"bD":"D","flagA":false,"flagB":false} / leftovers=[] | COMPLETED {"bD":"D","flagA":"false","flagB":"false"} / leaks=[] | match |
| 6 | `f2-exclusive-no-match` | NO_DUMP_JOB {} / leftovers=[] | NOT_COMPLETED {} / leaks=[] / incidents=["IllegalStateException: Exclusive gateway 'gw' could not be evaluated: no outgoing sequence flow condition was true and no default flow is defined"] | match — оба движка не дошли до точки наблюдения — ожидаемо для этого сценария |
| 7 | `f2-exclusive-multi-match-ab` | COMPLETED {"bB":"B","flagA":true,"flagB":true} / leftovers=[] | COMPLETED {"bB":"B","flagA":"true","flagB":"true"} / leaks=[] | match |
| 8 | `f2-exclusive-multi-match-ba` | COMPLETED {"bA":"A","flagA":true,"flagB":true} / leftovers=[] | COMPLETED {"bA":"A","flagA":"true","flagB":"true"} / leaks=[] | match |
| 9 | `f2-inclusive-all` | FAILED {} / leftovers=[] | COMPLETED {"bI1":"I1","bI2":"I2","flagA":"true","flagB":"true"} / leaks=[] | oracle-deploy-rejected — Zeebe 8.5 отверг модель на деплое: 3 INVALID_ARGUMENT: Command 'CREATE' rejected with code 'INVALID_ARGUMENT': Expected to deploy new resources, but encountered the following errors: 'f2-inclusive-all.bpmn': - Element: ijoin     - ERROR: Currently the inclusive gateway can only have one incoming sequence flow  |
| 10 | `f2-inclusive-partial` | FAILED {} / leftovers=[] | COMPLETED {"bI1":"I1","flagA":"true","flagB":"false"} / leaks=[] | oracle-deploy-rejected — Zeebe 8.5 отверг модель на деплое: 3 INVALID_ARGUMENT: Command 'CREATE' rejected with code 'INVALID_ARGUMENT': Expected to deploy new resources, but encountered the following errors: 'f2-inclusive-partial.bpmn': - Element: ijoin     - ERROR: Currently the inclusive gateway can only have one incoming sequence flow  |
| 11 | `f2-inclusive-split-all` | COMPLETED {"bI1":"I1","bI2":"I2","flagA":true,"flagB":true} / leftovers=[] | COMPLETED {"bI1":"I1","bI2":"I2","flagA":"true","flagB":"true"} / leaks=[] | match |
| 12 | `f2-inclusive-split-one` | COMPLETED {"bI1":"I1","flagA":true,"flagB":false} / leftovers=[] | COMPLETED {"bI1":"I1","flagA":"true","flagB":"false"} / leaks=[] | match |
| 13 | `f2-inclusive-default` | COMPLETED {"bD":"D","flagA":false,"flagB":false} / leftovers=[] | COMPLETED {"bD":"D","flagA":"false","flagB":"false"} / leaks=[] | match |
| 14 | `f2-loop-back` | COMPLETED {"again":false,"iter":3} / leftovers=[] | COMPLETED {"again":"false","iter":"3"} / leaks=[] | match |
| 15 | `f2-parallel-fork-join` | COMPLETED {"bP1":"P1","bP2":"P2"} / leftovers=[] | COMPLETED {"bP1":"P1","bP2":"P2"} / leaks=[] | match |
| 16 | `f2-parallel-join-incomplete` | LEFTOVER_JOBS {} / leftovers=[{"type":"branchP2","elementId":"taskP2"}] | NOT_COMPLETED {} / leaks=[] | match — оба движка не дошли до точки наблюдения — ожидаемо для этого сценария |

## Расхождений нет

Ни по одному сценарию среза «потоки» наблюдаемое состояние и терминальный
статус не разошлись с живым Zeebe 8.5.0.

## Отказы оракула на деплое — отдельный вывод (не «ours-differs»)

Эти сценарии НЕ расхождение рантайма: живой Zeebe 8.5.0 отверг модель на деплое,
поэтому сравнивать нечего — оракул не исполнил модель. Наш движок такую модель
ПРИНИМАЕТ и исполняет. Это расхождение по ПАРИТЕТУ ПРИЁМА МОДЕЛИ (мы permissive,
оракул — нет), и оно НЕ чинится в кампании: решение (отвергать такую модель и у нас,
или принять расхождение как осознанное) — за владельцем продукта.

- `f2-inclusive-all` — Zeebe 8.5 отверг модель на деплое: 3 INVALID_ARGUMENT: Command 'CREATE' rejected with code 'INVALID_ARGUMENT': Expected to deploy new resources, but encountered the following errors: 'f2-inclusive-all.bpmn': - Element: ijoin     - ERROR: Currently the inclusive gateway can only have one incoming sequence flow 
  - ours: COMPLETED {"bI1":"I1","bI2":"I2","flagA":"true","flagB":"true"} (модель принята и исполнена движком)
- `f2-inclusive-partial` — Zeebe 8.5 отверг модель на деплое: 3 INVALID_ARGUMENT: Command 'CREATE' rejected with code 'INVALID_ARGUMENT': Expected to deploy new resources, but encountered the following errors: 'f2-inclusive-partial.bpmn': - Element: ijoin     - ERROR: Currently the inclusive gateway can only have one incoming sequence flow 
  - ours: COMPLETED {"bI1":"I1","flagA":"true","flagB":"false"} (модель принята и исполнена движком)

**Вывод (к решению владельца / кандидат в баг-WO):** либо мы отвергаем модель с
джойном inclusive на деплое, как Zeebe 8.5, либо расхождение принимается осознанно.
В рамках кампании (WO-OPS-28) правки в деплой-валидации НЕ делаются — баги чинятся
отдельными задачами; здесь зафиксирован факт и предложение.

## Как воспроизвести

```bash
# 0) свой оракул Zeebe 8.5.0 (чужие контейнеры не трогаем)
docker run -d --name zbpm-o28c-zeebe --restart unless-stopped \
  -p 127.0.0.1:40600:9600 -p 127.0.0.1:37500:26500 \
  -e ZEEBE_BROKER_CLUSTER_PARTITIONSCOUNT=1 \
  -e ZEEBE_BROKER_GATEWAY_NETWORK_HOST=0.0.0.0 -e ZEEBE_BROKER_GATEWAY_NETWORK_PORT=26500 \
  camunda/zeebe:8.5.0
# 1) наш движок (генерирует манифест и наши наблюдения)
mvn -pl zorrobpm-engine verify -Dit.test=FlowScenarioMatrixIntegrationTests
# 2) живой Zeebe 8.5.0 (пути — от каталога docs/analysis/c8-oracle)
cd docs/analysis/c8-oracle && C8_ORACLE_GW=localhost:37500 node scenario-matrix.js \
  --manifest ../../../zorrobpm-engine/target/scenario-matrix/manifest-flows.json \
  --out ../../../zorrobpm-engine/target/scenario-matrix/zeebe-flows.json
# 3) сравнение + пересъёмка прибитых фактов
cd docs/analysis/c8-oracle && node scenario-matrix-flow-diff.js
```

Оракул — СВОЙ контейнер Zeebe 8.5.0; чужие контейнеры (zbpm-cmp-zeebe, raxon-*,
zbpm-pgci-*) не трогаются. Имя своего контейнера — в §«Оракул» выше.
