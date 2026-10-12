// AirLAN server: talks directly to WF-RAC modules on the local network and serves a
// mobile web app. No cloud, no dependencies.
//
//   node server.js    (AIRLAN_PORT, default 8321; AIRLAN_DATA_DIR, default ./data)

import http from 'node:http';
import os from 'node:os';
import { randomUUID } from 'node:crypto';
import { readFile, writeFile, rename, mkdir } from 'node:fs/promises';
import { dirname, join, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { WfRacClient, WfRacError, MODE_NAMES, PORT as WFRAC_PORT } from './wfrac.js';
import { hostsToScan, findOpen, lanAddresses, isIpv4 } from './discovery.js';
import { browse } from './mdns.js';
import { TIMER_GIVE_UP_MS, timerRetryDelayMs } from './timer-policy.js';

const ROOT = dirname(fileURLToPath(import.meta.url));
const DATA_FILE = join(process.env.AIRLAN_DATA_DIR ?? join(ROOT, 'data'), 'state.json');
const PUBLIC = join(ROOT, 'public');
const HTTP_PORT = Number(process.env.AIRLAN_PORT ?? 8321);
const TIMEZONE = Intl.DateTimeFormat().resolvedOptions().timeZone;
const TEMP_MIN = 16;
const TEMP_MAX = 30;
const TIMER_MAX_MINUTES = 6 * 60;
const STAT_CACHE_MS = 4000;

const log = (...args) => console.log(new Date().toISOString(), ...args);

// ---------- persistent state ----------

/**
 * deviceId is this installation's own: the unit's write lock tells writers apart by it. The
 * account (operatorId) is AirLAN's shared one, see AIRLAN_OPERATOR_ID. legacyOperatorId is
 * the account of its own that AirLAN 0.1.1 and older registered; units are moved off it
 * (accountMigrated) as they are reached, see migrate().
 * @type {{ deviceId: string, legacyOperatorId?: string, units: {airconId: string, name: string | null, host: string, scheme?: string, offAt?: number, timerFailedAt?: number, accountMigrated?: boolean}[] }}
 */
let store;

async function loadStore() {
  try {
    store = JSON.parse(await readFile(DATA_FILE, 'utf8'));
  } catch (e) {
    if (e.code !== 'ENOENT') throw e;
    store = { deviceId: `airlan-${randomUUID().slice(0, 8)}`, units: [] };
  }
  if (store.operatorId) {
    store.legacyOperatorId ??= store.operatorId;
    delete store.operatorId;
  }
}

let saving = Promise.resolve();
function saveStore() {
  const snapshot = JSON.stringify(store, null, 2);
  saving = saving.then(async () => {
    await mkdir(dirname(DATA_FILE), { recursive: true });
    await writeFile(DATA_FILE + '.tmp', snapshot);
    await rename(DATA_FILE + '.tmp', DATA_FILE);
  });
  return saving;
}

// ---------- discovery ----------

let lastScan = 0;
let scanning = null;

/**
 * Adds the module at `host`, or updates the address of a known one (DHCP may move it).
 * New units get no name: the app asks the user for one.
 */
async function adopt(host) {
  const probe = new WfRacClient({ host, ...ids() });
  const { airconId } = await probe.getDeviceInfo();
  let unit = store.units.find((u) => u.airconId === airconId);
  if (!unit) {
    unit = { airconId, name: null, host };
    store.units.push(unit);
    log(`new unit ${airconId} at ${host} (${probe.scheme})`);
  } else if (unit.host !== host) {
    log(`unit ${airconId} moved ${unit.host} -> ${host}`);
    unit.host = host;
  }
  unit.scheme = probe.scheme;
  clients.delete(airconId);
  return unit;
}

/** Modules that announce themselves by mDNS: `[{ airconId, address }]`. Quick, but not every module answers every time. */
const announced = () => browse(lanAddresses(os.networkInterfaces()));

/**
 * Looks for WF-RAC modules: by mDNS, and by probing every address on the local network for
 * the module's port, as not every module answers mDNS reliably.
 */
function scan() {
  if (scanning) return scanning;
  scanning = (async () => {
    const [byMdns, open] = await Promise.all([announced(), findOpen(hostsToScan(os.networkInterfaces()))]);
    for (const host of new Set([...byMdns.map((m) => m.address), ...open])) {
      try {
        await adopt(host);
      } catch (e) {
        log(`${host}:${WFRAC_PORT} is not a WF-RAC module (${e.message})`);
      }
    }
    lastScan = Date.now();
    await saveStore();
  })().finally(() => { scanning = null; });
  return scanning;
}

// ---------- talking to units ----------

const ids = () => ({ deviceId: store.deviceId });
const clients = new Map();
const cache = new Map(); // airconId -> { at, contents, state }

function clientFor(unit) {
  let c = clients.get(unit.airconId);
  if (!c || c.host !== unit.host) {
    c = new WfRacClient({ host: unit.host, scheme: unit.scheme, ...ids() });
    clients.set(unit.airconId, c);
  }
  return c;
}

/**
 * Looks for a unit that stopped answering, in case DHCP moved it: asks mDNS first, which
 * names the unit directly, and falls back to a full scan (at most once a minute).
 * Returns whether the unit is now known at a different address.
 */
async function relocate(unit) {
  const before = unit.host;
  const found = (await announced()).find((m) => m.airconId === unit.airconId);
  if (found && found.address !== before) {
    await adopt(found.address).catch(() => {});
    await saveStore();
  } else if (!found && Date.now() - lastScan >= 60_000) {
    await scan();
  }
  return unit.host !== before;
}

/** Runs an action on a unit; on a network failure, looks for the unit and retries if it moved. */
async function withUnit(unit, fn) {
  const client = clientFor(unit);
  try {
    return await fn(client);
  } catch (e) {
    if (e instanceof WfRacError) throw e;
    log(`unit ${unit.airconId} unreachable at ${unit.host}, looking for it`);
    if (!(await relocate(unit))) throw e;
    return fn(clientFor(unit));
  } finally {
    if (client.scheme && client.scheme !== unit.scheme) {
      unit.scheme = client.scheme;
      saveStore();
    }
  }
}

async function readStat(unit) {
  const hit = cache.get(unit.airconId);
  if (hit && Date.now() - hit.at < STAT_CACHE_MS) return hit;
  const { state } = await withUnit(unit, (c) => c.getStat(unit.airconId));
  const entry = { at: Date.now(), state };
  cache.set(unit.airconId, entry);
  return entry;
}

/**
 * Registers our account with a unit the user claims (adds or names), so a unit without a
 * free account slot shows up then and not at the first switch. Registering again is
 * idempotent: the account keeps its slot. Returns a warning for a full unit; any other
 * failure is left for the first write, which registers too.
 */
async function registerWarning(unit) {
  try {
    await (needsMigration(unit) ? migrate(unit) : withUnit(unit, (c) => c.registerAccount(unit.airconId, TIMEZONE)));
    return null;
  } catch (e) {
    return e.code === 'no_account_slot' ? { code: e.code, message: e.message } : null;
  }
}

/** Writes a change; waits up to `waitUpToS` for another client's write lock (see WfRacClient.change). */
async function applyChange(unit, change, { waitUpToS = 20 } = {}) {
  await migrate(unit);
  const state = await withUnit(unit, (c) => c.change(unit.airconId, change, { timezone: TIMEZONE, waitUpToS }));
  cache.set(unit.airconId, { at: Date.now(), state });
  log(`${unit.name}: ${JSON.stringify(change)}`);
}

// ---------- off timers ----------

const timers = new Map();

function scheduleTimer(unit) {
  clearTimeout(timers.get(unit.airconId));
  timers.delete(unit.airconId);
  if (!unit.offAt) return;
  // A timer that expired while the server was down fires right away.
  timers.set(unit.airconId, setTimeout(() => fireTimer(unit), Math.max(0, unit.offAt - Date.now())));
}

/**
 * Switches the unit off when its timer expires. Failures are retried (see timer-policy.js)
 * until TIMER_GIVE_UP_MS after the switch-off time; a write lock held by another client is
 * waited out. Then the timer is dropped and marked failed, for the app to show.
 */
async function fireTimer(unit, failed = 0) {
  const offAt = unit.offAt;
  try {
    await applyChange(unit, { power: false }, { waitUpToS: 0 });
    log(`${unit.name}: timer expired, switched off`);
    if (unit.offAt === offAt) delete unit.offAt;
    timers.delete(unit.airconId);
    await saveStore();
  } catch (e) {
    if (unit.offAt !== offAt) return; // Cancelled or replaced meanwhile.
    const delay = Math.max(timerRetryDelayMs(failed + 1), ((e.retryAfterS ?? 0) + 2) * 1000);
    if (Date.now() + delay <= offAt + TIMER_GIVE_UP_MS) {
      log(`${unit.name}: timer could not switch off (${e.message}), retrying in ${Math.round(delay / 1000)} s`);
      timers.set(unit.airconId, setTimeout(() => fireTimer(unit, failed + 1), delay));
    } else {
      log(`${unit.name}: timer could not switch off (${e.message}), giving up`);
      delete unit.offAt;
      unit.timerFailedAt = offAt;
      timers.delete(unit.airconId);
      await saveStore();
    }
  }
}

async function setTimer(unit, minutes) {
  delete unit.timerFailedAt;
  if (minutes == null) delete unit.offAt;
  else unit.offAt = Date.now() + minutes * 60_000;
  scheduleTimer(unit);
  await saveStore();
}

// ---------- HTTP ----------

// Error codes are part of the API: the web app translates them. unit_busy also says when
// the unit is free again (retryAfterS).
class HttpError extends Error {
  constructor(status, code, message, extra = {}) { super(message); this.status = status; this.code = code; this.extra = extra; }

  get body() { return { code: this.code, message: this.message, ...this.extra }; }
}

function toHttpError(e) {
  if (e instanceof HttpError) return e;
  if (e instanceof WfRacError) return new HttpError(502, e.code, e.message, e.retryAfterS != null ? { retryAfterS: e.retryAfterS } : {});
  return new HttpError(502, 'unreachable', e.message);
}

function parseChange(body) {
  const change = {};
  if ('power' in body) {
    if (typeof body.power !== 'boolean') throw new HttpError(400, 'invalid', 'power must be true or false');
    change.power = body.power;
  }
  if ('mode' in body) {
    const mode = MODE_NAMES.indexOf(body.mode);
    if (mode < 0) throw new HttpError(400, 'invalid', `mode must be one of ${MODE_NAMES.join(', ')}`);
    change.mode = mode;
  }
  if ('presetTemp' in body) {
    const t = Math.round(Number(body.presetTemp) * 2) / 2;
    if (!(t >= TEMP_MIN && t <= TEMP_MAX)) throw new HttpError(400, 'invalid', `presetTemp must be between ${TEMP_MIN} and ${TEMP_MAX}`);
    change.presetTemp = t;
  }
  return change;
}

/**
 * Moves a unit off the account AirLAN 0.1.1 and older registered per installation to the
 * shared one (WfRacClient.migrateAccount), once. Registering goes through here until then,
 * so that the old slot is freed before the shared account needs one. Named units are also
 * moved in the background whenever they are reached (see unitView).
 */
const migrations = new Map();
function migrate(unit) {
  if (!needsMigration(unit)) return Promise.resolve();
  if (!migrations.has(unit.airconId)) {
    const run = clientFor(unit)
      .migrateAccount(unit.airconId, store.legacyOperatorId, TIMEZONE)
      .then(
        () => {
          unit.accountMigrated = true;
          log(`${unit.name ?? unit.airconId}: moved to the shared AirLAN account`);
          return saveStore();
        },
        (e) => {
          log(`${unit.name ?? unit.airconId}: could not move to the shared AirLAN account yet (${e.message})`);
          throw e;
        },
      )
      .finally(() => migrations.delete(unit.airconId));
    migrations.set(unit.airconId, run);
  }
  return migrations.get(unit.airconId);
}

const needsMigration = (unit) => !!store.legacyOperatorId && !unit.accountMigrated;

async function unitView(unit) {
  const base = { airconId: unit.airconId, name: unit.name, host: unit.host, offAt: unit.offAt ?? null, timerFailedAt: unit.timerFailedAt ?? null };
  try {
    const { state } = await readStat(unit);
    if (unit.name) migrate(unit).catch(() => {});
    return {
      ...base,
      online: true,
      power: state.power,
      mode: MODE_NAMES[state.mode] ?? 'unknown',
      presetTemp: state.presetTemp,
      indoorTemp: state.indoorTemp,
      outdoorTemp: state.outdoorTemp,
      errorCode: state.errorCode,
    };
  } catch (e) {
    const err = toHttpError(e);
    return { ...base, online: false, error: err.body };
  }
}

async function readJson(req) {
  let raw = '';
  for await (const chunk of req) {
    raw += chunk;
    if (raw.length > 10_000) throw new HttpError(413, 'invalid', 'request body too large');
  }
  try { return raw ? JSON.parse(raw) : {}; } catch { throw new HttpError(400, 'invalid', 'invalid JSON'); }
}

const CONTENT_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.json': 'application/manifest+json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
};

