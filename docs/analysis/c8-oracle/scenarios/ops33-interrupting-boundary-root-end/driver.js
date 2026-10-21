'use strict';
/**
 * WO-OPS-33 — драйвер оракула Zeebe 8.5 для сценария
 * «прерывающая граница уводит ветвь форка в end event КОРНЕВОГО scope, пока вторая ветвь жива».
 *
 * Вопрос WO (п.4 «Что сделать»): что делает Zeebe, когда прерывающая граница уводит ветвь
 * в end event корневого scope при живой второй ветви форка?
 *
 * Наблюдение (только gRPC, ничего не правим в чужих контейнерах):
 *   1) деплой process.bpmn, старт инстанса;
 *   2) снимок @~300 мс: job хоста есть (граница PT1S ещё не выстрелила), job сестринской
 *      ветви есть (обе ветви форка живы);
 *   3) снимок @~2.5 c: граница выстрелила (job хоста погашен границей);
 *      смотрим, ЖИВ ЛИ job сестринской ветви:
 *        - job сестринской ветви на месте → инстанс НЕ терминирован, живая ветвь НЕ погашена;
 *        - job сестринской ветви исчез → инстанс терминирован корневым end event, живая
 *          ветвь погашена вместе с ним (scope termination).
 *
 * ВАЖНО про блокировку job'а: активация ЛОКАЕТ job на время activation timeout. Поэтому здесь
 * timeout = 500 мс (не 30 c как в lib/oracle.js): иначе «job не вернулся» означало бы «job
 * заблокирован МНОЙ», а не «job погашен» — это был бы ложный вывод.
 *
 * Запуск: node driver.js   (нужен стенд Zeebe 8.5: gateway localhost:36500, C8_ORACLE_GW).
 */
const path = require('path');
const fs = require('fs');
const O = require(path.join(__dirname, '..', '..', 'lib', 'oracle.js'));

const BPMN = path.join(__dirname, 'process.bpmn');
const PROCESS_ID = 'ops33ForkBoundary';
const HOST_TYPE = 'ops33Host';
const SIBLING_TYPE = 'ops33Sibling';
const LOCK_MS = 500;

/** Активация с КОРОТКИМ локом (см. предупреждение в шапке): 500 мс вместо 30 c. */
function activateShort(client, type) {
  return new Promise((resolve, reject) => {
    const call = client.ActivateJobs({
      type,
      worker: 'c8-oracle',
      timeout: String(LOCK_MS),
      maxJobsToActivate: 100,
      requestTimeout: '1500',
    });
    const jobs = [];
    call.on('data', (resp) => { for (const j of resp.jobs || []) jobs.push(j); });
    call.on('end', () => resolve(jobs));
    call.on('error', reject);
  });
}

async function sample(client, piKey, tag) {
  const host = (await activateShort(client, HOST_TYPE)).filter((j) => String(j.processInstanceKey) === String(piKey));
  const sibling = (await activateShort(client, SIBLING_TYPE)).filter((j) => String(j.processInstanceKey) === String(piKey));
  console.log(`${tag} host jobs=${host.length} sibling jobs=${sibling.length}`);
  return { host: host.length, sibling: sibling.length };
}

async function main() {
  const client = O.buildClient();
  const topo = await O.topology(client);
  console.log(`GATEWAY ${O.GATEWAY_HOST} version=${topo.brokers[0].version}`);

  await O.deploy(client, BPMN);
  const piKey = await O.start(client, PROCESS_ID, {});

  const before = await sample(client, piKey, 'BEFORE(boundary not fired yet)');
  await O.sleep(2500);
  const after = await sample(client, piKey, 'AFTER (boundary fired)');

  const result = {
    id: 'ops33-interrupting-boundary-root-end',
    gateway: O.GATEWAY_HOST,
    zeebeVersion: topo.brokers[0].version,
    processInstanceKey: piKey,
    activationLockMs: LOCK_MS,
    before,
    after,
    siblingStillAlive: after.sibling > 0,
    instanceTerminated: after.sibling === 0,
    verdict: after.sibling > 0
      ? 'Zeebe 8.5: инстанс НЕ терминирован — живая вторая ветвь форка ОСТАЛАСЬ живой; '
        + 'end event корневого scope в продолжении прерывающей границы закрывает scope ветви, '
        + 'а не инстанс.'
      : 'Zeebe 8.5: инстанс терминирован корневым end event, живая вторая ветвь форка '
        + 'ПОГАШЕНА вместе с ним (scope termination).',
  };
  fs.writeFileSync(path.join(__dirname, 'result-8.5.json'), JSON.stringify(result, null, 2));
  console.log('RESULT ' + JSON.stringify(result, null, 2));

  // уборка: свой инстанс — отменяем, чтобы не оставлять мусор на общем стенде
  await O.cancelPi(client, piKey);
  console.log('CLEANUP cancelled the probe instance');
  client.close();
}

main().catch((e) => {
  console.error('FAILED', e);
  process.exit(1);
});
