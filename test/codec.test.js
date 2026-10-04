import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { crc16, decode, encode } from '../wfrac.js';

const fixture = (name) => JSON.parse(readFileSync(new URL(`./fixtures/${name}`, import.meta.url)));
const live = fixture('live-frames.json').frames;
const vectors = fixture('pywfrac-vectors.json');

test('crc16 is CRC-16/CCITT-FALSE', () => {
  assert.equal(crc16([...Buffer.from('123456789')]), 0x29b1);
});

test('decodes the captured live frames', () => {
  const states = Object.fromEntries(Object.entries(live).map(([id, f]) => [id, decode(f.airconStat)]));
  assert.deepEqual(
    Object.fromEntries(Object.entries(states).map(([id, s]) => [id, [s.power, s.mode, s.presetTemp, s.indoorTemp, s.outdoorTemp]])),
    {
      e81656c09e49: [false, 1, 21, 24, 17.7],
      e81656cb0d45: [false, 2, 20.5, 24, 16.7],
      e8165626b22e: [false, 1, 23, 28.2, 11.7],
    },
  );
});

test('decodes every frame exactly like pywfrac', () => {
  for (const { frame, state } of vectors.decode) {
    const ours = decode(frame);
    for (const [field, expected] of Object.entries(state)) {
      assert.equal(ours[field], expected, `${field} of ${frame}`);
    }
  }
});

test('encodes every state byte-for-byte like pywfrac', () => {
  for (const { state, frame } of vectors.encode) {
    assert.equal(encode(state), frame, JSON.stringify(state));
  }
});

test('an encoded state decodes back to itself', () => {
  for (const { state } of vectors.encode) {
    const back = decode(encode(state));
    for (const field of ['power', 'mode', 'presetTemp', 'airFlow', 'windUD', 'coolHotJudge', 'modelNrRaw']) {
      assert.equal(back[field], state[field], `${field} of ${JSON.stringify(state)}`);
    }
  }
});

test('refuses states it cannot encode', () => {
  const base = decode(live.e81656c09e49.airconStat);
  assert.throws(() => encode({ ...base, mode: 7 }), /unknown mode/);
  assert.throws(() => encode({ ...base, airFlow: -1 }), /unknown fan speed/);
});
