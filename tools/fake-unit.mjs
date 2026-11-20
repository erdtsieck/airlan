// A stand-in for a WF-RAC module, for developing without an air conditioner.
//
//   node tools/fake-unit.mjs [--host 0.0.0.0] [--port 51443] [--id fa4e00000001]
//                            [--power on|off] [--mode cool|heat|auto|fan|dry] [--temp 21]
//                            [--indoor 23.5] [--outdoor 14.5]
//                            [--accounts-taken 0..4] [--other-writer-every <seconds>] [--mdns]
//                            [--model 0|1|2|3|64] [--legacy]
//                            [--answer-delay <ms>] [--stall-every <s> --stall-for <s>]
//                            [--no-silent]
//
// Answers getDeviceInfo, getAirconStat, setAirconStat, updateAccountInfo and
// deleteAccountInfo over plain HTTP like the HTTP firmware does, keeps the state across
// requests and logs every change. Like the real module it
//  - refuses writes from operators that did not register first (result 2);
//  - holds four account slots and refuses a new account when they are full
//    (--accounts-taken fills slots with other apps' accounts); deleting an account it does
//    not hold answers result 1;
//  - gives the last writer 60 seconds of exclusive write access, keyed on deviceId, and
//    reports when that lapses as `expires` (--other-writer-every simulates another app
//    that writes on a cadence);
//  - refuses request bodies with "\/" escapes, which the real parser does not undo;
//  - with --mdns, answers mDNS queries for _beaver._tcp.local.
//  - --model sets the model group byte (64: a ceiling cassette, no left/right vane);
//    --legacy reports byte 12 pinned to 1, as a unit with the legacy bus signature does:
//    it takes left/right vane and 3D auto commands but never reports them back.
//  - --answer-delay answers every request that much later, like a unit with a weak Wi-Fi
//    signal; --stall-every/--stall-for make it accept connections and not answer for a while
//    (busy or stuck), as the real module does around its hourly Wi-Fi reassociation. Anything
//    that is not HTTP, such as a TLS handshake, is logged: a plain-HTTP module should never
//    see one once its scheme is known.
//  - knows silent operation, the one piece of operation data AirLAN uses: a frame carrying
//    segments instead of a state write (code 0x21 sets it, code 0xDD reads it back) leaves
//    the state alone; like the real module it answers the write with result 11 although it
//    applies it. --no-silent refuses both codes, as a unit without silent operation does.
// Point AirLAN at it by IP address (from the Android emulator: 10.0.2.2).
//
// The tests use startFakeUnit() directly.

import http from 'node:http';
import dgram from 'node:dgram';
import os from 'node:os';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { crc16, decode, encode, MODE_NAMES, ACCOUNT_SLOTS, WRITE_LOCK_S } from '../wfrac.js';
import { indoorTemp, outdoorTemp } from '../temp-tables.js';
import { parseMessage, encodeAnnouncement, SERVICE } from '../mdns.js';

const nearest = (table, t) => table.reduce((best, v, i) => (Math.abs(v - t) < Math.abs(table[best] - t) ? i : best), 0);
const describe = (s) =>
  `${s.power ? 'ON ' : 'off'} ${MODE_NAMES[s.mode]} ${s.presetTemp}° fan ${s.airFlow} vanes ${s.entrust ? '3D auto' : `${s.windUD}/${s.windLR}`}`;

/**
 * Starts a fake unit. Returns `{ server, port, unit }`, where `unit` exposes the simulated
 * module for tests: `state`, `accounts`, `lock` ({ writer, expires }), `requests`, and
 * `otherWrite()` to let another app write (and take the lock).
 */
