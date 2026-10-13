import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createTargetListLoader } from './targetListLoader';

test('target retry requests only the failed schema and keeps successful tables', async () => {
  const calls: string[] = [];
  let discovery = 0;
  let fail = true;
  const loader = createTargetListLoader(
    async () => {
      discovery++;
      return ['public', 'archive'];
    },
    async (schema) => {
      calls.push(schema!);
      if (schema === 'archive' && fail) throw new Error('temporary');
      return [{ schemaName: schema, tableName: schema === 'public' ? 'users' : 'orders' }];
    },
  );
  const first = await loader.load();
  assert.equal(first.errors.length, 1);
  assert.deepEqual(first.targets, [{ schemaName: 'public', tableName: 'users' }]);
  fail = false;
  const retry = await loader.load();
  assert.equal(retry.errors.length, 0);
  assert.deepEqual(retry.targets, [
    { schemaName: 'public', tableName: 'users' },
    { schemaName: 'archive', tableName: 'orders' },
  ]);
  assert.deepEqual(calls, ['public', 'archive', 'archive']);
  assert.equal(discovery, 1);
  await loader.load();
  assert.deepEqual(calls, ['public', 'archive', 'archive']);
});

test('pending target loads deduplicate discovery and table requests', async () => {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let calls = 0;
  const loader = createTargetListLoader(
    async () => {
      calls++;
      await gate;
      return [undefined];
    },
    async () => {
      calls++;
      return [{ tableName: 'users' }];
    },
  );
  const first = loader.load();
  const second = loader.load();
  assert.equal(first, second);
  release();
  await first;
  assert.equal(calls, 2);
});

test('failed schema discovery is retried explicitly and not automatically', async () => {
  let calls = 0;
  const loader = createTargetListLoader(
    async () => {
      if (++calls === 1) throw new Error('offline');
      return ['public'];
    },
    async () => [{ tableName: 'users' }],
  );
  assert.equal((await loader.load()).errors.length, 1);
  assert.equal(calls, 1);
  assert.equal((await loader.load()).errors.length, 0);
  assert.equal(calls, 2);
});
