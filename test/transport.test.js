import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import https from 'node:https';
import { readFileSync } from 'node:fs';
import { WfRacClient, WfRacError } from '../wfrac.js';

const tls = {
  key: readFileSync(new URL('./fixtures/test-unit.key', import.meta.url)),
  cert: readFileSync(new URL('./fixtures/test-unit.crt', import.meta.url)),
};

/** A fake module: answers like the real one and records what it received. */
function fakeUnit(transport, { result = 0 } = {}) {
  const requests = [];
  const handler = (req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      const payload = JSON.parse(body);
      requests.push({ at: Date.now(), path: req.url, payload });
      res.end(JSON.stringify({ ...payload, result, contents: { airconId: 'e81656cb0d45', macAddress: 'e81656cb0d45', apMode: 0 } }));
    });
  };
  const server = transport === 'https' ? https.createServer(tls, handler) : http.createServer(handler);
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve({ server, requests, port: server.address().port })));
}

const client = (port, extra = {}) =>
  new WfRacClient({ host: '127.0.0.1', port, deviceId: 'test', operatorId: 'op', minIntervalMs: 0, ...extra });

test('talks plain HTTP to older firmware and sends the full envelope', async (t) => {
  const unit = await fakeUnit('http');
  t.after(() => unit.server.close());
  const c = client(unit.port);

  assert.equal((await c.getDeviceInfo()).airconId, 'e81656cb0d45');
  assert.equal(c.scheme, 'http');
  const { path, payload } = unit.requests[0];
  assert.equal(path, '/beaver/command/getDeviceInfo');
  assert.deepEqual(Object.keys(payload).sort(), ['apiVer', 'command', 'contents', 'deviceId', 'operatorId', 'timestamp']);
});

test('falls back to HTTPS with a self-signed certificate', async (t) => {
  const unit = await fakeUnit('https');
  t.after(() => unit.server.close());
  const c = client(unit.port);

  assert.equal((await c.getDeviceInfo()).airconId, 'e81656cb0d45');
  assert.equal(c.scheme, 'https');
});

test('a known scheme is used directly', async (t) => {
  const unit = await fakeUnit('https');
  t.after(() => unit.server.close());
  let plainAttempts = 0;
  const probe = http.createServer(() => plainAttempts++);
  t.after(() => probe.close());
  const c = client(unit.port, { scheme: 'https' });

  await c.getDeviceInfo();
  assert.equal(plainAttempts, 0);
  assert.equal(unit.requests.length, 1);
});

test('a refusal is a WfRacError with code unit_refused and keeps the scheme', async (t) => {
  const unit = await fakeUnit('http', { result: 2 });
  t.after(() => unit.server.close());
  const c = client(unit.port, { scheme: 'http' });

  await assert.rejects(c.getDeviceInfo(), (e) => e instanceof WfRacError && e.code === 'unit_refused' && e.result === 2);
  assert.equal(c.scheme, 'http');
});

test('an unreachable unit is a network error and forgets the scheme', async () => {
  const c = client(1, { scheme: 'https' });
  await assert.rejects(c.getDeviceInfo(), (e) => !(e instanceof WfRacError) && typeof e.code === 'string');
  assert.equal(c.scheme, null);
});

test('requests to one unit are serialised and spaced out', async (t) => {
  const unit = await fakeUnit('http');
  t.after(() => unit.server.close());
  const c = client(unit.port, { scheme: 'http', minIntervalMs: 150 });

  await Promise.all([c.getDeviceInfo(), c.getDeviceInfo(), c.getDeviceInfo()]);
  const gaps = unit.requests.slice(1).map((r, i) => r.at - unit.requests[i].at);
  assert.ok(gaps.every((g) => g >= 140), `gaps ${gaps}`);
});
