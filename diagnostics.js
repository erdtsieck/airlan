// A plain-text report about one unit for the user to send to the developer: the module's
// firmware and transport and the last requests with their outcome. Nothing that identifies
// the user goes in: no names, addresses only by their last number, the MAC only by its last
// four digits. The Android app writes the same report (data/Diagnostics.kt).

const IPV4 = /\b\d{1,3}\.\d{1,3}\.\d{1,3}\.(\d{1,3})\b/g;

/** An IPv4 address keeps only its last number: 192.168.1.23 becomes x.x.x.23. */
export const maskAddresses = (text) => String(text).replace(IPV4, (_, last) => `x.x.x.${last}`);

const pad = (n) => String(n).padStart(2, '0');
const clock = (ms) => { const d = new Date(ms); return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`; };
const day = (ms) => { const d = new Date(ms); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`; };

/**
 * `unit` as stored, `state` the last decoded state (or null), `trace` the unit's RequestTrace
 * (or null).
 */
export function report({ version, platform, unit, state, trace, now = Date.now() }) {
  const m = trace?.module;
  const lines = [
    `AirLAN diagnostics, ${day(now)} ${clock(now)}`,
    `Server ${version}, ${platform}`,
    '',
    `Unit: ...${unit.airconId.slice(-4)} at ${maskAddresses(unit.host)}, transport ${unit.scheme ?? 'unknown'}`,
    `Module: firmType ${m?.firmType ?? '?'}, wireless ${m?.wireless ?? '?'}, mcu ${m?.mcu ?? '?'}, accounts ${m?.accounts ?? '?'}`,
    `Model group: ${state?.modelNrRaw ?? unit.modelNrRaw ?? '?'}, left/right vane reported: ${!!unit.reportsVaneLR}`,
    state
      ? `State: power ${state.power ? 'on' : 'off'}, mode ${state.mode ?? 'unknown'}, set ${state.presetTemp}, fan ${state.airFlow}, ` +
        `up/down ${state.windUD}, left/right ${state.windLR}, 3D ${state.entrust}, error ${state.errorCode ?? 'none'}`
      : 'State: not read',
  ];
  const entries = trace?.entries() ?? [];
  lines.push('', `Last ${entries.length} requests:`);
  for (const e of entries) {
    lines.push(`${clock(e.at)} ${e.command} ${e.ms} ms ${e.result !== null ? `result ${e.result}` : maskAddresses(e.error ?? 'no answer')}`);
  }
  const answered = entries.filter((e) => e.result !== null);
  if (answered.length) {
    const average = Math.round(answered.reduce((sum, e) => sum + e.ms, 0) / answered.length);
    lines.push(`Average answer time: ${average} ms, failures: ${entries.filter((e) => e.result !== 0).length} of ${entries.length}`);
  }
  return lines.join('\n') + '\n';
}
