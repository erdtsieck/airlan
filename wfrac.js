// Local protocol of the Mitsubishi Heavy Industries WF-RAC Wi-Fi module.
// Ported from pywfrac (MIT, see NOTICE), which documents the reverse-engineering work.
//
// The module listens on <scheme>://<ip>:51443/beaver/command/<command> (POST only).
// Older firmware (WF-RAC) speaks plain HTTP, newer branches (WF-RAC-HTTPS, WCBN4612L)
// HTTPS with a self-signed certificate and a 2016 mbedTLS stack.
//
// State travels as a base64 "airconStat": a COMMAND block and a RECEIVE block, each
// 18 bytes + variable segments + CRC16. A write is always a full state frame: fields
// without their set-bit are ignored, so we send the current state back with only the
// desired change applied.

import http from 'node:http';
import https from 'node:https';
import { constants as cryptoConstants } from 'node:crypto';
import { indoorTemp, outdoorTemp } from './temp-tables.js';

export const PORT = 51443;

export const Mode = Object.freeze({ AUTO: 0, COOL: 1, HEAT: 2, FAN: 3, DRY: 4 });
export const MODE_NAMES = ['auto', 'cool', 'heat', 'fan', 'dry'];

const CMD_MODE = [0x20, 0x28, 0x30, 0x2c, 0x24];
const RCV_MODE = [0x00, 0x08, 0x10, 0x0c, 0x04];
const CMD_AIRFLOW = [0x0f, 0x08, 0x09, 0x0a, 0x0e];
const RCV_AIRFLOW = [0x07, 0x00, 0x01, 0x02, 0x06];
const CMD_WIND_UD = [[192, 128], [128, 128], [128, 144], [128, 160], [128, 176]];
const CMD_WIND_LR = [[3, 16], [2, 16], [2, 17], [2, 18], [2, 19], [2, 20], [2, 21], [2, 22]];
const NO_SEGMENTS = [1, 255, 255, 255, 255];

const emptyBlock = () => [0, 0, 0, 0, 0, 0xff, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];

/** CRC16-CCITT (poly 0x1021, init 0xFFFF), as appended little-endian to each block. */
export function crc16(bytes) {
  let crc = 0xffff;
  for (const b of bytes) {
    crc ^= b << 8;
    for (let i = 0; i < 8; i++) crc = crc & 0x8000 ? ((crc << 1) ^ 0x1021) & 0xffff : (crc << 1) & 0xffff;
  }
  return crc;
}

const withCrc = (bytes) => {
  const crc = crc16(bytes);
  return [...bytes, crc & 0xff, crc >> 8];
};

/** Decodes an airconStat string into a state object. */
export function decode(base64) {
  const d = [...Buffer.from(base64, 'base64')];
  const start = d[18] * 4 + 21;
  const c = d.slice(start, start + 18);
  const segments = d.slice(start + 19, d.length - 2);

  const modelNrRaw = c[0] & 0x7f;
  const modelNr = modelNrRaw === 3 ? 2 : [0, 1, 2].indexOf(modelNrRaw);
  const s = {
    power: (c[2] & 0x03) === 1,
    mode: RCV_MODE.indexOf(c[2] & 0x3c),
    presetTemp: c[4] / 2,
    airFlow: RCV_AIRFLOW.indexOf(c[3] & 0x0f),
    windUD: (c[2] & 0xc0) === 0x40 ? 0 : [0, 16, 32, 48].indexOf(c[3] & 0xf0) + 1,
    windLR: (c[12] & 0x03) === 1 ? 0 : [0, 1, 2, 3, 4, 5, 6].indexOf(c[11] & 0x1f) + 1,
    entrust: (c[12] & 0x0c) === 4,
    coolHotJudge: (c[8] & 0x08) === 0,
    modelNrRaw,
    modelNr,
    vacant: (c[10] & 1) !== 0,
    selfClean: modelNr === 1 || modelNr === 2 ? (c[15] & 1) !== 0 : false,
    compressorRunning: (c[9] & 0x02) !== 0,
    errorCode: c[6] & 0x80 ? `M${String(c[6] & 0x7f).padStart(2, '0')}` : (c[6] & 0x7f) === 0 ? null : `E${c[6] & 0x7f}`,
    indoorTemp: null,
    outdoorTemp: null,
  };
  for (let i = 0; i + 3 < segments.length; i += 4) {
    if (segments[i] !== 0x80) continue;
    if (segments[i + 1] === 0x10) s.outdoorTemp = outdoorTemp[segments[i + 2]];
    if (segments[i + 1] === 0x20) s.indoorTemp = indoorTemp[segments[i + 2]];
  }
  return s;
}

