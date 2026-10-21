# WO-OPS-28 ФАЗА 5 («сквозные») — наш движок vs живой Zeebe 8.5.0

- Оракул: живой Zeebe 8.5.0 (gateway 8.5.0), прогон `docs/analysis/c8-oracle/scenario-matrix-cross.js`.
- Наш движок: те же схемы и тот же список действий (манифест `zorrobpm-engine/target/scenario-matrix/manifest-cross.json`).
- Сценариев: 4; совпали: 4; расхождений: 0.
- Наблюдение: переменные корневого скоупа в «наблюдательной» задаче + «что осталось активным» (у нас — нарушения инварианта утечек G-P, у оракула — оставшиеся активируемыми job'ы).
- PG vs H2: те же сценарии на PostgreSQL 16 — сверяются с той же прибитой частью, расхождение было бы отдельной строкой.

| # | Сценарий | Ось | Zeebe 8.5 live | ours (H2) | ours (PG) | Вердикт |
|---|---|---|---|---|---|---|
| 1 | `f5-parallel-race` | PARALLEL_RACE | COMPLETED {"stA":"stA","stB":"stB","stC":"stC","stD":"stD"} / leftovers=0 / версии=2 | COMPLETED {"stC":"stC","stA":"stA","stB":"stB","stD":"stD"} / leaks=0 / incidents=0 | COMPLETED {"stC":"stC","stD":"stD","stB":"stB","stA":"stA"} / leaks=0 / incidents=0 | match |
| 2 | `f5-restart-midprocess` | RESTART_MIDPROCESS | COMPLETED {"phase":"after","started":"yes"} / leftovers=0 / продолжение=stAfter / версии=1 | COMPLETED {"started":"yes","phase":"after"} / leaks=0 / incidents=0 | COMPLETED {"started":"yes","phase":"after"} / leaks=0 / incidents=0 | match |
| 3 | `f5-version-v1` | VERSION_MIGRATION | COMPLETED {"phase":"stV1"} / leftovers=0 / продолжение=stV1 / версии=1->2 | COMPLETED {"phase":"stV1"} / leaks=0 / incidents=0 | COMPLETED {"phase":"stV1"} / leaks=0 / incidents=0 | match |
| 4 | `f5-version-v2` | VERSION_MIGRATION | COMPLETED {"phase":"stV2"} / leftovers=0 / продолжение=stV2 / версии=3->4 | COMPLETED {"phase":"stV2"} / leaks=0 / incidents=0 | COMPLETED {"phase":"stV2"} / leaks=0 / incidents=0 | match |

## Расхождений нет

Ни по одному сценарию среза «сквозные» наблюдаемое состояние, терминальный статус
и остаток не разошлись ни с живым Zeebe 8.5.0, ни между H2 и PostgreSQL 16.

## Что означает каждый сценарий на оракуле

- `f5-restart-midprocess` — у Zeebe нет понятия «рестарт движка»: состояние живёт в партиции брокера,
  и перезапуск брокера его не теряет. Поэтому сверяется ИТОГ прогона (процесс дошёл до конца с теми же
  переменными), а не механизм: «durable state» у нас обеспечивает БД, у Zeebe — репликация партиции.
- `f5-parallel-race` — все job'ы ветвей форка завершаются ОДНИМ ЗАЛПОМ (`Promise.all`), то есть
  действительно параллельно с точки зрения клиента; у нас те же четыре завершения делают четыре
  РЕАЛЬНЫХ потока с барьером. Сверяется, что джойн дал ровно один следующий job.
- `f5-version-v1` — старт на v1 → деплой v2 ТОГО ЖЕ ключа → завершение задачи. ЖИВОЙ факт Zeebe 8.5.0:
  инстанс продолжился СВОЕЙ версией (`stV1`), а не новой (`stV2`) — версия фиксируется на старте.
  Это измерение живьём, а не цитата из документации. `f5-version-v2` — контроль: инстанс, стартовавший
  на v2, продолжается на v2.
