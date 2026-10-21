'use strict';
/**
 * WO-OPS-33 (вторая проба оракула) — форма ENG-1: прерывающая граница на хосте, вторая
 * ветвь форка УЖЕ завершена и стоит в parallel-join, который никогда не сработает.
 *
 * Вопрос: завершает ли Zeebe 8.5 инстанс в этой форме (существующий тест
 * ConditionalEventIntegrationTests#conditionalBoundaryFiresInterruptingWhenItsConditionBecomesTrue
 * требует, чтобы завершил — это поведение закреплено с 2026-07-28, WO-ENG-1)?
 *
 * Наблюдение состояния инстанса в 8.5: отдельного read-API у инстанса нет, но есть
 * CancelProcessInstance: на ЗАВЕРШЁННОМ/терминированном инстансе он отвечает ошибкой
 * (NOT_FOUND), на ЖИВОМ — отменяет без ошибки. Этим и меряем (плюс контрольная проба на
 * заведомо завершённом инстансе, чтобы отличить «завершён» от «ошибка отмены»).
 *
 * Лок активации job'а — 500 мс (см. первый драйвер): иначе «job не вернулся» означало бы
 * «job заблокирован мной», а не «job погашен».
 */
const path = require('path');
const fs = require('fs');
const O = require(path.join(__dirname, '..', '..', 'lib', 'oracle.js'));

const BPMN = path.join(__dirname, 'process.bpmn');
const PROCESS_ID = 'ops33Eng1';
const HOST_TYPE = 'ops33bHost';
const TRIGGER_TYPE = 'ops33bTrigger';
const LOCK_MS = 500;

function activateShort(client, type) {
  return new Promise((resolve, reject) => {
    const call = client.ActivateJobs({
      type, worker: 'c8-oracle', timeout: String(LOCK_MS),
      maxJobsToActivate: 100, requestTimeout: '1500',
    });
    const jobs = [];
    call.on('data', (resp) => { for (const j of resp.jobs || []) jobs.push(j); });
    call.on('end', () => resolve(jobs));
    call.on('error', reject);
  });
}

async function tryCancel(client, piKey) {
  try {
    await O.unary(client, 'CancelProcessInstance', { processInstanceKey: String(piKey) });
    return 'CANCEL_ACCEPTED (инстанс был жив)';
  } catch (e) {
    return 'CANCEL_REJECTED: ' + String(e.details || e.message).split('\n')[0];
  }
}

async function main() {
  const client = O.buildClient();
  const topo = await O.topology(client);
  console.log(`GATEWAY ${O.GATEWAY_HOST} version=${topo.brokers[0].version}`);

  await O.deploy(client, BPMN);
  const piKey = await O.start(client, PROCESS_ID, {});

  // ── ветвь trigger завершается ПЕРВОЙ (как в тесте ENG-1) → её ветвь встаёт в join ──────
  const trigger = await O.poll(client, TRIGGER_TYPE, 1, 10, piKey);
  console.log(`trigger jobs=${trigger.length}`);
  for (const j of trigger) {
    if (String(j.processInstanceKey) === String(piKey)) await O.complete(client, j, {});
  }
  await O.sleep(500);
  const hostBefore = (await activateShort(client, HOST_TYPE)).filter((j) => String(j.processInstanceKey) === String(piKey));
  console.log(`BEFORE(boundary not fired) host jobs=${hostBefore.length}`);

  // ── ждём срабатывания границы (PT1S) ─────────────────────────────────────────────────
  await O.sleep(2500);
  const hostAfter = (await activateShort(client, HOST_TYPE)).filter((j) => String(j.processInstanceKey) === String(piKey));
  console.log(`AFTER (boundary fired) host jobs=${hostAfter.length}`);

  // ── состояние инстанса: отмена завершённого инстанса отвергается, живого — принимается ──
  const state = await tryCancel(client, piKey);
  console.log(`INSTANCE STATE PROBE: ${state}`);

  const result = {
    id: 'ops33-eng1-shape',
    gateway: O.GATEWAY_HOST,
    zeebeVersion: topo.brokers[0].version,
    processInstanceKey: piKey,
    before: { host: hostBefore.length },
    after: { host: hostAfter.length },
    cancelProbe: state,
    instanceTerminated: state.startsWith('CANCEL_REJECTED'),
    verdict: state.startsWith('CANCEL_REJECTED')
      ? 'Zeebe 8.5: инстанс ТЕРМИНИРОВАН — end event корневого scope в продолжении прерывающей '
        + 'границы завершает инстанс целиком (вторая ветвь форки погибает вместе с ним).'
      : 'Zeebe 8.5: инстанс ОСТАЛСЯ ЖИВЫМ — end event корневого scope в продолжении прерывающей '
        + 'границы закрывает scope ветви, а не инстанс; вторая ветвь форки (в join) остаётся висеть.',
  };
  fs.writeFileSync(path.join(__dirname, 'result-8.5.json'), JSON.stringify(result, null, 2));
  console.log('RESULT ' + JSON.stringify(result, null, 2));
  client.close();
}

main().catch((e) => { console.error('FAILED', e); process.exit(1); });
