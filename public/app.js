// UI text lives in locales/<language>.json, English being the source language. Adding a
// language is adding a file; keys missing from it fall back to English.
let messages = {};
let locale = 'en';

async function loadMessages() {
  const fetchLocale = (lang) => fetch(`locales/${lang}.json`).then((r) => (r.ok ? r.json() : null)).catch(() => null);
  const english = (await fetchLocale('en')) ?? {};
  for (const tag of navigator.languages ?? [navigator.language]) {
    const lang = tag.slice(0, 2).toLowerCase();
    const found = lang === 'en' ? english : await fetchLocale(lang);
    if (found) {
      messages = { ...english, ...found };
      locale = tag;
      break;
    }
  }
  if (!Object.keys(messages).length) messages = english;
  document.documentElement.lang = locale;
}

const t = (key, vars = {}) => (messages[key] ?? key).replace(/\{(\w+)\}/g, (_, v) => vars[v] ?? '');

const ICONS = {
  power: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M12 3v8"/><path d="M6.3 6.8a8 8 0 1 0 11.4 0"/></svg>',
  cool: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M12 2v20M4.9 6.5l14.2 11M4.9 17.5l14.2-11"/><path d="M9 4l3 2.5L15 4M9 20l3-2.5L15 20"/></svg>',
  heat: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="4.2"/><path d="M12 2v2.5M12 19.5V22M2 12h2.5M19.5 12H22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M4.9 19.1l1.8-1.8M17.3 6.7l1.8-1.8"/></svg>',
  gear: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/></svg>',
  up: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 15l6-6l6 6"/></svg>',
  down: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6l6-6"/></svg>',
  edit: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 20h4L19 9l-4-4L4 16z"/></svg>',
  cube: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l8 4.5v9L12 21l-8-4.5v-9z"/><path d="M12 12l8-4.5M12 12v9M12 12L4 7.5"/></svg>',
  swingUD: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v18M8 7l4-4 4 4M8 17l4 4 4-4"/></svg>',
  swingLR: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12h18M7 8l-4 4 4 4M17 8l4 4-4 4"/></svg>',
};
// Fan speed: 'auto' or a manual step, quiet to high. The up/down vane: 'swing' or a fixed
// position, highest to lowest. The left/right vane: 'swing', 1–5 from left to right, 6 spread
// out, 7 pointed inwards. '3d' (3D auto) hands both vanes to the unit; only for units that
// report their left/right vane (vaneLR is null for the others).
const parseSetting = (s) => /^\d$/.test(s) ? Number(s) : s;
const label = (prefix) => (v) => v === null || v === undefined ? '–' : v === '3d' ? t('auto3d') : t(`${prefix}_${v}`);
const fanLabel = label('fan');
const vaneLabel = label('vane');
const vaneLRLabel = label('vaneLR');
const TIMER_MINUTES = [15, 30, 60, 120, 180, 240, 300, 360];

// The airflow drawings. Each vane position is a ray from a pivot; the rays' ends lie on an
// ellipse, so the fan fits its box. A tap anywhere in the drawing picks the nearest ray.
// Up/down, side view: the unit's body top left, rays from just below the horizontal (highest)
// to steep (lowest). Left/right, top view: rays from the pivot at the bottom, left to right.
const FAN_UD = { w: 112, h: 64, px: 16, py: 12, a: 90, b: 50, angles: [6, 24, 42, 60] };
const FAN_LR = { w: 120, h: 60, px: 60, py: 56, a: 54, b: 50, angles: [-66, -33, 0, 33, 66] };
const rayEnd = (f, deg) => {
  const r = (deg * Math.PI) / 180;
  return f === FAN_UD ? [f.px + f.a * Math.cos(r), f.py + f.b * Math.sin(r)] : [f.px + f.a * Math.sin(r), f.py - f.b * Math.cos(r)];
};
/** The index of the ray nearest to a point in the drawing's own coordinates. */
function nearestRay(f, x, y) {
  const angle = f === FAN_UD
    ? (Math.atan2((y - f.py) / f.b, (x - f.px) / f.a) * 180) / Math.PI
    : (Math.atan2((x - f.px) / f.a, (f.py - y) / f.b) * 180) / Math.PI;
  return f.angles.reduce((best, a, i) => (Math.abs(a - angle) < Math.abs(f.angles[best] - angle) ? i : best), 0);
}
/**
 * A fan of rays. `lit` are the rays drawn in the accent colour, `dot` the one with an end
 * dot; `faint` draws every ray half-transparent in the accent (swinging, 3D auto). Every ray
 * is also a button for keyboards and screen readers.
 */
