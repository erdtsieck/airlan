// The server's on/off timers, on mocked time: when they fire, retry and give up.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { TimerRunner, migrateTimers, nextOccurrence, TIMER_GIVE_UP_MS } from '../timers.js';

const MIN = 60_000;
const flush = () => new Promise(setImmediate);

/** A runner whose unit answers when `reachable`, recording every write and save. */
function setup(t) {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: Date.UTC(2026, 10, 6, 21, 0) });
  const world = { reachable: true, writes: [], saves: 0, power: false };
  const runner = new TimerRunner({
    switchPower: async (unit, power) => {
      if (!world.reachable) throw Object.assign(new Error('unreachable'), { code: 'unreachable' });
      world.writes.push(power);
      world.power = power;
    },
    save: async () => { world.saves++; },
  });
  const unit = { airconId: 'e81656cb0d45', name: 'Woonkamer' };
  const advance = async (ms) => {
    t.mock.timers.tick(ms);
    await flush();
  };
  return { world, runner, unit, advance };
}

test('an on-timer switches the unit on at its time and clears itself', async (t) => {
  const { world, runner, unit, advance } = setup(t);
  await runner.set(unit, 'on', Date.now() + 30 * MIN);
  await advance(30 * MIN - 1);
  assert.deepEqual(world.writes, []);
  await advance(1);
  assert.deepEqual(world.writes, [true]);
  assert.deepEqual(unit.timers, {});
});

test('a unit has one timer per action: setting again replaces it, the other action is left alone', async (t) => {
  const { world, runner, unit, advance } = setup(t);
  await runner.set(unit, 'off', Date.now() + 60 * MIN);
  await runner.set(unit, 'on', Date.now() + 10 * MIN);
  await runner.set(unit, 'on', Date.now() + 20 * MIN);
  await advance(10 * MIN);
  assert.deepEqual(world.writes, []);
  await advance(10 * MIN);
  assert.deepEqual(world.writes, [true]);
  assert.deepEqual(Object.keys(unit.timers), ['off']);
  await advance(40 * MIN);
  assert.deepEqual(world.writes, [true, false]);
});

test('cancelling one action keeps the other', async (t) => {
  const { world, runner, unit, advance } = setup(t);
  await runner.set(unit, 'off', Date.now() + 5 * MIN);
  await runner.set(unit, 'on', Date.now() + 10 * MIN);
  await runner.cancel(unit, 'off');
  await advance(10 * MIN);
  assert.deepEqual(world.writes, [true]);
});

test('an unreachable unit is retried ever less often until 75 minutes after the time, then marked failed', async (t) => {
  const { world, runner, unit, advance } = setup(t);
  world.reachable = false;
  const at = Date.now() + MIN;
  await runner.set(unit, 'on', at);
  await advance(MIN);
  while (unit.timers.on) await advance(30_000);
  assert.ok(Date.now() - at >= 70 * MIN, `gave up after ${(Date.now() - at) / MIN} min`);
  assert.ok(Date.now() - at <= TIMER_GIVE_UP_MS + 30_000);
  assert.deepEqual(unit.timerFailed, { action: 'on', at });
  // Setting a new timer clears the failure.
  await runner.set(unit, 'on', Date.now() + MIN);
  assert.equal(unit.timerFailed, undefined);
});

test('a unit that comes back within the window is switched after all', async (t) => {
  const { world, runner, unit, advance } = setup(t);
  world.reachable = false;
  await runner.set(unit, 'off', Date.now() + MIN);
  await advance(MIN);
  await advance(30 * MIN);
  assert.deepEqual(world.writes, []);
  world.reachable = true;
  await advance(5 * MIN);
  assert.deepEqual(world.writes, [false]);
  assert.deepEqual(unit.timers, {});
  assert.equal(unit.timerFailed, undefined);
});

test('a timer cancelled while it is retrying stops', async (t) => {
  const { world, runner, unit, advance } = setup(t);
  world.reachable = false;
  await runner.set(unit, 'on', Date.now() + MIN);
  await advance(MIN);
  await runner.cancel(unit, 'on');
  world.reachable = true;
  await advance(80 * MIN);
  assert.deepEqual(world.writes, []);
  assert.equal(unit.timerFailed, undefined);
});

test('a timer that expired while the server was down fires as soon as it is scheduled', async (t) => {
  const { world, runner, advance } = setup(t);
  const unit = { airconId: 'e81656cb0d45', name: 'Woonkamer', timers: { on: { at: Date.now() - 10 * MIN } } };
  runner.schedule(unit);
  await advance(0);
  assert.deepEqual(world.writes, [true]);
});

test('the next occurrence of a time of day is today while it is ahead, else tomorrow', () => {
  const now = new Date(2026, 10, 6, 21, 30).getTime();
  assert.equal(nextOccurrence('22:15', now), new Date(2026, 10, 6, 22, 15).getTime());
  assert.equal(nextOccurrence('07:00', now), new Date(2026, 10, 7, 7, 0).getTime());
  assert.equal(nextOccurrence('21:30', now), new Date(2026, 10, 7, 21, 30).getTime());
  for (const bad of ['7:00', '24:00', '12:60', '', null, undefined, 700]) assert.equal(nextOccurrence(bad, now), null, String(bad));
});

test('timers stored by 0.3 and older are read as the off-timer', () => {
  assert.deepEqual(
    migrateTimers({ airconId: 'a', offAt: 1234, timerFailedAt: 999 }),
    { airconId: 'a', timers: { off: { at: 1234 } }, timerFailed: { action: 'off', at: 999 } },
  );
  const current = { airconId: 'a', timers: { on: { at: 5 } } };
  assert.deepEqual(migrateTimers(structuredClone(current)), current);
});
