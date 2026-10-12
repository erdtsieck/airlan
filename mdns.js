// Finding WF-RAC modules by multicast DNS. Each module announces the service
// `_beaver._tcp.local` with its MAC address (lower case, no colons) as instance name, which
// is also its airconId. Just enough DNS for that: PTR, SRV and A records, no dependencies.

import dgram from 'node:dgram';

export const SERVICE = '_beaver._tcp.local';
const MDNS_ADDRESS = '224.0.0.251';
const MDNS_PORT = 5353;
const TYPE = { A: 1, PTR: 12, TXT: 16, SRV: 33 };
const CLASS_IN = 1;
const UNICAST_RESPONSE = 0x8000; // QU bit: please answer us directly
const CACHE_FLUSH = 0x8000;

function encodeName(name) {
  const parts = name.replace(/\.$/, '').split('.');
  const bytes = [];
  for (const p of parts) {
    const label = Buffer.from(p, 'utf8');
    if (label.length > 63) throw new Error(`label too long: ${p}`);
    bytes.push(label.length, ...label);
  }
  bytes.push(0);
  return bytes;
}

const u16 = (n) => [(n >> 8) & 0xff, n & 0xff];
const u32 = (n) => [(n >>> 24) & 0xff, (n >>> 16) & 0xff, (n >>> 8) & 0xff, n & 0xff];

/** A query for `name` of `type` (default PTR); `unicast` asks responders to answer us directly. */
export function encodeQuery(name = SERVICE, type = TYPE.PTR, { unicast = true } = {}) {
  return Buffer.from([...u16(0), ...u16(0), ...u16(1), ...u16(0), ...u16(0), ...u16(0), ...encodeName(name), ...u16(type), ...u16(CLASS_IN | (unicast ? UNICAST_RESPONSE : 0))]);
}

/** A response announcing one module, as a module sends it (used by tools/fake-unit.mjs and tests). */
export function encodeAnnouncement({ instance, address, port = 51443, ttl = 120 }) {
  const full = `${instance}.${SERVICE}`;
  const host = `${instance}.local`;
  const record = (name, type, data, flush = false) => [...encodeName(name), ...u16(type), ...u16(CLASS_IN | (flush ? CACHE_FLUSH : 0)), ...u32(ttl), ...u16(data.length), ...data];
  const answers = [
    record(SERVICE, TYPE.PTR, encodeName(full)),
    record(full, TYPE.SRV, [...u16(0), ...u16(0), ...u16(port), ...encodeName(host)], true),
    record(full, TYPE.TXT, [0], true),
    record(host, TYPE.A, address.split('.').map(Number), true),
  ];
  return Buffer.from([...u16(0), ...u16(0x8400), ...u16(0), ...u16(answers.length), ...u16(0), ...u16(0), ...answers.flat()]);
}

function readName(buf, offset) {
  const labels = [];
  let pos = offset;
  let end = null;
  for (let jumps = 0; jumps < 32; jumps++) {
    const len = buf[pos];
    if (len === undefined) throw new Error('name runs past the end');
    if ((len & 0xc0) === 0xc0) {
      if (end === null) end = pos + 2;
      pos = ((len & 0x3f) << 8) | buf[pos + 1];
      continue;
    }
    if (len === 0) return { name: labels.join('.'), end: end ?? pos + 1 };
    labels.push(buf.toString('utf8', pos + 1, pos + 1 + len));
    pos += 1 + len;
  }
  throw new Error('too many compression pointers');
}

