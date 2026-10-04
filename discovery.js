// Which addresses to probe when looking for WF-RAC modules on the local network.

import net from 'node:net';
import { PORT } from './wfrac.js';

// Networks up to /22 (1022 hosts) are scanned completely. For larger ones, scanning
// everything takes too long, so only the /24 around our own address is scanned; units
// elsewhere can be added by address.
const MAX_SCAN_PREFIX = 22;

const toInt = (ip) => ip.split('.').reduce((n, part) => n * 256 + Number(part), 0);
const toIp = (n) => [24, 16, 8, 0].map((shift) => Math.floor(n / 2 ** shift) % 256).join('.');

/** Host addresses to probe, from the output of os.networkInterfaces(). */
export function hostsToScan(interfaces) {
  const hosts = new Set();
  const own = new Set();
  for (const a of Object.values(interfaces).flat()) {
    if (a?.family !== 'IPv4' || a.internal || !a.cidr) continue;
    const cidrPrefix = Number(a.cidr.split('/')[1]);
    if (cidrPrefix > 30) continue;
    const prefix = cidrPrefix < MAX_SCAN_PREFIX ? 24 : cidrPrefix;
    const size = 2 ** (32 - prefix);
    const network = Math.floor(toInt(a.address) / size) * size;
    own.add(a.address);
    for (let i = 1; i < size - 1; i++) hosts.add(toIp(network + i));
  }
  for (const a of own) hosts.delete(a);
  return [...hosts];
}

/** Our own LAN addresses, for showing where the app can be reached. */
export function lanAddresses(interfaces) {
  return Object.values(interfaces)
    .flat()
    .filter((a) => a?.family === 'IPv4' && !a.internal)
    .map((a) => a.address);
}

export const isIpv4 = (s) => net.isIPv4(s);

/** Whether something accepts connections on the WF-RAC port. */
export function portOpen(host, { port = PORT, timeoutMs = 1500 } = {}) {
  return new Promise((resolve) => {
    const s = net.connect({ host, port });
    const done = (ok) => { s.destroy(); resolve(ok); };
    s.setTimeout(timeoutMs, () => done(false));
    s.once('connect', () => done(true));
    s.once('error', () => done(false));
  });
}

/** Probes hosts in batches, so a /22 does not open a thousand sockets at once. */
export async function findOpen(hosts, { batchSize = 256, ...probe } = {}) {
  const open = [];
  for (let i = 0; i < hosts.length; i += batchSize) {
    const batch = hosts.slice(i, i + batchSize);
    const results = await Promise.all(batch.map((host) => portOpen(host, probe)));
    open.push(...batch.filter((_, j) => results[j]));
  }
  return open;
}
