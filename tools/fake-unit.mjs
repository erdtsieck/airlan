// A stand-in for a WF-RAC module, for developing without an air conditioner.
//
//   node tools/fake-unit.mjs [--host 0.0.0.0] [--port 51443] [--id fa4e00000001]
//                            [--power on|off] [--mode cool|heat|auto|fan|dry] [--temp 21]
//                            [--indoor 23.5] [--outdoor 14.5]
//
// Answers getDeviceInfo, getAirconStat, setAirconStat and updateAccountInfo over plain HTTP
// like the HTTP firmware does, keeps the state across requests and logs every change.
// Like the real module it refuses writes from operators that did not register first
// (result 2), and it refuses request bodies with "\/" escapes, which the real parser does
// not undo. Point AirLAN at it by IP address (from the Android emulator: 10.0.2.2).

import http from 'node:http';
import { readFileSync } from 'node:fs';
import { crc16, decode, encode, MODE_NAMES } from '../wfrac.js';
import { indoorTemp, outdoorTemp } from '../temp-tables.js';

const arg = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? process.argv[i + 1] : fallback;
};
const host = arg('host', '0.0.0.0');
const port = Number(arg('port', 51443));
const airconId = arg('id', 'fa4e00000001');
const nearest = (table, t) => table.reduce((best, v, i) => (Math.abs(v - t) < Math.abs(table[best] - t) ? i : best), 0);
const sensors = { indoor: Number(arg('indoor', 23.5)), outdoor: Number(arg('outdoor', 14.5)) };

// Start from a frame captured from a real unit (switched off, cooling, 21 °C).
const live = JSON.parse(readFileSync(new URL('../test/fixtures/live-frames.json', import.meta.url)));
let state = decode(live.frames.e81656c09e49.airconStat);
if (arg('power')) state.power = arg('power') === 'on';
if (arg('mode')) state.mode = MODE_NAMES.indexOf(arg('mode'));
if (arg('temp')) state.presetTemp = Number(arg('temp'));

/** encode(), plus the indoor/outdoor temperature segments a real unit reports. */
function frame(s) {
  const bytes = [...Buffer.from(encode(s), 'base64')];
  const command = bytes.slice(0, 25); // 18-byte block + 1 segment (5 bytes) + CRC
  const receive = [
    ...bytes.slice(25, 25 + 18),
    2, 0x80, 0x20, nearest(indoorTemp, sensors.indoor), 0, 0x80, 0x10, nearest(outdoorTemp, sensors.outdoor), 0,
  ];
  const crc = crc16(receive);
  return Buffer.from([...command, ...receive, crc & 0xff, crc >> 8]).toString('base64');
}
const accounts = new Set();

const describe = (s) => `${s.power ? 'ON ' : 'off'} ${MODE_NAMES[s.mode]} ${s.presetTemp}°`;
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
log(`fake unit ${airconId} on ${host}:${port}: ${describe(state)}`);

http
  .createServer((req, res) => {
    let raw = '';
    req.on('data', (c) => (raw += c));
    req.on('end', () => {
      const reply = (result, contents) => {
        const body = JSON.stringify({ ...payload, result, ...(contents ? { contents } : {}) });
        res.writeHead(200, { 'Content-Type': 'application/json' }).end(body);
      };
      let payload;
      try {
        payload = JSON.parse(raw);
      } catch {
        return res.writeHead(501).end('Not supported this command');
      }
      const { command, operatorId, contents = {} } = payload;
      const stat = () => ({ airconId, airconStat: frame(state), updatedBy: 'local', firmType: 'WF-RAC' });

      switch (command) {
        case 'getDeviceInfo':
          return reply(0, { airconId, macAddress: airconId, apMode: 0 });
        case 'updateAccountInfo':
          accounts.add(contents.accountId);
          log(`registered ${contents.accountId}`);
          return reply(0);
        case 'getAirconStat':
          return reply(0, stat());
        case 'setAirconStat': {
          if (operatorId !== 'aws' && !accounts.has(operatorId)) {
            log(`refused write: ${operatorId} is not registered`);
            return reply(2, stat());
          }
          if (raw.includes('\\/')) {
            log('refused write: escaped slashes in the request body');
            return reply(2, stat());
          }
          const before = describe(state);
          state = { ...decode(contents.airconStat), indoorTemp: state.indoorTemp, outdoorTemp: state.outdoorTemp };
          log(`${before} -> ${describe(state)}`);
          return reply(0, stat());
        }
        default:
          return res.writeHead(501).end('Not supported this command');
      }
    });
  })
  .listen(port, host);