function fanSvg(id, f, { lit = [], dot = null, faint = false, labels, pressed, disabled }) {
  const rays = f.angles.map((deg, i) => {
    const [x, y] = rayEnd(f, deg).map((n) => n.toFixed(1));
    const cls = faint ? 'faint' : lit.includes(i) ? 'lit' : 'idle';
    const end = dot === i ? `<circle class="end" cx="${x}" cy="${y}" r="4"/>` : '';
    return `<g role="button" tabindex="${disabled ? -1 : 0}" data-ray="${i}" aria-label="${escape(labels[i])}" aria-pressed="${pressed(i)}"><line class="${cls}" x1="${f.px}" y1="${f.py}" x2="${x}" y2="${y}"/>${end}</g>`;
  }).join('');
  const body = f === FAN_UD ? '<rect class="body" x="0" y="2" width="28" height="11" rx="3"/>' : '';
  return `<svg class="fan${disabled ? ' disabled' : ''}" id="${id}" width="${f.w}" height="${f.h}" viewBox="0 0 ${f.w} ${f.h}" role="group">${body}${rays}</svg>`;
}
const TEMP_MIN = 16, TEMP_MAX = 30;

let units = [];
let selected = null;
try { selected = localStorage.getItem('airlan.selected'); } catch {}
let pendingTemp = null, tempTimer = null, busy = false;
let screen = 'unit'; // 'unit' | 'manage'

