# WO-OPS-27 — матрица сценариев: наш движок vs Zeebe 8.5.0 (живой оракул)

- Оракул: живой Zeebe 8.5.0 (gateway 8.5.0), контейнер, поднятый прогоном `scenario-matrix.js` (gateway из C8_ORACLE_GW).
- Наш движок: тот же набор схем (манифест `zorrobpm-engine/target/scenario-matrix/manifest.json`).
- Сценариев: 56; совпали: 56; оракул не исполнил (docs≠8.5): 0; расхождений: 0.

| # | Сценарий | Zeebe 8.5 live (переменные / что осталось активным) | ours (переменные / что осталось активным) | Вердикт |
|---|---|---|---|---|
| 1 | `mi_parallel_task-single-n0-` | COMPLETED {"items":[],"results":[]} / leftovers=[] | COMPLETED {"items":[],"results":[]} / leaks=[] | match |
| 2 | `mi_parallel_task-single-n1-a` | COMPLETED {"items":["a"],"results":["a"]} / leftovers=[] | COMPLETED {"items":["a"],"results":["a"]} / leaks=[] | match |
| 3 | `mi_parallel_task-single-n2-ab` | COMPLETED {"items":["a","b"],"results":["a","b"]} / leftovers=[] | COMPLETED {"items":["a","b"],"results":["a","b"]} / leaks=[] | match |
| 4 | `mi_parallel_task-single-n2-ba` | COMPLETED {"items":["a","b"],"results":["a","b"]} / leftovers=[] | COMPLETED {"items":["a","b"],"results":["a","b"]} / leaks=[] | match |
| 5 | `mi_parallel_task-single-n3-abc` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 6 | `mi_parallel_task-single-n3-acb` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 7 | `mi_parallel_task-single-n3-bac` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 8 | `mi_parallel_task-single-n3-bca` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 9 | `mi_parallel_task-single-n3-cba` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 10 | `mi_parallel_task-single-n3-cab` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 11 | `mi_sequential_task-single-n0-` | COMPLETED {"items":[],"results":[]} / leftovers=[] | COMPLETED {"items":[],"results":[]} / leaks=[] | match |
| 12 | `mi_sequential_task-single-n1-a` | COMPLETED {"items":["a"],"results":["a"]} / leftovers=[] | COMPLETED {"items":["a"],"results":["a"]} / leaks=[] | match |
| 13 | `mi_sequential_task-single-n2-ab` | COMPLETED {"items":["a","b"],"results":["a","b"]} / leftovers=[] | COMPLETED {"items":["a","b"],"results":["a","b"]} / leaks=[] | match |
| 14 | `mi_sequential_task-single-n3-abc` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 15 | `mi_parallel_subprocess-single-n0-` | COMPLETED {"items":[],"results":[]} / leftovers=[] | COMPLETED {"items":[],"results":[]} / leaks=[] | match |
| 16 | `mi_parallel_subprocess-single-n1-a` | COMPLETED {"items":["a"],"results":["a"]} / leftovers=[] | COMPLETED {"items":["a"],"results":["a"]} / leaks=[] | match |
| 17 | `mi_parallel_subprocess-single-n2-ab` | COMPLETED {"items":["a","b"],"results":["a","b"]} / leftovers=[] | COMPLETED {"items":["a","b"],"results":["a","b"]} / leaks=[] | match |
| 18 | `mi_parallel_subprocess-single-n2-ba` | COMPLETED {"items":["a","b"],"results":["a","b"]} / leftovers=[] | COMPLETED {"items":["a","b"],"results":["a","b"]} / leaks=[] | match |
| 19 | `mi_parallel_subprocess-single-n3-abc` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 20 | `mi_parallel_subprocess-single-n3-acb` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 21 | `mi_parallel_subprocess-single-n3-bac` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 22 | `mi_parallel_subprocess-single-n3-bca` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 23 | `mi_parallel_subprocess-single-n3-cba` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 24 | `mi_parallel_subprocess-single-n3-cab` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 25 | `mi_parallel_subprocess-chain-n0-` | COMPLETED {"items":[],"results":[]} / leftovers=[] | COMPLETED {"items":[],"results":[]} / leaks=[] | match |
| 26 | `mi_parallel_subprocess-chain-n1-a` | COMPLETED {"items":["a"],"results":["a"]} / leftovers=[] | COMPLETED {"items":["a"],"results":["a"]} / leaks=[] | match |
| 27 | `mi_parallel_subprocess-chain-n2-ab` | COMPLETED {"items":["a","b"],"results":["a","b"]} / leftovers=[] | COMPLETED {"items":["a","b"],"results":["a","b"]} / leaks=[] | match |
| 28 | `mi_parallel_subprocess-chain-n2-ba` | COMPLETED {"items":["a","b"],"results":["a","b"]} / leftovers=[] | COMPLETED {"items":["a","b"],"results":["a","b"]} / leaks=[] | match |
| 29 | `mi_parallel_subprocess-chain-n3-abc` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 30 | `mi_parallel_subprocess-chain-n3-acb` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 31 | `mi_parallel_subprocess-chain-n3-bac` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 32 | `mi_parallel_subprocess-chain-n3-bca` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 33 | `mi_parallel_subprocess-chain-n3-cba` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 34 | `mi_parallel_subprocess-chain-n3-cab` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 35 | `mi_parallel_subprocess-user-n0-` | COMPLETED {"items":[],"results":[]} / leftovers=[] | COMPLETED {"items":[],"results":[]} / leaks=[] | match |
| 36 | `mi_parallel_subprocess-user-n1-a` | COMPLETED {"items":["a"],"results":["a"]} / leftovers=[] | COMPLETED {"items":["a"],"results":["a"]} / leaks=[] | match |
| 37 | `mi_parallel_subprocess-user-n2-ab` | COMPLETED {"items":["a","b"],"results":["a","b"]} / leftovers=[] | COMPLETED {"items":["a","b"],"results":["a","b"]} / leaks=[] | match |
| 38 | `mi_parallel_subprocess-user-n2-ba` | COMPLETED {"items":["a","b"],"results":["a","b"]} / leftovers=[] | COMPLETED {"items":["a","b"],"results":["a","b"]} / leaks=[] | match |
| 39 | `mi_parallel_subprocess-user-n3-abc` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 40 | `mi_parallel_subprocess-user-n3-acb` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 41 | `mi_parallel_subprocess-user-n3-bac` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 42 | `mi_parallel_subprocess-user-n3-bca` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 43 | `mi_parallel_subprocess-user-n3-cba` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 44 | `mi_parallel_subprocess-user-n3-cab` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 45 | `mi_sequential_subprocess-single-n0-` | COMPLETED {"items":[],"results":[]} / leftovers=[] | COMPLETED {"items":[],"results":[]} / leaks=[] | match |
| 46 | `mi_sequential_subprocess-single-n1-a` | COMPLETED {"items":["a"],"results":["a"]} / leftovers=[] | COMPLETED {"items":["a"],"results":["a"]} / leaks=[] | match |
| 47 | `mi_sequential_subprocess-single-n2-ab` | COMPLETED {"items":["a","b"],"results":["a","b"]} / leftovers=[] | COMPLETED {"items":["a","b"],"results":["a","b"]} / leaks=[] | match |
| 48 | `mi_sequential_subprocess-single-n3-abc` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 49 | `mi_sequential_subprocess-chain-n0-` | COMPLETED {"items":[],"results":[]} / leftovers=[] | COMPLETED {"items":[],"results":[]} / leaks=[] | match |
| 50 | `mi_sequential_subprocess-chain-n1-a` | COMPLETED {"items":["a"],"results":["a"]} / leftovers=[] | COMPLETED {"items":["a"],"results":["a"]} / leaks=[] | match |
| 51 | `mi_sequential_subprocess-chain-n2-ab` | COMPLETED {"items":["a","b"],"results":["a","b"]} / leftovers=[] | COMPLETED {"items":["a","b"],"results":["a","b"]} / leaks=[] | match |
| 52 | `mi_sequential_subprocess-chain-n3-abc` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |
| 53 | `mi_sequential_subprocess-user-n0-` | COMPLETED {"items":[],"results":[]} / leftovers=[] | COMPLETED {"items":[],"results":[]} / leaks=[] | match |
| 54 | `mi_sequential_subprocess-user-n1-a` | COMPLETED {"items":["a"],"results":["a"]} / leftovers=[] | COMPLETED {"items":["a"],"results":["a"]} / leaks=[] | match |
| 55 | `mi_sequential_subprocess-user-n2-ab` | COMPLETED {"items":["a","b"],"results":["a","b"]} / leftovers=[] | COMPLETED {"items":["a","b"],"results":["a","b"]} / leaks=[] | match |
| 56 | `mi_sequential_subprocess-user-n3-abc` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leftovers=[] | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} / leaks=[] | match |

## Расхождений нет

Ни по одному сценарию среза наблюдаемое состояние (переменные корневого скоупа)
и терминальный статус не разошлись с живым Zeebe 8.5.0.

## Как воспроизвести

```bash
# 1) наш движок (генерирует манифест и наши наблюдения)
mvn -pl zorrobpm-engine verify -Dit.test=ScenarioMatrixIntegrationTests
# 2) живой Zeebe 8.5.0
cd docs/analysis/c8-oracle && node scenario-matrix.js
# 3) сравнение + прибитые факты оракула
cd docs/analysis/c8-oracle && node scenario-matrix-diff.js
```