function commandBlock(s) {
  const b = emptyBlock();
  b[2] |= s.power ? 3 : 2;
  b[2] |= CMD_MODE[s.mode];
  b[3] |= CMD_AIRFLOW[s.airFlow];
  const [ud2, ud3] = CMD_WIND_UD[s.windUD] ?? [0, 0];
  b[2] |= ud2;
  b[3] |= ud3;
  const [lr12, lr11] = CMD_WIND_LR[s.windLR] ?? [0, 0];
  b[12] |= lr12;
  b[11] |= lr11;
  b[4] |= Math.trunc(s.presetTemp / 0.5) + 128;
  b[12] |= s.entrust ? 12 : 8;
  if (!s.coolHotJudge) b[8] |= 8;
  if (s.modelNr === 1) b[10] |= s.vacant ? 1 : 0;
  if (s.modelNr === 1 || s.modelNr === 2) b[12] |= s.selfClean ? 144 : 128;
  return b;
}

function receiveBlock(s) {
  const b = emptyBlock();
  if (s.power) b[2] |= 1;
  b[2] |= RCV_MODE[s.mode];
  b[3] |= RCV_AIRFLOW[s.airFlow];
  if (s.windUD === 0) b[2] |= 64;
  else if (s.windUD >= 2 && s.windUD <= 4) b[3] |= (s.windUD - 1) * 16;
  if (s.windLR === 0) b[12] |= 1;
  else if (s.windLR >= 1 && s.windLR <= 7) b[11] |= s.windLR - 1;
  b[4] |= Math.trunc(s.presetTemp / 0.5);
  if (s.entrust) b[12] |= 4;
  if (!s.coolHotJudge) b[8] |= 8;
  b[0] |= s.modelNrRaw;
  if (s.modelNr === 1) b[10] |= s.vacant ? 1 : 0;
  if (s.modelNr === 1 || s.modelNr === 2) b[12] |= s.selfClean ? 144 : 128;
  return b;
}

/** Encodes a complete desired state into an airconStat string. */
export function encode(s) {
  if (!(s.mode in CMD_MODE)) throw new Error(`unknown mode ${s.mode}`);
  if (!(s.airFlow in CMD_AIRFLOW)) throw new Error(`unknown fan speed ${s.airFlow}`);
  const bytes = [...withCrc([...commandBlock(s), ...NO_SEGMENTS]), ...withCrc([...receiveBlock(s), ...NO_SEGMENTS])];
  return Buffer.from(bytes).toString('base64');
}

/**
 * The unit answered, but not with success. `code` is stable for clients to translate:
 *   unit_refused  - non-zero result (unregistered account, another client's 60 s write lock, …)
 *   bad_response  - the reply was not the expected JSON
 */
export class WfRacError extends Error {
  constructor(code, message, result) {
    super(message);
    this.code = code;
    this.result = result;
  }
}

// The HTTPS firmware runs mbedTLS 2.4 (2016): self-signed certificate, legacy ciphers
// and legacy renegotiation. Verification is pointless against a self-signed per-unit
// certificate, so it is off; the trade-off is the same as pywfrac's permissive context.
const legacyTls = {
  rejectUnauthorized: false,
  checkServerIdentity: () => undefined,
  minVersion: 'TLSv1',
  ciphers: 'DEFAULT:@SECLEVEL=0',
  secureOptions: cryptoConstants.SSL_OP_LEGACY_SERVER_CONNECT,
};