const $ = (id) => document.getElementById(id);
const fmt = (n) => n == null ? '–' : n.toLocaleString(locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const unit = () => units.find((u) => u.airconId === selected);
// Unnamed units are numbered so they can be told apart until the user names them.
// A unit's airconId is its Wi-Fi module's MAC address; shown as printed on the module.
const macLabel = (id) => /^[0-9a-f]{12}$/.test(id) ? id.toUpperCase().match(/../g).join(':') : id;
const displayName = (u) => u.name ?? `${t('newUnit')} ${units.filter((x) => !x.name).indexOf(u) + 1}`;
const errorText = (err) => (`error.${err?.code}` in messages ? t(`error.${err.code}`, { s: err.retryAfterS }) : err?.message ?? t('error.server'));
const timerLabel = (min) => min < 60 ? t('minutesShort', { n: min }) : t('hoursShort', { n: min / 60 });

function setStatus(text, error = false) {
  $('status').textContent = text;
  $('status').className = 'status' + (error ? ' error' : '');
}

async function api(method, path, body) {
  let res;
  try {
    res = await fetch(path, { method, headers: body ? { 'Content-Type': 'application/json' } : {}, body: body && JSON.stringify(body) });
  } catch {
    throw { code: 'server' };
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw data.error ?? { code: 'server' };
  return data;
}

async function refresh() {
  // Don't re-render under someone who is typing a name or an address.
  if (busy || document.activeElement?.tagName === 'INPUT') return;
  try {
    units = await api('GET', '/api/units');
    if (!unit()) selected = units[0]?.airconId ?? null;
    render();
    const u = unit();
    setStatus(u && !u.online ? errorText(u.error) : '', u && !u.online);
  } catch (e) {
    setStatus(errorText(e), true);
  }
}

async function change(body, label, u = unit()) {
  busy = true;
  setStatus(label + '…');
  try {
    const { warning, ...next } = await api('PATCH', `/api/units/${u.airconId}`, body);
    Object.assign(u, next);
    // E.g. the unit has no free account slot: the name is saved, but switching will fail.
    setStatus(warning ? errorText(warning) : '', !!warning);
  } catch (e) {
    setStatus(errorText(e), true);
  } finally {
    busy = false;
    render();
  }
}

async function timer(minutes) {
  const u = unit();
  try {
    const next = minutes
      ? await api('PUT', `/api/units/${u.airconId}/timer`, { minutes })
      : await api('DELETE', `/api/units/${u.airconId}/timer`);
    Object.assign(u, next);
    render();
  } catch (e) {
    setStatus(errorText(e), true);
  }
}

function nudgeTemp(delta) {
  const u = unit();
  pendingTemp = Math.min(TEMP_MAX, Math.max(TEMP_MIN, (pendingTemp ?? u.presetTemp) + delta));
  render();
  // The module wants to be addressed calmly: only send once the taps have stopped.
  clearTimeout(tempTimer);
  tempTimer = setTimeout(async () => {
    const temp = pendingTemp;
    await change({ presetTemp: temp }, t('setTo', { temp: fmt(temp) }));
    pendingTemp = null;
    render();
  }, 700);
}

function rename(u = unit()) {
  const name = prompt(t('renamePrompt'), u.name ?? '');
  if (name && name.trim() && name.trim() !== u.name) change({ name: name.trim() }, t('saving'), u);
}

function remaining(offAt) {
  const min = Math.max(0, Math.ceil((offAt - Date.now()) / 60000));
  const h = Math.floor(min / 60);
  if (!h) return t('remainingMinutes', { m: min });
  return min % 60 ? t('remainingHours', { h, m: min % 60 }) : t('remainingWholeHours', { h });
}

/**
 * Equal buttons side by side, as many to a row as the first of `counts` (or `counts` and
 * fewer) at which no label is cut off by a narrow screen, large text or a long translation.
 */
function fitColumns(grid, counts) {
  if (!grid) return;
  for (const n of Array.isArray(counts) ? [...counts, 1] : Array.from({ length: counts }, (_, i) => counts - i)) {
    grid.style.gridTemplateColumns = `repeat(${n}, minmax(0, 1fr))`;
    if (![...grid.children].some(overflows)) return;
  }
}

/**
 * Whether an element's content is wider than the room inside its padding. scrollWidth misses
 * content that only runs into the right padding, so measure the content itself.
 */
function overflows(el) {
  const range = document.createRange();
  range.selectNodeContents(el);
  const style = getComputedStyle(el);
  const room = el.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
  return range.getBoundingClientRect().width > room + 0.5;
}

/** Lays out every row of equal buttons. */
function fitAll() {
  fitColumns(document.querySelector('.modes'), 2);
  fitColumns(document.querySelector('.chips'), 4);
}

/**
 * Fan speed and the vanes, as drawings: rows of a label with the current value on the left
 * and the control on the right (below the label when they do not fit side by side). 3D auto,
 * which aims both vanes, is a toggle in the header; the left/right rows and the toggle are
 * only there for units that report a left/right vane (vaneLR is null for the others).
 */
function airflowCard(u, off) {
  const both = u.vaneLR !== null;
  const auto3d = both && u.vane === '3d';
  const dis = off ? 'disabled' : '';
  const accent = u.mode === 'cool' || u.mode === 'heat' ? `var(--${u.mode})` : 'var(--text)';
  const level = typeof u.fanSpeed === 'number' ? u.fanSpeed : 0;
  const bars = [1, 2, 3, 4].map((n) => `<button class="bar" data-fan="${n}" aria-label="${escape(fanLabel(n))}" aria-pressed="${u.fanSpeed === n}" ${dis}><span class="${n <= level ? 'lit' : ''}" style="height:${2 + 6 * n}px"></span></button>`).join('');
  const ud = typeof u.vane === 'number' ? u.vane - 1 : null;
  const udSvg = fanSvg('fanUD', FAN_UD, {
    lit: ud === null ? [] : [ud], dot: ud, faint: u.vane === 'swing' || auto3d,
    labels: [1, 2, 3, 4].map(vaneLabel), pressed: (i) => u.vane === i + 1, disabled: off,
  });
  const lr = u.vaneLR;
  const lrLit = lr === 6 ? [0, 4] : lr === 7 ? [1, 3] : typeof lr === 'number' ? [lr - 1] : [];
  const lrSvg = both ? fanSvg('fanLR', FAN_LR, {
    lit: lrLit, dot: typeof lr === 'number' && lr <= 5 ? lr - 1 : null, faint: lr === 'swing' || auto3d,
    labels: [1, 2, 3, 4, 5].map(vaneLRLabel), pressed: (i) => lr === i + 1, disabled: off,
  }) : '';
  const row = (title, value, control, extra = '') => `
      <div class="af-row${extra}">
        <div class="af-label"><b>${title}</b>${value !== null ? `<span class="val">${escape(value)}</span>` : ''}</div>
        <div class="af-ctl">${control}</div>
      </div>`;
  const spread = [6, 7].map((v) => `<button class="pill" data-vane-lr="${v}" aria-pressed="${lr === v}" ${dis}>${vaneLRLabel(v)}</button>`).join('');
  return `
    <section class="card airflow" style="--flow: ${accent}">
      <div class="af-head">
        <h2>${t('airflow')}</h2>
        ${both ? `<button class="chip3d" id="auto3d" aria-pressed="${auto3d}" ${dis}>${ICONS.cube}${t('auto3d')}</button>` : ''}
      </div>
      ${row(t('fanSpeed'), fanLabel(u.fanSpeed), `<button class="pill" data-fan="auto" aria-pressed="${u.fanSpeed === 'auto'}" ${dis}>${t('fan_auto')}</button><div class="bars" role="group" aria-label="${escape(t('fanSpeed'))}">${bars}</div>`)}
      ${row(t('vane'), vaneLabel(u.vane), `${udSvg}<button class="round" data-vane="swing" aria-label="${escape(t('vane_swing'))}" aria-pressed="${u.vane === 'swing'}" ${dis}>${ICONS.swingUD}</button>`)}
      ${both ? row(t('vaneLR'), vaneLRLabel(lr), `${lrSvg}<button class="round" data-vane-lr="swing" aria-label="${escape(t('vaneLR_swing'))}" aria-pressed="${lr === 'swing'}" ${dis}>${ICONS.swingLR}</button>`) : ''}
      ${both ? row(`<span class="minor">${t('spread')}</span>`, null, spread, ' minor-row') : ''}
    </section>`;
}

function wireAirflow(u, off) {
  if (off) return;
  const set = (body, what, value) => change(body, `${what}: ${value}`);
  document.querySelectorAll('.airflow [data-fan]').forEach((b) => b.onclick = () => {
    const f = parseSetting(b.dataset.fan);
    if (u.fanSpeed !== f) set({ fanSpeed: f }, t('fanSpeed'), fanLabel(f));
  });
  const vane = (v) => { if (u.vane !== v) set({ vane: v }, t('vane'), vaneLabel(v)); };
  const vaneLR = (v) => { if (u.vaneLR !== v) set({ vaneLR: v }, t('vaneLR'), vaneLRLabel(v)); };
  document.querySelectorAll('.airflow [data-vane]').forEach((b) => b.onclick = () => vane(parseSetting(b.dataset.vane)));
  document.querySelectorAll('.airflow [data-vane-lr]').forEach((b) => b.onclick = () => vaneLR(parseSetting(b.dataset.vaneLr)));
  // Off again: back to the position the unit had before is unknown, so the highest.
  if ($('auto3d')) $('auto3d').onclick = () => (u.vane === '3d' ? vane(1) : set({ vane: '3d' }, t('airflow'), t('auto3d')));
  for (const [id, f, pick] of [['fanUD', FAN_UD, vane], ['fanLR', FAN_LR, vaneLR]]) {
    const svg = $(id);
    if (!svg) continue;
    svg.onclick = (e) => {
      const box = svg.getBoundingClientRect();
      pick(nearestRay(f, ((e.clientX - box.left) * f.w) / box.width, ((e.clientY - box.top) * f.h) / box.height) + 1);
    };
    svg.querySelectorAll('[data-ray]').forEach((g) => g.onkeydown = (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pick(Number(g.dataset.ray) + 1); }
    });
  }
}

function escape(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

/** A unit's tile: a dot for how it stands, its name, and its set temperature when on. */
function tile(u) {
  const known = u.mode === 'cool' || u.mode === 'heat' ? u.mode : 'other';
  const [dot, standing] = !u.online ? ['unreachable', t('unreachable')]
    : !u.power ? ['', t('tileOff')]
    : [`on ${known}`, t('tileOn', { mode: t(u.mode) })];
  return `
    <button role="tab" aria-selected="${u.airconId === selected}" data-id="${u.airconId}">
      <span class="tile-dot"><span class="dot ${dot}" role="img" aria-label="${escape(standing)}"></span></span>
      <span class="tile-body"><span class="tile-name">${escape(displayName(u))}</span>${
        u.online && u.power ? `<span class="tile-temp ${known}">${fmt(u.presetTemp)}°</span>` : ''}</span>
    </button>`;
}

const gearButton = () => `<button class="gear" data-manage aria-label="${t('manage')}">${ICONS.gear}</button>`;

/**
 * The tiles in rows of `columns`, the gear ending the last row. Two columns, or one when a
 * word of a name would not fit a tile: names wrap, but never inside a word.
 */
function renderTiles() {
  const nav = $('units');
  nav.hidden = screen === 'manage' || units.length < 2;
  if (nav.hidden) return;
  for (const columns of [2, 1]) {
    const rows = [];
    for (let i = 0; i < units.length; i += columns) rows.push(units.slice(i, i + columns));
    nav.innerHTML = rows.map((row, r) => `<div class="tile-row">${row.map(tile).join('')}${
      r < rows.length - 1 ? ''
        : row.length < columns ? '<span class="gear-cell"></span>'.repeat(columns - row.length - 1) + `<span class="gear-cell">${gearButton()}</span>`
        : gearButton()}</div>`).join('');
    if (![...nav.querySelectorAll('.tile-name')].some(overflows)) return;
  }
}

function render() {
  renderTiles();

  const u = unit();
  if (screen === 'manage' || !u) return renderManage();

  const accent = u.power && (u.mode === 'cool' || u.mode === 'heat') ? `var(--${u.mode})` : 'var(--off)';
  document.documentElement.style.setProperty('--accent', accent);
  const off = !u.online || busy;
  const temp = pendingTemp ?? u.presetTemp;
  // Only temperatures the unit actually reported.
  const climate = u.online
    ? [
        u.indoorTemp != null && `${t('indoor')} ${fmt(u.indoorTemp)}°`,
        u.outdoorTemp != null && `${t('outdoor')} ${fmt(u.outdoorTemp)}°`,
        u.errorCode && `${t('fault')} ${u.errorCode}`,
      ].filter(Boolean).join(' · ')
    : t('unreachable');
  const clock = (ms) => new Date(ms).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' });
  const offAtTime = u.offAt && clock(u.offAt);
  const timerFailed = !u.offAt && u.timerFailedAt ? `<p class="hint error">${t('timerFailed', { time: clock(u.timerFailedAt) })}</p>` : '';

  const naming = u.name ? '' : `
    <section class="card naming">
      <h2>${t('nameThisUnit')}</h2>
      <p class="hint">${t('nameHint')}</p>
      <p class="ident">${escape(u.host)} · ${t('mac', { mac: macLabel(u.airconId) })}</p>
      <form class="field" id="nameForm">
        <input type="text" id="nameInput" maxlength="40" placeholder="${t('namePlaceholder')}" autocomplete="off">
        <button class="btn primary">${t('save')}</button>
      </form>
    </section>`;

  $('view').innerHTML = naming + `
    <section class="card">
      <div class="head">
        <div style="min-width:0">
          <div class="name"><span>${escape(displayName(u))}</span><button class="rename" id="rename" aria-label="${t('rename')}">${ICONS.edit}</button></div>
          <div class="climate">${climate}</div>
        </div>
        ${units.length < 2 ? gearButton() : ''}
        <button class="power ${u.power ? 'on' : ''}" id="power" ${off ? 'disabled' : ''} aria-label="${u.power ? t('turnOff') : t('turnOn')}" aria-pressed="${!!u.power}">${ICONS.power}</button>
      </div>

      <div class="temp">
        <button id="down" ${off || temp <= TEMP_MIN ? 'disabled' : ''} aria-label="${t('colder')}">−</button>
        <div class="setpoint"><b>${u.online ? fmt(temp) + '°' : '–'}</b><small>${u.power ? t('set') : t('off')}</small></div>
        <button id="up" ${off || temp >= TEMP_MAX ? 'disabled' : ''} aria-label="${t('warmer')}">+</button>
      </div>

      <div class="modes">
        ${['cool', 'heat'].map((m) => `
          <button data-mode="${m}" aria-pressed="${u.mode === m}" ${off ? 'disabled' : ''}>${ICONS[m]}${t(m)}</button>`).join('')}
      </div>
      ${u.online && u.mode !== 'cool' && u.mode !== 'heat' ? `<p class="other-mode">${t('currentMode', { mode: t(u.mode) })}</p>` : ''}
    </section>


    <section class="card">
      <h2>${t('autoOff')}</h2>
      ${timerFailed}
      ${u.offAt ? `
        <div class="timer-active">
          <div><b>${t('offAt', { time: offAtTime })}</b><div class="left">${remaining(u.offAt)}</div></div>
          <button class="cancel" id="cancel">${t('cancel')}</button>
        </div>` : u.power ? `
        <div class="chips">${TIMER_MINUTES.map((min) => `<button data-min="${min}" ${off ? 'disabled' : ''}>${timerLabel(min)}</button>`).join('')}</div>` : `
        <p class="hint">${t('turnOnForTimer')}</p>`}
    </section>

    ${airflowCard(u, off)}`;

  $('rename').onclick = () => rename(u);
  if ($('nameForm')) $('nameForm').onsubmit = (e) => {
    e.preventDefault();
    const name = $('nameInput').value.trim();
    if (name) change({ name }, t('saving'));
  };
  $('power').onclick = () => change({ power: !u.power }, u.power ? t('turnOff') : t('turnOn'));
  $('down').onclick = () => nudgeTemp(-0.5);
  $('up').onclick = () => nudgeTemp(0.5);
  document.querySelectorAll('.modes button').forEach((b) => b.onclick = () => {
    if (u.mode !== b.dataset.mode) change({ mode: b.dataset.mode }, t(b.dataset.mode));
  });
  wireAirflow(u, off);
  document.querySelectorAll('.chips button').forEach((b) => b.onclick = () => timer(Number(b.dataset.min)));
  if ($('cancel')) $('cancel').onclick = () => timer(null);
  fitAll();
}

function renderManage() {
  $('view').innerHTML = `
    <div class="manage-head">
      <h1>${t('manage')}</h1>
      ${units.length ? `<button class="btn" id="done">${t('done')}</button>` : ''}
    </div>
    <section class="card">
      ${units.length ? units.map((u, i) => `
        <div class="row">
          <div class="info"><b>${escape(displayName(u))}</b><small>${u.host} · ${u.online ? t('online') : t('unreachable')}</small><small>${t('mac', { mac: macLabel(u.airconId) })}</small></div>
          ${units.length > 1 ? `<div class="move">
            <button class="round" data-move="${u.airconId}" data-by="-1" aria-label="${escape(t('moveUp', { name: displayName(u) }))}" ${i === 0 ? 'disabled' : ''}>${ICONS.up}</button>
            <button class="round" data-move="${u.airconId}" data-by="1" aria-label="${escape(t('moveDown', { name: displayName(u) }))}" ${i === units.length - 1 ? 'disabled' : ''}>${ICONS.down}</button>
          </div>` : ''}
          <div class="actions">
            <button class="btn" data-rename="${u.airconId}">${t('rename')}</button>
            <button class="btn danger" data-forget="${u.airconId}">${t('forget')}</button>
          </div>
        </div>`).join('') : `<p class="hint">${t('noUnits')}</p>`}
      <div class="field"><button class="btn primary" id="scan">${t('searchNetwork')}</button></div>
    </section>
    <section class="card">
      <h2>${t('addByAddress')}</h2>
      <p class="hint">${t('addByAddressHint')}</p>
      <form class="field" id="addForm">
        <input type="text" id="addInput" inputmode="decimal" placeholder="192.168.1.50" autocomplete="off">
        <button class="btn">${t('add')}</button>
      </form>
    </section>`;

  if ($('done')) $('done').onclick = () => { screen = 'unit'; render(); };
  $('scan').onclick = async () => {
    setStatus(t('searching') + '…');
    try {
      units = await api('POST', '/api/scan');
      if (!unit()) selected = units[0]?.airconId ?? null;
      render();
      setStatus(t('searchResult', { n: units.length }));
    } catch (e) {
      setStatus(errorText(e), true);
    }
  };
  $('addForm').onsubmit = async (e) => {
    e.preventDefault();
    setStatus(t('searching') + '…');
    try {
      const { warning, ...added } = await api('POST', '/api/units', { host: $('addInput').value.trim() });
      units = units.filter((u) => u.airconId !== added.airconId).concat(added);
      selected = added.airconId;
      screen = 'unit';
      render();
      setStatus(warning ? errorText(warning) : '', !!warning);
    } catch (e) {
      setStatus(errorText(e), true);
    }
  };
  document.querySelectorAll('[data-move]').forEach((b) => b.onclick = async () => {
    const order = units.map((u) => u.airconId);
    const from = order.indexOf(b.dataset.move);
    const to = from + Number(b.dataset.by);
    if (from < 0 || to < 0 || to >= order.length) return;
    order.splice(to, 0, order.splice(from, 1)[0]);
    try {
      units = await api('PUT', '/api/units/order', { order });
      render();
      // Keep the focus on the moved unit's arrow, so moving it further is another press.
      document.querySelector(`[data-move="${b.dataset.move}"][data-by="${b.dataset.by}"]:not(:disabled)`)?.focus();
    } catch (e) {
      setStatus(errorText(e), true);
    }
  });
  document.querySelectorAll('[data-rename]').forEach((b) => b.onclick = () => rename(units.find((u) => u.airconId === b.dataset.rename)));
  document.querySelectorAll('[data-forget]').forEach((b) => b.onclick = async () => {
    const u = units.find((x) => x.airconId === b.dataset.forget);
    if (!confirm(t('forgetConfirm', { name: displayName(u) }))) return;
    try {
      await api('DELETE', `/api/units/${u.airconId}`);
      units = units.filter((x) => x !== u);
      if (selected === u.airconId) selected = units[0]?.airconId ?? null;
      render();
    } catch (e) {
      setStatus(errorText(e), true);
    }
  });
}

// The gear is drawn with the tiles, or on the unit card when there is only one unit.
document.addEventListener('click', (e) => {
  if (e.target.closest('[data-manage]')) { screen = 'manage'; render(); }
});
addEventListener('resize', () => {
  renderTiles();
  fitAll();
});

$('units').addEventListener('click', (e) => {
  const b = e.target.closest('button[data-id]');
  if (!b) return;
  selected = b.dataset.id;
  pendingTemp = null;
  try { localStorage.setItem('airlan.selected', selected); } catch {}
  render();
});

await loadMessages();
refresh();
setInterval(() => { if (!document.hidden && pendingTemp == null) refresh(); }, 10000);
document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
