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
  edit: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 20h4L19 9l-4-4L4 16z"/></svg>',
};
const TIMER_MINUTES = [30, 60, 120, 180, 240, 300, 360];
const TEMP_MIN = 16, TEMP_MAX = 30;

let units = [];
let selected = null;
try { selected = localStorage.getItem('airlan.selected'); } catch {}
let pendingTemp = null, tempTimer = null, busy = false;

const $ = (id) => document.getElementById(id);
const fmt = (n) => n == null ? '–' : n.toLocaleString(locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const unit = () => units.find((u) => u.airconId === selected);
const errorText = (err) => messages[`error.${err?.code}`] ?? err?.message ?? t('error.server');
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
  if (busy) return;
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

async function change(body, label) {
  const u = unit();
  busy = true;
  setStatus(label + '…');
  try {
    Object.assign(u, await api('PATCH', `/api/units/${u.airconId}`, body));
    setStatus('');
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

function rename() {
  const u = unit();
  const name = prompt(t('renamePrompt'), u.name);
  if (name && name.trim() && name.trim() !== u.name) change({ name: name.trim() }, t('saving'));
}

function remaining(offAt) {
  const min = Math.max(0, Math.ceil((offAt - Date.now()) / 60000));
  const h = Math.floor(min / 60);
  return h ? t('remainingHours', { h, m: min % 60 }) : t('remainingMinutes', { m: min });
}

function escape(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

function render() {
  $('units').innerHTML = units.map((u) => `
    <button role="tab" aria-selected="${u.airconId === selected}" data-id="${u.airconId}">
      <span class="dot ${u.power ? u.mode : ''}"></span>${escape(u.name)}
    </button>`).join('');
  $('units').hidden = units.length < 2;

  const u = unit();
  if (!u) {
    $('view').innerHTML = `<div class="empty">${t('noUnits')}<br><button id="scan">${t('searchAgain')}</button></div>`;
    $('scan').onclick = async () => {
      setStatus(t('searching') + '…');
      try {
        units = await api('POST', '/api/scan');
        selected = units[0]?.airconId;
        render();
        setStatus('');
      } catch (e) {
        setStatus(errorText(e), true);
      }
    };
    return;
  }

  const accent = u.power && (u.mode === 'cool' || u.mode === 'heat') ? `var(--${u.mode})` : 'var(--off)';
  document.documentElement.style.setProperty('--accent', accent);
  const off = !u.online || busy;
  const temp = pendingTemp ?? u.presetTemp;
  const climate = u.online
    ? `${t('indoor')} ${fmt(u.indoorTemp)}° · ${t('outdoor')} ${fmt(u.outdoorTemp)}°${u.errorCode ? ` · ${t('fault')} ${u.errorCode}` : ''}`
    : t('unreachable');
  const offAtTime = u.offAt && new Date(u.offAt).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' });

  $('view').innerHTML = `
    <section class="card">
      <div class="head">
        <div style="min-width:0">
          <div class="name"><span>${escape(u.name)}</span><button class="rename" id="rename" aria-label="${t('rename')}">${ICONS.edit}</button></div>
          <div class="climate">${climate}</div>
        </div>
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
      ${u.offAt ? `
        <div class="timer-active">
          <div><b>${t('offAt', { time: offAtTime })}</b><div class="left">${remaining(u.offAt)}</div></div>
          <button class="cancel" id="cancel">${t('cancel')}</button>
        </div>` : u.power ? `
        <div class="chips">${TIMER_MINUTES.map((min) => `<button data-min="${min}" ${off ? 'disabled' : ''}>${timerLabel(min)}</button>`).join('')}</div>` : `
        <p class="hint">${t('turnOnForTimer')}</p>`}
    </section>`;

  $('rename').onclick = rename;
  $('power').onclick = () => change({ power: !u.power }, u.power ? t('turnOff') : t('turnOn'));
  $('down').onclick = () => nudgeTemp(-0.5);
  $('up').onclick = () => nudgeTemp(0.5);
  document.querySelectorAll('.modes button').forEach((b) => b.onclick = () => {
    if (u.mode !== b.dataset.mode) change({ mode: b.dataset.mode }, t(b.dataset.mode));
  });
  document.querySelectorAll('.chips button').forEach((b) => b.onclick = () => timer(Number(b.dataset.min)));
  if ($('cancel')) $('cancel').onclick = () => timer(null);
}

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
