// A stand-in for a WF-RAC module, for developing without an air conditioner.
//
//   node tools/fake-unit.mjs [--host 0.0.0.0] [--port 51443] [--id fa4e00000001]
//                            [--power on|off] [--mode cool|heat|auto|fan|dry] [--temp 21]
//                            [--indoor 23.5] [--outdoor 14.5]
//                            [--accounts-taken 0..4] [--other-writer-every <seconds>] [--mdns]
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
const describe = (s) => `${s.power ? 'ON ' : 'off'} ${MODE_NAMES[s.mode]} ${s.presetTemp}°`;

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
  state: initial = {},
  log = () => {},
} = {}) {
  // Start from a frame captured from a real unit (switched off, cooling, 21 °C).
  const live = JSON.parse(readFileSync(new URL('../test/fixtures/live-frames.json', import.meta.url)));
  const unit = {
    state: { ...decode(live.frames.e81656c09e49.airconStat), ...initial },
    accounts: new Set(Array.from({ length: accountsTaken }, (_, i) => `other-app-${i + 1}`)),
    lock: { writer: null, expires: 0 },
    clock: Math.round(Date.now() / 1000),
    requests: [],
    otherWrite() {
      unit.lock = { writer: 'other-app', expires: unit.clock + WRITE_LOCK_S };
      log('another app wrote: locked for 60 s');
    },
  };

  /** encode(), plus the indoor/outdoor temperature segments a real unit reports. */
  function frame(s) {
    const bytes = [...Buffer.from(encode(s), 'base64')];
    const command = bytes.slice(0, 25); // 18-byte block + 1 segment (5 bytes) + CRC
    const receive = [
      ...bytes.slice(25, 25 + 18),
      2, 0x80, 0x20, nearest(indoorTemp, indoor), 0, 0x80, 0x10, nearest(outdoorTemp, outdoor), 0,
    ];
    const crc = crc16(receive);
    return Buffer.from([...command, ...receive, crc & 0xff, crc >> 8]).toString('base64');
  }

  const server = http.createServer((req, res) => {
    let raw = '';
    req.on('data', (c) => (raw += c));
    req.on('end', () => {
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
      const stat = () => ({
        airconId,
        airconStat: frame(unit.state),
        updatedBy: 'local',
        expires: unit.lock.expires,
        firmType: 'WF-RAC',
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
    state: initial,
    log,
  });
  log(`fake unit ${airconId} on port ${port}: ${describe(unit.state)}, ${unit.accounts.size}/${ACCOUNT_SLOTS} accounts taken`);
  const every = Number(arg('other-writer-every', 0));
  if (every > 0) setInterval(() => { unit.clock = Math.round(Date.now() / 1000); unit.otherWrite(); }, every * 1000);
  if (flag('mdns')) {
    const address = Object.values(os.networkInterfaces()).flat().find((a) => a?.family === 'IPv4' && !a.internal)?.address;
    if (address) respondToMdns(airconId, address, port, log);
  }
}