async function serveStatic(res, path) {
  const file = path === '/' ? 'index.html' : path.slice(1);
  if (!/^(locales\/)?\w[\w.-]*$/.test(file)) throw new HttpError(404, 'not_found', 'not found');
  let body;
  try {
    body = await readFile(join(PUBLIC, file));
  } catch {
    throw new HttpError(404, 'not_found', 'not found');
  }
  res.writeHead(200, { 'Content-Type': CONTENT_TYPES[extname(file)] ?? 'application/octet-stream', 'Cache-Control': 'no-cache' });
  res.end(body);
}

function findUnit(id) {
  const unit = store.units.find((u) => u.airconId === id);
  if (!unit) throw new HttpError(404, 'not_found', 'unknown unit');
  return unit;
}

async function route(req, res) {
  const { pathname } = new URL(req.url, 'http://x');
  const send = (status, body) => {
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };

  if (pathname === '/api/units' && req.method === 'GET') {
    return send(200, await Promise.all(store.units.map(unitView)));
  }
  if (pathname === '/api/scan' && req.method === 'POST') {
    await scan();
    return send(200, await Promise.all(store.units.map(unitView)));
  }
  if (pathname === '/api/units' && req.method === 'POST') {
    const { host } = await readJson(req);
    if (!isIpv4(String(host))) throw new HttpError(400, 'invalid', 'host must be an IPv4 address');
    let unit;
    try {
      unit = await adopt(host);
    } catch (e) {
      throw e instanceof WfRacError ? e : new HttpError(502, 'no_unit_at_address', `no WF-RAC module answers at ${host}`);
    }
    await saveStore();
    const warning = await registerWarning(unit);
    return send(200, { ...(await unitView(unit)), ...(warning && { warning }) });
  }

  const m = pathname.match(/^\/api\/units\/([0-9a-f]{12})(\/timer)?$/);
  if (m) {
    const unit = findUnit(m[1]);
    if (!m[2] && req.method === 'DELETE') {
      // The account stays: it is shared with every other AirLAN client of this unit.
      await setTimer(unit, null);
      store.units.splice(store.units.indexOf(unit), 1);
      clients.delete(unit.airconId);
      cache.delete(unit.airconId);
      await saveStore();
      log(`forgot unit ${unit.airconId}`);
      res.writeHead(204).end();
      return;
    }
    if (!m[2] && req.method === 'PATCH') {
      const body = await readJson(req);
      let warning = null;
      if ('name' in body) {
        const name = String(body.name).trim().slice(0, 40);
        if (!name) throw new HttpError(400, 'invalid', 'name must not be empty');
        unit.name = name;
        await saveStore();
        warning = await registerWarning(unit);
      }
      const change = parseChange(body);
      if (Object.keys(change).length) {
        await applyChange(unit, change);
        delete unit.timerFailedAt;
        // Switching off makes a pending timer pointless.
        if (change.power === false && unit.offAt) await setTimer(unit, null);
        await saveStore();
      }
      return send(200, { ...(await unitView(unit)), ...(warning && { warning }) });
    }
    if (m[2] && req.method === 'PUT') {
      const { minutes } = await readJson(req);
      if (!(Number.isInteger(minutes) && minutes > 0 && minutes <= TIMER_MAX_MINUTES)) {
        throw new HttpError(400, 'invalid', `minutes must be an integer between 1 and ${TIMER_MAX_MINUTES}`);
      }
      await setTimer(unit, minutes);
      return send(200, await unitView(unit));
    }
    if (m[2] && req.method === 'DELETE') {
      await setTimer(unit, null);
      return send(200, await unitView(unit));
    }
  }

  if (req.method === 'GET' && !pathname.startsWith('/api/')) return serveStatic(res, pathname);
  throw new HttpError(404, 'not_found', 'not found');
}

await loadStore();
if (!store.units.length) {
  log('no units known yet, scanning the network…');
  scan().then(() => log(`scan found ${store.units.length} unit(s)`));
}
log(`${store.units.length} unit(s): ${store.units.map((u) => `${u.name ?? u.airconId} (${u.host})`).join(', ')}`);
for (const unit of store.units) scheduleTimer(unit);
await saveStore();

http
  .createServer((req, res) => {
    route(req, res).catch((e) => {
      const err = toHttpError(e);
      if (err.status >= 500) log(`${req.method} ${req.url}: ${e instanceof WfRacError ? e.message : e.stack ?? e.message}`);
      res.writeHead(err.status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: err.body }));
    });
  })
  .listen(HTTP_PORT, () => {
    const urls = [`http://localhost:${HTTP_PORT}`, ...lanAddresses(os.networkInterfaces()).map((a) => `http://${a}:${HTTP_PORT}`)];
    log(`AirLAN running: ${urls.join('  ')}`);
  });
