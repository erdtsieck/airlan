import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import { readFileSync } from 'node:fs';
import { WfRacClient, WfRacError, failureKind } from '../wfrac.js';

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

test('an unreachable unit keeps its scheme and is not tried over the other protocol', async () => {
  for (const scheme of ['https', null]) {
    const c = client(1, { scheme });
    await assert.rejects(c.getDeviceInfo(), (e) => failureKind(e) === 'unreachable');
    assert.equal(c.scheme, scheme);
    assert.match(c.trace.entries()[0].error, /^unreachable/);
  }
});

/** Accepts connections, records the first bytes of each, and answers `reply` (or nothing, keeping the connection open). */
function rawUnit(reply) {
  const first = [];
  const sockets = new Set();
  const server = net.createServer((s) => {
    sockets.add(s);
    s.once('data', (d) => {
      first.push(d);
      if (reply) s.end(reply);
    });
    s.on('error', () => {});
    s.on('close', () => sockets.delete(s));
  });
  const close = () => { for (const s of sockets) s.destroy(); server.close(); };
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve({ port: server.address().port, first, close })));
}

// Regression: a unit that answered slowly once lost its scheme, after which every request
// went out twice, the second time as a TLS handshake to a plain-HTTP module, which kept it
// occupied. Only restarting (reloading the stored scheme) ended that.
test('a unit that does not answer in time keeps its scheme and gets no TLS handshake', async (t) => {
  const unit = await rawUnit(null);
  t.after(() => unit.close());
  for (const scheme of ['http', null]) {
    const c = client(unit.port, { scheme, timeouts: { readTimeoutMs: 200 } });
    for (let i = 0; i < 2; i++) {
      await assert.rejects(c.getDeviceInfo(), (e) => failureKind(e) === 'no_answer');
      assert.equal(c.scheme, scheme);
    }
  }
  assert.equal(unit.first.length, 4);
  assert.ok(unit.first.every((b) => b.toString('latin1').startsWith('POST ')), 'every attempt is plain HTTP');
});

test('a unit that answers a plain request in TLS is detected again', async (t) => {
  // A TLS alert record (fatal, unexpected_message): what a TLS server sends to plain text.
  const unit = await rawUnit(Buffer.from([0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x0a]));
  t.after(() => unit.close());
  const c = client(unit.port, { scheme: 'http' });
  await assert.rejects(c.getDeviceInfo());
  assert.match(c.trace.notes()[0].text, /another protocol/);
});

test('a unit known as HTTPS that answers in plain HTTP switches to HTTP', async (t) => {
  const unit = await fakeUnit('http');
  t.after(() => unit.server.close());
  const c = client(unit.port, { scheme: 'https' });
  assert.equal((await c.getDeviceInfo()).airconId, 'e81656cb0d45');
  assert.equal(c.scheme, 'http');
});

test('the time a request waited for its turn is recorded', async (t) => {
  const unit = await fakeUnit('http');
  t.after(() => unit.server.close());
  const c = client(unit.port, { scheme: 'http', minIntervalMs: 300 });
  await Promise.all([c.getDeviceInfo(), c.getDeviceInfo()]);
  assert.ok(Math.max(...c.trace.entries().map((e) => e.waitedMs)) >= 250);
});

test('requests to one unit are serialised and spaced out', async (t) => {
  const unit = await fakeUnit('http');
  t.after(() => unit.server.close());
  const c = client(unit.port, { scheme: 'http', minIntervalMs: 150 });

  await Promise.all([c.getDeviceInfo(), c.getDeviceInfo(), c.getDeviceInfo()]);
  const gaps = unit.requests.slice(1).map((r, i) => r.at - unit.requests[i].at);
  assert.ok(gaps.every((g) => g >= 140), `gaps ${gaps}`);
});

test('records every request and its outcome for diagnostics', async (t) => {
  const ok = await fakeUnit('http');
  const refusing = await fakeUnit('http', { result: 2 });
  t.after(() => { ok.server.close(); refusing.server.close(); });
  const a = client(ok.port, { scheme: 'http' });
  await a.getDeviceInfo();
  const b = client(refusing.port, { scheme: 'http' });
  await assert.rejects(b.getDeviceInfo());
  const gone = client(1, { scheme: 'http' });
  await assert.rejects(gone.getDeviceInfo());

  const [first] = a.trace.entries();
  assert.equal(first.command, 'getDeviceInfo');
  assert.equal(first.result, 0);
  assert.equal(first.error, null);
  assert.equal(b.trace.entries()[0].result, 2);
  const [failed] = gone.trace.entries();
  assert.equal(failed.result, null);
  assert.ok(failed.error);
});