export function startFakeUnit({
  host = '127.0.0.1',
  port = 0,
  airconId = 'fa4e00000001',
  indoor = 23.5,
  outdoor = 14.5,
  accountsTaken = 0,
  legacy = false,
  model,
  answerDelayMs = 0,
  silentSupported = true,
  state: initial = {},
  log = () => {},
} = {}) {
  // Start from a frame captured from a real unit (switched off, cooling, 21 °C).
  const live = JSON.parse(readFileSync(new URL('../test/fixtures/live-frames.json', import.meta.url)));
  const unit = {
    state: { ...decode(live.frames.e81656c09e49.airconStat), ...(model !== undefined && { modelNrRaw: model, modelNr: [0, 1, 2].indexOf(model === 3 ? 2 : model) }), ...initial },
    accounts: new Set(Array.from({ length: accountsTaken }, (_, i) => `other-app-${i + 1}`)),
    lock: { writer: null, expires: 0 },
    clock: Math.round(Date.now() / 1000),
    requests: [],
    silent: false,
    /** Connections accepted, and those that were not HTTP (a TLS handshake, say). */
    connections: 0,
    notHttp: 0,
    stalledUntil: 0,
    /** Accepts connections but answers none for [seconds]. */
    stall(seconds) {
      unit.stalledUntil = Date.now() + seconds * 1000;
      log(`stalling for ${seconds} s: connections are accepted, nothing is answered`);
    },
    otherWrite() {
      unit.lock = { writer: 'other-app', expires: unit.clock + WRITE_LOCK_S };
      log('another app wrote: locked for 60 s');
    },
  };

  /** The COMMAND block's segments, or null for the "nothing to send" sentinel of a state write. */
  function commandSegments(airconStat) {
    const d = [...Buffer.from(airconStat, 'base64')];
    const segments = [];
    for (let i = 0; i < d[18]; i++) segments.push(d.slice(19 + i * 4, 23 + i * 4));
    return segments.length === 1 && segments[0].every((b) => b === 0xff) ? null : segments;
  }

  /** encode(), plus the indoor/outdoor temperature segments a real unit reports, and [answers]. */
  function frame(s, answers = []) {
    const bytes = [...Buffer.from(encode(s), 'base64')];
    const command = bytes.slice(0, 25); // 18-byte block + 1 segment (5 bytes) + CRC
    const state = bytes.slice(25, 25 + 18);
    if (legacy) state[12] = 1;
    const receive = [
      ...state,
      2 + answers.length, 0x80, 0x20, nearest(indoorTemp, indoor), 0, 0x80, 0x10, nearest(outdoorTemp, outdoor), 0,
      ...answers.flat(),
    ];
    const crc = crc16(receive);
    return Buffer.from([...command, ...receive, crc & 0xff, crc >> 8]).toString('base64');
  }

  const server = http.createServer((req, res) => {
    let raw = '';
    req.on('data', (c) => (raw += c));
    req.on('end', async () => {
      if (Date.now() < unit.stalledUntil) return; // Never answers; the client gives up.
      if (answerDelayMs) await new Promise((r) => setTimeout(r, answerDelayMs));
      let payload;
      try {
        payload = JSON.parse(raw);
      } catch {
        return res.writeHead(501).end('Not supported this command');
      }
      const reply = (result, contents) => {
        const body = JSON.stringify({ ...payload, result, ...(contents ? { contents } : {}) });
        res.writeHead(200, { 'Content-Type': 'application/json' }).end(body);
      };
      const { command, operatorId, deviceId, timestamp, contents = {} } = payload;
      unit.requests.push(payload);
      // The module has no clock of its own: every request sets it.
      if (timestamp) unit.clock = timestamp;
      const stat = (answers) => ({
        airconId,
        airconStat: frame(unit.state, answers),
        updatedBy: 'local',
        expires: unit.lock.expires,
        firmType: 'WF-RAC',
        wireless: { firmVer: '010' },
        mcu: { firmVer: '131' },
        numOfAccount: unit.accounts.size,
      });

      switch (command) {
        case 'getDeviceInfo':
          return reply(0, { airconId, macAddress: airconId, apMode: 0 });
        case 'updateAccountInfo':
          if (!unit.accounts.has(contents.accountId) && unit.accounts.size >= ACCOUNT_SLOTS) {
            log(`refused account ${contents.accountId}: all ${ACCOUNT_SLOTS} slots taken`);
            return reply(1);
          }
          unit.accounts.add(contents.accountId);
          log(`registered ${contents.accountId} (${unit.accounts.size}/${ACCOUNT_SLOTS})`);
          return reply(0);
        case 'deleteAccountInfo':
          if (!unit.accounts.delete(contents.accountId)) return reply(1);
          log(`deleted account ${contents.accountId} (${unit.accounts.size}/${ACCOUNT_SLOTS})`);
          return reply(0);
        case 'getAirconStat':
          return reply(0, stat());
        case 'setAirconStat': {
          if (operatorId !== 'aws' && !unit.accounts.has(operatorId)) {
            log(`refused write: ${operatorId} is not registered`);
            return reply(2, stat());
          }
          if (raw.includes('\\/')) {
            log('refused write: escaped slashes in the request body');
            return reply(2, stat());
          }
          if (unit.lock.writer && unit.lock.expires >= unit.clock && unit.lock.writer !== deviceId) {
            log(`refused write from ${deviceId}: locked by ${unit.lock.writer} for ${unit.lock.expires - unit.clock} s`);
            return reply(1, stat());
          }
          const segments = commandSegments(contents.airconStat);
          if (segments) {
            if (!silentSupported || segments.some(([code]) => code !== 0x21 && code !== 0xdd)) {
              log(`refused operation data ${segments.map(([code]) => code.toString(16)).join(', ')}: unknown code`);
              return reply(11, stat());
            }
            unit.lock = { writer: deviceId, expires: unit.clock + WRITE_LOCK_S };
            const write = segments.find(([code]) => code === 0x21);
            if (write) {
              unit.silent = write[1] === 1;
              log(`silent operation ${unit.silent ? 'on' : 'off'}`);
              return reply(11, stat()); // Applied all the same, as the real module does.
            }
            return reply(0, stat([[0xdd, 0x80, unit.silent ? 0x20 : 0, 0]]));
          }
          const before = describe(unit.state);
          unit.state = { ...decode(contents.airconStat), indoorTemp: unit.state.indoorTemp, outdoorTemp: unit.state.outdoorTemp };
          unit.lock = { writer: deviceId, expires: unit.clock + WRITE_LOCK_S };
          log(`${before} -> ${describe(unit.state)}`);
          return reply(0, stat());
        }
        default:
          return res.writeHead(501).end('Not supported this command');
      }
    });
  });
  server.on('connection', () => unit.connections++);
  server.on('clientError', (e, socket) => {
    unit.notHttp++;
    log(`a connection that is not HTTP (${e.code}): a TLS handshake to a plain-HTTP module?`);
    socket.destroy();
  });

  return new Promise((resolve) => server.listen(port, host, () => resolve({ server, port: server.address().port, unit })));
}

