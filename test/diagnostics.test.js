import { test } from 'node:test';
import assert from 'node:assert/strict';
import { maskAddresses, report } from '../diagnostics.js';
import { RequestTrace } from '../wfrac.js';

test('masks addresses but keeps their last number', () => {
  assert.equal(maskAddresses('connect ECONNREFUSED 192.168.68.109:51443'), 'connect ECONNREFUSED x.x.x.109:51443');
});

test('reports firmware, state and requests without the name, address or MAC', () => {
  const trace = new RequestTrace();
  trace.module = { firmType: 'WF-RAC', wireless: '010', mcu: '131', accounts: 3 };
  trace.add({ at: 0, command: 'getAirconStat', ms: 120, result: 0, error: null });
  trace.add({ at: 1000, command: 'setAirconStat', ms: 300, result: 1, error: null });
  trace.add({ at: 2000, command: 'getAirconStat', ms: 6000, result: null, error: 'ETIMEDOUT: connect to 192.168.68.109 timed out' });
  const unit = { airconId: 'e81656cb0d45', name: 'Woonkamer', host: '192.168.68.109', scheme: 'http', modelNrRaw: 1, reportsVaneLR: true };
  const state = { power: true, mode: 'auto', presetTemp: 22, airFlow: 0, windUD: 0, windLR: 0, entrust: true, modelNrRaw: 1, errorCode: null };

  const text = report({ version: '0.3.0', platform: 'Node test', unit, state, trace, now: 0 });

  assert.match(text, /firmType WF-RAC, wireless 010, mcu 131, accounts 3/);
  assert.match(text, /\.\.\.0d45 at x\.x\.x\.109, transport http/);
  assert.match(text, /mode auto/);
  assert.match(text, /setAirconStat 300 ms result 1/);
  assert.match(text, /failures: 2 of 3/);
  assert.doesNotMatch(text, /Woonkamer|192\.168|e81656cb/);
});

test('a request that queued long and events such as a move are reported, addresses masked', () => {
  const trace = new RequestTrace();
  trace.add({ at: 0, command: 'getAirconStat', ms: 10000, result: null, error: 'connected, no answer within 10 s', waitedMs: 4200 });
  trace.add({ at: 1000, command: 'getAirconStat', ms: 90, result: 0, error: null, waitedMs: 200 });
  trace.note('address changed from 192.168.68.116 to 192.168.68.120', 2000);
  const unit = { airconId: 'e81656cb0d45', name: 'Woonkamer', host: '192.168.68.109', scheme: 'http' };

  const text = report({ version: '0.3.2', platform: 'Node test', unit, state: null, trace, now: 0 });

  assert.match(text, /getAirconStat 10000 ms, queued 4200 ms connected, no answer within 10 s/);
  assert.match(text, /getAirconStat 90 ms result 0/);
  assert.match(text, /Events:\n.*address changed from x\.x\.x\.116 to x\.x\.x\.120/);
});