/** A DNS message: whether it is a response, its questions and its resource records (all sections). */
export function parseMessage(buf) {
  if (buf.length < 12) throw new Error('message too short');
  const count = (i) => buf.readUInt16BE(i);
  let pos = 12;
  const questions = [];
  for (let q = 0; q < count(4); q++) {
    const { name, end } = readName(buf, pos);
    questions.push({ name: name.toLowerCase(), type: buf.readUInt16BE(end) });
    pos = end + 4;
  }
  const records = [];
  const total = count(6) + count(8) + count(10);
  for (let r = 0; r < total; r++) {
    const { name, end } = readName(buf, pos);
    const type = buf.readUInt16BE(end);
    const length = buf.readUInt16BE(end + 8);
    const data = end + 10;
    if (data + length > buf.length) throw new Error('record runs past the end');
    const rec = { name: name.toLowerCase(), type };
    if (type === TYPE.PTR) rec.target = readName(buf, data).name.toLowerCase();
    else if (type === TYPE.SRV) Object.assign(rec, { port: buf.readUInt16BE(data + 4), target: readName(buf, data + 6).name.toLowerCase() });
    else if (type === TYPE.A && length === 4) rec.address = [...buf.subarray(data, data + 4)].join('.');
    records.push(rec);
    pos = data + length;
  }
  return { response: (buf[2] & 0x80) !== 0, questions, records };
}

/** The resource records of a DNS message. */
export const parseRecords = (buf) => parseMessage(buf).records;

/**
 * Folds records (from any number of messages) into the modules they describe:
 * `[{ airconId, address, port }]`, only those whose address is known.
 */
export function modulesFrom(records) {
  const suffix = `.${SERVICE}`;
  const instances = new Set(records.filter((r) => r.type === TYPE.PTR && r.name === SERVICE).map((r) => r.target));
  const srv = new Map(records.filter((r) => r.type === TYPE.SRV).map((r) => [r.name, r]));
  const addresses = new Map(records.filter((r) => r.type === TYPE.A).map((r) => [r.name, r.address]));
  for (const name of srv.keys()) if (name.endsWith(suffix)) instances.add(name);
  const found = [];
  for (const instance of instances) {
    const airconId = instance.slice(0, -suffix.length);
    if (!/^[0-9a-f]{12}$/.test(airconId)) continue;
    const s = srv.get(instance);
    const address = addresses.get(s?.target ?? `${airconId}.local`);
    if (address) found.push({ airconId, address, port: s?.port ?? 51443 });
  }
  return found;
}

/**
 * Asks the network for WF-RAC modules and listens for `timeoutMs`. Queries go out on every
 * given IPv4 interface address; answers come back to our own socket (unicast) or, from
 * responders that only multicast, to the mDNS port, which is joined when it can be shared.
 * Never throws: no answer, or no multicast on this host, is an empty list.
 */
export async function browse(interfaceAddresses, { timeoutMs = 2500 } = {}) {
  const records = [];
  const sockets = [];
  const onMessage = (msg) => { try { records.push(...parseRecords(msg)); } catch { /* not for us */ } };
  const open = (port, join) => new Promise((resolve) => {
    const s = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    s.on('message', onMessage);
    s.once('error', () => { s.close(); resolve(null); });
    s.bind(port, () => {
      try {
        if (join) for (const a of interfaceAddresses) { try { s.addMembership(MDNS_ADDRESS, a); } catch { /* interface without multicast */ } }
        s.on('error', () => {});
        sockets.push(s);
        resolve(s);
      } catch {
        s.close();
        resolve(null);
      }
    });
  });

  const querier = await open(0, false);
  const listener = await open(MDNS_PORT, true);
  const send = (socket, query) => {
    for (const a of interfaceAddresses) {
      try {
        socket.setMulticastInterface(a);
        socket.send(query, MDNS_PORT, MDNS_ADDRESS);
      } catch { /* interface went away */ }
    }
  };
  // Some responders answer a unicast request, others only ever multicast, and then only to
  // a query that came from the mDNS port: ask both ways.
  const ask = (name, type) => {
    if (querier) send(querier, encodeQuery(name, type));
    if (listener) send(listener, encodeQuery(name, type, { unicast: false }));
  };
  try {
    ask();
    await new Promise((r) => setTimeout(r, Math.min(1000, timeoutMs / 2)));
    // Ask again (mDNS loses packets), and ask for addresses an answer left out.
    ask();
    const known = new Set(records.filter((r) => r.type === TYPE.A).map((r) => r.name));
    for (const r of records) if (r.type === TYPE.SRV && !known.has(r.target)) ask(r.target, TYPE.A);
    await new Promise((r) => setTimeout(r, timeoutMs - Math.min(1000, timeoutMs / 2)));
  } finally {
    for (const s of sockets) s.close();
  }
  return modulesFrom(records);
}