/** Answers mDNS queries for the service like a module does, announcing `address`. */
function respondToMdns(airconId, address, port, log) {
  const socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  socket.on('message', (msg, from) => {
    let asks = false;
    try {
      const m = parseMessage(msg);
      asks = !m.response && m.questions.some((q) => q.name === SERVICE || q.name === `${airconId}.local`);
    } catch {
      return;
    }
    if (!asks) return;
    const answer = encodeAnnouncement({ instance: airconId, address, port });
    // Unicast to a querier on another port (legacy query), otherwise to the group.
    if (from.port !== 5353) socket.send(answer, from.port, from.address);
    else socket.send(answer, 5353, '224.0.0.251');
    log(`mDNS: announced ${airconId}.${SERVICE} at ${address}:${port} to ${from.address}`);
  });
  socket.bind(5353, () => {
    for (const a of Object.values(os.networkInterfaces()).flat()) {
      if (a?.family === 'IPv4' && !a.internal) {
        try { socket.addMembership('224.0.0.251', a.address); } catch { /* no multicast here */ }
      }
    }
  });
  return socket;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const arg = (name, fallback) => {
    const i = process.argv.indexOf(`--${name}`);
    return i > 0 ? process.argv[i + 1] : fallback;
  };
  const flag = (name) => process.argv.includes(`--${name}`);
  const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
  const initial = {};
  if (arg('power')) initial.power = arg('power') === 'on';
  if (arg('mode')) initial.mode = MODE_NAMES.indexOf(arg('mode'));
  if (arg('temp')) initial.presetTemp = Number(arg('temp'));
  const airconId = arg('id', 'fa4e00000001');
  const { port, unit } = await startFakeUnit({
    host: arg('host', '0.0.0.0'),
    port: Number(arg('port', 51443)),
    airconId,
    indoor: Number(arg('indoor', 23.5)),
    outdoor: Number(arg('outdoor', 14.5)),
    accountsTaken: Number(arg('accounts-taken', 0)),
    legacy: flag('legacy'),
    model: arg('model') === undefined ? undefined : Number(arg('model')),
    answerDelayMs: Number(arg('answer-delay', 0)),
    silentSupported: !flag('no-silent'),
    state: initial,
    log,
  });
  log(`fake unit ${airconId} on port ${port}: ${describe(unit.state)}, ${unit.accounts.size}/${ACCOUNT_SLOTS} accounts taken`);
  const stallEvery = Number(arg('stall-every', 0));
  if (stallEvery > 0) setInterval(() => unit.stall(Number(arg('stall-for', 30))), stallEvery * 1000);
  const every = Number(arg('other-writer-every', 0));
  if (every > 0) setInterval(() => { unit.clock = Math.round(Date.now() / 1000); unit.otherWrite(); }, every * 1000);
  if (flag('mdns')) {
    const address = Object.values(os.networkInterfaces()).flat().find((a) => a?.family === 'IPv4' && !a.internal)?.address;
    if (address) respondToMdns(airconId, address, port, log);
  }
}