/**
 * One POST without keep-alive. Deliberately node:http and not fetch: the module's
 * embedded web server only reads a body that arrives in the same TCP packet as the
 * headers, and fetch (undici) sends them separately - the module then answers
 * "501 Not supported this command".
 */
function post(scheme, host, port, command, payload) {
  const body = JSON.stringify(payload);
  const transport = scheme === 'https' ? https : http;
  return new Promise((resolve, reject) => {
    const req = transport.request(
      {
        host,
        port,
        path: `/beaver/command/${command}`,
        method: 'POST',
        agent: false,
        timeout: 6000,
        headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
        ...(scheme === 'https' ? legacyTls : {}),
      },
      (res) => {
        let text = '';
        res.setEncoding('utf8');
        res.on('data', (c) => (text += c));
        res.on('end', () => {
          try {
            resolve(JSON.parse(text));
          } catch {
            reject(new WfRacError('bad_response', `unexpected reply (HTTP ${res.statusCode}): ${text.slice(0, 80)}`));
          }
        });
      },
    );
    req.on('timeout', () => req.destroy(Object.assign(new Error('unit did not respond'), { code: 'ETIMEDOUT' })));
    req.on('error', reject);
    req.end(body);
  });
}

/**
 * Talks to one module. The module handles one connection at a time and wants at least
 * a second between requests, so all requests go through a queue.
 *
 * The scheme (http/https) is discovered on first contact and exposed as `scheme` so the
 * caller can persist it. A transport failure on a known scheme forgets it again, so a
 * firmware update that switches protocol is picked up on the next request.
 */
export class WfRacClient {
  #queue = Promise.resolve();
  #lastRequestAt = 0;

  constructor({ host, port = PORT, scheme = null, deviceId, operatorId, minIntervalMs = 1100 }) {
    this.host = host;
    this.port = port;
    this.scheme = scheme;
    this.deviceId = deviceId;
    this.operatorId = operatorId;
    this.minIntervalMs = minIntervalMs;
  }

  async #send(command, payload) {
    if (this.scheme) {
      try {
        return await post(this.scheme, this.host, this.port, command, payload);
      } catch (e) {
        if (!(e instanceof WfRacError)) this.scheme = null;
        throw e;
      }
    }
    let lastError;
    for (const scheme of ['http', 'https']) {
      try {
        const body = await post(scheme, this.host, this.port, command, payload);
        this.scheme = scheme;
        return body;
      } catch (e) {
        lastError = e;
      }
    }
    throw lastError;
  }

  request(command, contents) {
    const run = async () => {
      const wait = this.#lastRequestAt + this.minIntervalMs - Date.now();
      if (wait > 0) await new Promise((r) => setTimeout(r, wait));
      try {
        const body = await this.#send(command, {
          apiVer: '1.0',
          command,
          deviceId: this.deviceId,
          operatorId: this.operatorId,
          timestamp: Math.round(Date.now() / 1000),
          ...(contents ? { contents } : {}),
        });
        if (body.result !== 0) throw new WfRacError('unit_refused', `unit refused ${command} (result ${body.result})`, body.result);
        return body.contents;
      } finally {
        this.#lastRequestAt = Date.now();
      }
    };
    const p = this.#queue.then(run, run);
    this.#queue = p.catch(() => {});
    return p;
  }

  getDeviceInfo() {
    return this.request('getDeviceInfo', {});
  }

  async getStat(airconId) {
    const contents = await this.request('getAirconStat', { airconId });
    return { contents, state: decode(contents.airconStat) };
  }

  async setStat(airconId, state) {
    const contents = await this.request('setAirconStat', { airconId, airconStat: encode(state) });
    return decode(contents.airconStat);
  }

  /** Writes require a registered account (4 slots per unit). Re-registering the same id is idempotent. */
  registerAccount(airconId, timezone) {
    return this.request('updateAccountInfo', { accountId: this.operatorId, airconId, remote: 0, timezone });
  }
}
