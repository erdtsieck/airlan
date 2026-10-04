import { test } from 'node:test';
import assert from 'node:assert/strict';
import { hostsToScan } from '../discovery.js';

const iface = (address, cidr, extra = {}) => ({ address, cidr: `${address}/${cidr}`, family: 'IPv4', internal: false, ...extra });

test('scans a /24 completely, except our own address', () => {
  const hosts = hostsToScan({ wifi: [iface('192.168.68.118', 24)] });
  assert.equal(hosts.length, 253);
  assert.ok(hosts.includes('192.168.68.1') && hosts.includes('192.168.68.254'));
  assert.ok(!hosts.includes('192.168.68.118') && !hosts.includes('192.168.68.0') && !hosts.includes('192.168.68.255'));
});

test('scans a /22 completely', () => {
  const hosts = hostsToScan({ eth: [iface('10.1.6.20', 22)] });
  assert.equal(hosts.length, 1021);
  assert.ok(hosts.includes('10.1.4.1') && hosts.includes('10.1.7.254'));
});

test('limits a larger network to the /24 around our own address', () => {
  const hosts = hostsToScan({ eth: [iface('10.20.30.40', 16)] });
  assert.equal(hosts.length, 253);
  assert.ok(hosts.every((h) => h.startsWith('10.20.30.')));
});

test('ignores loopback, IPv6 and point-to-point links, and merges interfaces', () => {
  const hosts = hostsToScan({
    lo: [iface('127.0.0.1', 8, { internal: true })],
    v6: [{ address: 'fe80::1', cidr: 'fe80::1/64', family: 'IPv6', internal: false }],
    vpn: [iface('10.8.0.2', 32)],
    wifi: [iface('192.168.1.10', 24)],
    eth: [iface('192.168.1.11', 24)],
  });
  assert.equal(hosts.length, 252);
});

test('findOpen finds listening hosts and gives up on silent ones', async (t) => {
  const { createServer } = await import('node:net');
  const { findOpen } = await import('../discovery.js');
  const server = createServer((s) => s.destroy());
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  t.after(() => server.close());
  const { port } = server.address();

  // 192.0.2.0/24 is TEST-NET-1: nothing answers there, so only the timeout ends the probe.
  // A silent host sits first in each batch: an array index leaking into the options once
  // turned its timeout into 0, which disables it and hung the scan.
  const started = Date.now();
  const open = await findOpen(['192.0.2.1', '127.0.0.1', '192.0.2.2', '127.0.0.1'], { port, timeoutMs: 300, batchSize: 2 });
  assert.deepEqual(open, ['127.0.0.1', '127.0.0.1']);
  assert.ok(Date.now() - started < 3000);
});
