import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';

const dir = new URL('../public/locales/', import.meta.url);
const load = (file) => JSON.parse(readFileSync(new URL(file, dir)));
const english = load('en.json');
const placeholders = (s) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();

test('every key the app uses exists in English', () => {
  const source = readFileSync(new URL('../public/app.js', import.meta.url), 'utf8');
  const used = new Set([...source.matchAll(/\bt\('([\w.]+)'/g)].map((m) => m[1]));
  for (const mode of ['cool', 'heat', 'auto', 'fan', 'dry']) used.add(mode);
  for (const code of ['unreachable', 'unit_refused', 'bad_response', 'invalid', 'not_found', 'server']) used.add(`error.${code}`);
  for (const key of used) assert.ok(key in english, `missing in en.json: ${key}`);
});

for (const file of readdirSync(dir).filter((f) => f.endsWith('.json') && f !== 'en.json')) {
  test(`${file} matches English keys and placeholders`, () => {
    const translated = load(file);
    for (const key of Object.keys(translated)) assert.ok(key in english, `${file} has unknown key ${key}`);
    for (const [key, text] of Object.entries(english)) {
      assert.ok(key in translated, `${file} is missing ${key}`);
      assert.deepEqual(placeholders(translated[key]), placeholders(text), `${file} placeholders of ${key}`);
    }
  });
}
