// The timers that switch a unit on or off at a set time. The WF-RAC module has no timers of
// its own, so the server keeps them; the Android app does the same with alarms (see
// Repository.fireTimer) and follows the same retry rule.
//
// A unit has at most one pending timer per action, `on` and `off`. When it fires it writes
// only the power: switching on brings back the unit's last mode, temperature and airflow.
//
// A unit can be out of reach for a long time: the module drops off Wi-Fi about once an hour,
// and sometimes stays away for up to an hour after that; roaming between access points can
// cost 15-35 minutes. So a timer keeps trying until 75 minutes after its time, quickly at
// first (a brief hiccup, another client's write lock) and then every 5 minutes.

export const TIMER_ACTIONS = ['on', 'off'];
export const TIMER_GIVE_UP_MS = 75 * 60_000;

/** The wait before the next attempt after `failed` failed attempts (1, 2, ...): 30 s, 1, 2, 4, then 5 min. */
export function timerRetryDelayMs(failed) {
  return Math.min(5 * 60_000, 30_000 * 2 ** Math.max(0, failed - 1));
}

/**
 * The next moment the clock reads `time` ("07:00") in this process's time zone, after `now`:
 * today if that is still ahead, else tomorrow. Null for anything that is not such a time.
 */
export function nextOccurrence(time, now = Date.now()) {
  const m = /^([01]\d|2[0-3]):([0-5]\d)$/.exec(String(time));
  if (!m) return null;
  const at = new Date(now);
  at.setHours(Number(m[1]), Number(m[2]), 0, 0);
  if (at.getTime() <= now) {
    at.setDate(at.getDate() + 1);
    at.setHours(Number(m[1]), Number(m[2]), 0, 0);
  }
  return at.getTime();
}

/**
 * Brings a unit as stored by 0.3 and older (`offAt`, `timerFailedAt`) to the current shape:
 * `timers: { on?: { at }, off?: { at } }` and `timerFailed: { action, at }`.
 */
export function migrateTimers(unit) {
  if (unit.offAt != null) unit.timers = { ...unit.timers, off: { at: unit.offAt } };
  if (unit.timerFailedAt != null) unit.timerFailed = { action: 'off', at: unit.timerFailedAt };
  delete unit.offAt;
  delete unit.timerFailedAt;
  return unit;
}

/**
 * Runs the stored timers of units. The unit objects are the truth (they are what is saved);
 * this only holds the setTimeout handles derived from them.
 *
 *   switchPower(unit, power): writes the power, not waiting for another client's write lock
 *   save(): persists the units
 */
export class TimerRunner {
  #handles = new Map();

  constructor({ switchPower, save, log = () => {} }) {
    this.switchPower = switchPower;
    this.save = save;
    this.log = log;
  }

  /** Sets the handles for all of a unit's timers; one that expired while the server was down fires right away. */
  schedule(unit) {
    for (const action of TIMER_ACTIONS) this.#arm(unit, action, () => this.#fire(unit, action));
  }

  async set(unit, action, at) {
    delete unit.timerFailed;
    unit.timers = { ...unit.timers, [action]: { at } };
    this.schedule(unit);
    await this.save();
  }

  async cancel(unit, action) {
    if (!unit.timers?.[action]) return;
    delete unit.timers[action];
    this.#clear(unit, action);
    await this.save();
  }

  async cancelAll(unit) {
    for (const action of TIMER_ACTIONS) await this.cancel(unit, action);
  }

  #key(unit, action) {
    return `${unit.airconId}/${action}`;
  }

  #clear(unit, action) {
    clearTimeout(this.#handles.get(this.#key(unit, action)));
    this.#handles.delete(this.#key(unit, action));
  }

  #arm(unit, action, run, at = unit.timers?.[action]?.at) {
    this.#clear(unit, action);
    if (at == null) return;
    this.#handles.set(this.#key(unit, action), setTimeout(run, Math.max(0, at - Date.now())));
  }

  /**
   * Switches the unit when its timer expires. Failures are retried (see timerRetryDelayMs)
   * until TIMER_GIVE_UP_MS after the timer's time; a write lock held by another client is
   * waited out. Then the timer is dropped and marked failed, for the app to show.
   */
  async #fire(unit, action, failed = 0) {
    const timer = unit.timers?.[action];
    if (!timer) return;
    const still = () => unit.timers?.[action] === timer; // Not cancelled or replaced meanwhile.
    const verb = action === 'on' ? 'switch on' : 'switch off';
    try {
      await this.switchPower(unit, action === 'on');
      this.log(`${unit.name}: timer expired, ${action === 'on' ? 'switched on' : 'switched off'}`);
      if (still()) delete unit.timers[action];
      if (!unit.timers?.[action]) this.#handles.delete(this.#key(unit, action));
      await this.save();
    } catch (e) {
      if (!still()) return;
      const delay = Math.max(timerRetryDelayMs(failed + 1), ((e.retryAfterS ?? 0) + 2) * 1000);
      if (Date.now() + delay <= timer.at + TIMER_GIVE_UP_MS) {
        this.log(`${unit.name}: timer could not ${verb} (${e.message}), retrying in ${Math.round(delay / 1000)} s`);
        this.#arm(unit, action, () => this.#fire(unit, action, failed + 1), Date.now() + delay);
      } else {
        this.log(`${unit.name}: timer could not ${verb} (${e.message}), giving up`);
        delete unit.timers[action];
        unit.timerFailed = { action, at: timer.at };
        this.#clear(unit, action);
        await this.save();
      }
    }
  }
}
