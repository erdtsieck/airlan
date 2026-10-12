// How a write deals with the unit's account table and its 60-second write lock, against the
// fake unit in tools/fake-unit.mjs, which models both.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { WfRacClient, WfRacError, AIRLAN_OPERATOR_ID } from '../wfrac.js';
import { startFakeUnit } from '../tools/fake-unit.mjs';
import { TIMER_GIVE_UP_MS, timerRetryDelayMs } from '../timer-policy.js';

const ID = 'fa4e00000001';

async function setup(t, options) {
  const fake = await startFakeUnit(options);
  t.after(() => fake.server.close());
  const client = new WfRacClient({ host: '127.0.0.1', port: fake.port, scheme: 'http', deviceId: 'airlan-test', operatorId: 'op-test', minIntervalMs: 0 });
  return { ...fake, client };
}

test('a write registers first and then applies the change', async (t) => {
  const { unit, client } = await setup(t);
  const next = await client.change(ID, { power: true, presetTemp: 23 }, { timezone: 'UTC' });
  assert.equal(next.power, true);
  assert.equal(unit.state.presetTemp, 23);
  assert.ok(unit.accounts.has('op-test'));
});

test('a full account table is no_account_slot, not a generic refusal', async (t) => {
  const { client } = await setup(t, { accountsTaken: 4 });
  await assert.rejects(client.registerAccount(ID, 'UTC'), (e) => e instanceof WfRacError && e.code === 'no_account_slot');
  await assert.rejects(client.change(ID, { power: true }, { timezone: 'UTC' }), (e) => e.code === 'no_account_slot');
});

test('re-registering an account the unit holds takes no second slot', async (t) => {
  const { unit, client } = await setup(t, { accountsTaken: 3 });
  await client.registerAccount(ID, 'UTC');
  await client.registerAccount(ID, 'UTC');
  assert.equal(unit.accounts.size, 4);
});

test('another client holding the write lock is unit_busy with the time it has left', async (t) => {
  const { unit, client } = await setup(t);
  await client.registerAccount(ID, 'UTC');
  unit.otherWrite();
  await assert.rejects(
    client.change(ID, { power: true }, { timezone: 'UTC', waitUpToS: 5 }),
    (e) => e.code === 'unit_busy' && e.retryAfterS >= 55 && e.retryAfterS <= 60,
  );
  assert.equal(unit.state.power, false);
});

test('a lock that lapses soon is waited out, once', async (t) => {
  const { unit, client } = await setup(t);
  await client.registerAccount(ID, 'UTC');
  unit.lock = { writer: 'other-app', expires: Math.round(Date.now() / 1000) + 1 };
  const waits = [];
  const next = await client.change(ID, { power: true }, { timezone: 'UTC', waitUpToS: 5, onWait: (s) => waits.push(s) });
  assert.equal(next.power, true);
  assert.equal(waits.length, 1);
});

test('our own write lock does not count as busy', async (t) => {
  const { client } = await setup(t);
  await client.change(ID, { power: true }, { timezone: 'UTC' });
  const { lockedForS } = await client.getStat(ID);
  assert.equal(lockedForS, 0);
  const next = await client.change(ID, { presetTemp: 24 }, { timezone: 'UTC', waitUpToS: 0 });
  assert.equal(next.presetTemp, 24);
});

test('an account removed behind our back is registered again', async (t) => {
  const { unit, client } = await setup(t);
  await client.change(ID, { power: true }, { timezone: 'UTC' });
  unit.accounts.delete('op-test'); // e.g. freed from another phone
  unit.lock = { writer: null, expires: 0 };
  const next = await client.change(ID, { power: false }, { timezone: 'UTC' });
  assert.equal(next.power, false);
  assert.ok(unit.accounts.has('op-test'));
});

test('every AirLAN client registers the one shared account', async (t) => {
  const fake = await startFakeUnit();
  t.after(() => fake.server.close());
  for (const deviceId of ['phone-1', 'phone-2', 'server']) {
    const c = new WfRacClient({ host: '127.0.0.1', port: fake.port, scheme: 'http', deviceId, minIntervalMs: 0 });
    await c.change(ID, { power: deviceId !== 'server' }, { timezone: 'UTC' });
    fake.unit.lock = { writer: null, expires: 0 }; // a minute later: the next writer is not kept out
  }
  assert.deepEqual([...fake.unit.accounts], [AIRLAN_OPERATOR_ID]);
});

test('migrating frees the old per-installation account first, so it works on a full unit', async (t) => {
  const { unit, client } = await setup(t, { accountsTaken: 3 });
  unit.accounts.add('legacy-op'); // 4 of 4: three other apps and our old account
  await client.migrateAccount(ID, 'legacy-op', 'UTC');
  assert.deepEqual([...unit.accounts].sort(), ['op-test', 'other-app-1', 'other-app-2', 'other-app-3']);
  // Again, with the old account gone already (the unit answers result 1): still fine.
  await client.migrateAccount(ID, 'legacy-op', 'UTC');
  assert.equal(unit.accounts.size, 4);
  const deletes = unit.requests.filter((r) => r.command === 'deleteAccountInfo');
  assert.ok(deletes.every((r) => r.operatorId === 'legacy-op'), 'a delete is sent as the account it deletes');
});

test('the timer retries quickly at first, then every five minutes, for 75 minutes', () => {
  assert.deepEqual([1, 2, 3, 4, 5, 6, 20].map(timerRetryDelayMs), [30e3, 60e3, 120e3, 240e3, 300e3, 300e3, 300e3]);
  assert.equal(TIMER_GIVE_UP_MS, 75 * 60e3);
  // An outage of a full hour is still covered.
  let at = 0;
  let failed = 0;
  while (at + timerRetryDelayMs(failed + 1) <= TIMER_GIVE_UP_MS) at += timerRetryDelayMs(++failed);
  assert.ok(at >= 70 * 60e3, `last attempt at ${at / 60e3} min`);
});

test('fan speed and the up/down vane are written with the rest of the state kept', async (t) => {
  const { unit, client } = await setup(t);
  await client.change(ID, { power: true, presetTemp: 22 }, { timezone: 'UTC' });
  for (const [airFlow, windUD] of [[3, 0], [0, 2], [4, 4], [1, 1]]) {
    await client.change(ID, { airFlow, windUD }, { timezone: 'UTC' });
    assert.deepEqual([unit.state.airFlow, unit.state.windUD, unit.state.power, unit.state.presetTemp], [airFlow, windUD, true, 22]);
  }
});
