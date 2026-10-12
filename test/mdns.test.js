import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { encodeQuery, encodeAnnouncement, parseMessage, modulesFrom, SERVICE } from '../mdns.js';

test('reads the announcement a real module sends', () => {
  // Captured from a WF-RAC 010/131 module, answering a PTR query for _beaver._tcp.local.
  const msg = readFileSync(new URL('./fixtures/mdns-beaver-response.bin', import.meta.url));
  const { response, records } = parseMessage(msg);
  assert.equal(response, true);
  assert.deepEqual(modulesFrom(records), [{ airconId: 'e81656c09e49', address: '192.168.68.108', port: 51443 }]);
});

test('an announcement round-trips through the parser', () => {
  const { records } = parseMessage(encodeAnnouncement({ instance: 'a1b2c3d4e5f6', address: '10.0.0.7' }));
  assert.deepEqual(modulesFrom(records), [{ airconId: 'a1b2c3d4e5f6', address: '10.0.0.7', port: 51443 }]);
});

test('a query asks for the service, with or without a unicast answer', () => {
  const unicast = parseMessage(encodeQuery());
  assert.equal(unicast.response, false);
  assert.deepEqual(unicast.questions, [{ name: SERVICE, type: 12 }]);
  const q = encodeQuery();
  assert.equal(q.readUInt16BE(q.length - 2), 0x8001);
  const multicast = encodeQuery(SERVICE, 12, { unicast: false });
  assert.equal(multicast.readUInt16BE(multicast.length - 2), 0x0001);
});

test('follows name compression pointers', () => {
  // Header, then one PTR answer whose target points back at the question name at offset 12.
  const name = Buffer.from([7, ...Buffer.from('_beaver'), 4, ...Buffer.from('_tcp'), 5, ...Buffer.from('local'), 0]);
  const target = Buffer.from([12, ...Buffer.from('e81656c09e49'), 0xc0, 12]);
  const msg = Buffer.concat([
    Buffer.from([0, 0, 0x84, 0, 0, 1, 0, 1, 0, 0, 0, 0]),
    name, Buffer.from([0, 12, 0, 1]),
    Buffer.from([0xc0, 12, 0, 12, 0, 1, 0, 0, 0, 120, 0, target.length]), target,
  ]);
  const { questions, records } = parseMessage(msg);
  assert.equal(questions[0].name, SERVICE);
  assert.equal(records[0].target, `e81656c09e49.${SERVICE}`);
});

test('modules without an address, or with a name that is not a MAC address, are left out', () => {
  const { records } = parseMessage(encodeAnnouncement({ instance: 'a1b2c3d4e5f6', address: '10.0.0.7' }));
  assert.deepEqual(modulesFrom(records.filter((r) => r.type !== 1)), []);
  const odd = parseMessage(encodeAnnouncement({ instance: 'printer', address: '10.0.0.8' })).records;
  assert.deepEqual(modulesFrom(odd), []);
});

test('truncated messages are rejected, not misread', () => {
  const msg = encodeAnnouncement({ instance: 'a1b2c3d4e5f6', address: '10.0.0.7' });
  assert.throws(() => parseMessage(msg.subarray(0, msg.length - 3)));
  assert.throws(() => parseMessage(Buffer.alloc(5)));
});
