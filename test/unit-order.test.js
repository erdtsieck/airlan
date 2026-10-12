import { test } from 'node:test';
import assert from 'node:assert/strict';
import { reorder } from '../unit-order.js';

const units = ['a', 'b', 'c'].map((airconId) => ({ airconId }));
const ids = (list) => list.map((u) => u.airconId).join('');

test('units follow the given order', () => {
  assert.equal(ids(reorder(units, ['c', 'a', 'b'])), 'cab');
});

test('units left out keep their order after the named ones; unknown and repeated ids are ignored', () => {
  assert.equal(ids(reorder(units, ['c', 'x', 'c'])), 'cab');
  assert.equal(ids(reorder(units, [])), 'abc');
});
