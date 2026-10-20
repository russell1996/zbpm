# WO-OPS-27 — матрица сценариев: наш движок vs Zeebe 8.5.0 (живой оракул)

- Оракул: живой Zeebe 8.5.0 (gateway 8.5.0), стенд `zbpm-cmp-zeebe`.
- Наш движок: тот же набор схем (манифест `zorrobpm-engine/target/scenario-matrix/manifest.json`).
- Сценариев: 56; совпали: 44; оракул не исполнил (docs≠8.5): 12; расхождений: 0.

| # | Сценарий | Zeebe 8.5 live | ours | Вердикт |
|---|---|---|---|---|
| 1 | `mi_parallel_task-single-n0-` | COMPLETED {"items":[],"results":[]} | COMPLETED {"items":[],"results":[]} | match |
| 2 | `mi_parallel_task-single-n1-a` | COMPLETED {"items":["a"],"results":["a"]} | COMPLETED {"items":["a"],"results":["a"]} | match |
| 3 | `mi_parallel_task-single-n2-ab` | COMPLETED {"items":["a","b"],"results":["a","b"]} | COMPLETED {"items":["a","b"],"results":["a","b"]} | match |
| 4 | `mi_parallel_task-single-n2-ba` | COMPLETED {"items":["a","b"],"results":["a","b"]} | COMPLETED {"items":["a","b"],"results":["a","b"]} | match |
| 5 | `mi_parallel_task-single-n3-abc` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 6 | `mi_parallel_task-single-n3-acb` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 7 | `mi_parallel_task-single-n3-bac` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 8 | `mi_parallel_task-single-n3-bca` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 9 | `mi_parallel_task-single-n3-cba` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 10 | `mi_parallel_task-single-n3-cab` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 11 | `mi_sequential_task-single-n0-` | COMPLETED {"items":[],"results":[]} | COMPLETED {"items":[],"results":[]} | match |
| 12 | `mi_sequential_task-single-n1-a` | COMPLETED {"items":["a"],"results":["a"]} | COMPLETED {"items":["a"],"results":["a"]} | match |
| 13 | `mi_sequential_task-single-n2-ab` | COMPLETED {"items":["a","b"],"results":["a","b"]} | COMPLETED {"items":["a","b"],"results":["a","b"]} | match |
| 14 | `mi_sequential_task-single-n3-abc` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 15 | `mi_parallel_subprocess-single-n0-` | COMPLETED {"items":[],"results":[]} | COMPLETED {"items":[],"results":[]} | match |
| 16 | `mi_parallel_subprocess-single-n1-a` | COMPLETED {"items":["a"],"results":["a"]} | COMPLETED {"items":["a"],"results":["a"]} | match |
| 17 | `mi_parallel_subprocess-single-n2-ab` | COMPLETED {"items":["a","b"],"results":["a","b"]} | COMPLETED {"items":["a","b"],"results":["a","b"]} | match |
| 18 | `mi_parallel_subprocess-single-n2-ba` | COMPLETED {"items":["a","b"],"results":["a","b"]} | COMPLETED {"items":["a","b"],"results":["a","b"]} | match |
| 19 | `mi_parallel_subprocess-single-n3-abc` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 20 | `mi_parallel_subprocess-single-n3-acb` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 21 | `mi_parallel_subprocess-single-n3-bac` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 22 | `mi_parallel_subprocess-single-n3-bca` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 23 | `mi_parallel_subprocess-single-n3-cba` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 24 | `mi_parallel_subprocess-single-n3-cab` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 25 | `mi_parallel_subprocess-chain-n0-` | COMPLETED {"items":[],"results":[]} | COMPLETED {"items":[],"results":[]} | match |
| 26 | `mi_parallel_subprocess-chain-n1-a` | COMPLETED {"items":["a"],"results":["a"]} | COMPLETED {"items":["a"],"results":["a"]} | match |
| 27 | `mi_parallel_subprocess-chain-n2-ab` | COMPLETED {"items":["a","b"],"results":["a","b"]} | COMPLETED {"items":["a","b"],"results":["a","b"]} | match |
| 28 | `mi_parallel_subprocess-chain-n2-ba` | COMPLETED {"items":["a","b"],"results":["a","b"]} | COMPLETED {"items":["a","b"],"results":["a","b"]} | match |
| 29 | `mi_parallel_subprocess-chain-n3-abc` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 30 | `mi_parallel_subprocess-chain-n3-acb` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 31 | `mi_parallel_subprocess-chain-n3-bac` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 32 | `mi_parallel_subprocess-chain-n3-bca` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 33 | `mi_parallel_subprocess-chain-n3-cba` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 34 | `mi_parallel_subprocess-chain-n3-cab` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 35 | `mi_parallel_subprocess-user-n0-` | COMPLETED {"items":[],"results":[]} | COMPLETED {"items":[],"results":[]} | match |
| 36 | `mi_parallel_subprocess-user-n1-a` | NO_DUMP_JOB {} | COMPLETED {"items":["a"],"results":["a"]} | oracle-unsupported — Zeebe 8.5.0 не исполнил сценарий (NO_DUMP_JOB: dump job never appeared) |
| 37 | `mi_parallel_subprocess-user-n2-ab` | NO_DUMP_JOB {} | COMPLETED {"items":["a","b"],"results":["a","b"]} | oracle-unsupported — Zeebe 8.5.0 не исполнил сценарий (NO_DUMP_JOB: dump job never appeared) |
| 38 | `mi_parallel_subprocess-user-n2-ba` | NO_DUMP_JOB {} | COMPLETED {"items":["a","b"],"results":["a","b"]} | oracle-unsupported — Zeebe 8.5.0 не исполнил сценарий (NO_DUMP_JOB: dump job never appeared) |
| 39 | `mi_parallel_subprocess-user-n3-abc` | NO_DUMP_JOB {} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | oracle-unsupported — Zeebe 8.5.0 не исполнил сценарий (NO_DUMP_JOB: dump job never appeared) |
| 40 | `mi_parallel_subprocess-user-n3-acb` | NO_DUMP_JOB {} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | oracle-unsupported — Zeebe 8.5.0 не исполнил сценарий (NO_DUMP_JOB: dump job never appeared) |
| 41 | `mi_parallel_subprocess-user-n3-bac` | NO_DUMP_JOB {} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | oracle-unsupported — Zeebe 8.5.0 не исполнил сценарий (NO_DUMP_JOB: dump job never appeared) |
| 42 | `mi_parallel_subprocess-user-n3-bca` | NO_DUMP_JOB {} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | oracle-unsupported — Zeebe 8.5.0 не исполнил сценарий (NO_DUMP_JOB: dump job never appeared) |
| 43 | `mi_parallel_subprocess-user-n3-cba` | NO_DUMP_JOB {} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | oracle-unsupported — Zeebe 8.5.0 не исполнил сценарий (NO_DUMP_JOB: dump job never appeared) |
| 44 | `mi_parallel_subprocess-user-n3-cab` | NO_DUMP_JOB {} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | oracle-unsupported — Zeebe 8.5.0 не исполнил сценарий (NO_DUMP_JOB: dump job never appeared) |
| 45 | `mi_sequential_subprocess-single-n0-` | COMPLETED {"items":[],"results":[]} | COMPLETED {"items":[],"results":[]} | match |
| 46 | `mi_sequential_subprocess-single-n1-a` | COMPLETED {"items":["a"],"results":["a"]} | COMPLETED {"items":["a"],"results":["a"]} | match |
| 47 | `mi_sequential_subprocess-single-n2-ab` | COMPLETED {"items":["a","b"],"results":["a","b"]} | COMPLETED {"items":["a","b"],"results":["a","b"]} | match |
| 48 | `mi_sequential_subprocess-single-n3-abc` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 49 | `mi_sequential_subprocess-chain-n0-` | COMPLETED {"items":[],"results":[]} | COMPLETED {"items":[],"results":[]} | match |
| 50 | `mi_sequential_subprocess-chain-n1-a` | COMPLETED {"items":["a"],"results":["a"]} | COMPLETED {"items":["a"],"results":["a"]} | match |
| 51 | `mi_sequential_subprocess-chain-n2-ab` | COMPLETED {"items":["a","b"],"results":["a","b"]} | COMPLETED {"items":["a","b"],"results":["a","b"]} | match |
| 52 | `mi_sequential_subprocess-chain-n3-abc` | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | match |
| 53 | `mi_sequential_subprocess-user-n0-` | COMPLETED {"items":[],"results":[]} | COMPLETED {"items":[],"results":[]} | match |
| 54 | `mi_sequential_subprocess-user-n1-a` | NO_DUMP_JOB {} | COMPLETED {"items":["a"],"results":["a"]} | oracle-unsupported — Zeebe 8.5.0 не исполнил сценарий (NO_DUMP_JOB: dump job never appeared) |
| 55 | `mi_sequential_subprocess-user-n2-ab` | NO_DUMP_JOB {} | COMPLETED {"items":["a","b"],"results":["a","b"]} | oracle-unsupported — Zeebe 8.5.0 не исполнил сценарий (NO_DUMP_JOB: dump job never appeared) |
| 56 | `mi_sequential_subprocess-user-n3-abc` | NO_DUMP_JOB {} | COMPLETED {"items":["a","b","c"],"results":["a","b","c"]} | oracle-unsupported — Zeebe 8.5.0 не исполнил сценарий (NO_DUMP_JOB: dump job never appeared) |

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
