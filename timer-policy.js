// When the switch-off timer tries again after a failed attempt, and when it gives up.
// The Android app follows the same rule (see Repository.timerRetryDelayMs).
//
// A unit can be out of reach for a long time: the module drops off Wi-Fi about once an hour,
// and sometimes stays away for up to an hour after that; roaming between access points can
// cost 15-35 minutes. So the timer keeps trying until 75 minutes after the switch-off time,
// quickly at first (a brief hiccup, another client's write lock) and then every 5 minutes.

export const TIMER_GIVE_UP_MS = 75 * 60_000;

/** The wait before the next attempt after `failed` failed attempts (1, 2, ...): 30 s, 1, 2, 4, then 5 min. */
export function timerRetryDelayMs(failed) {
  return Math.min(5 * 60_000, 30_000 * 2 ** Math.max(0, failed - 1));
}
