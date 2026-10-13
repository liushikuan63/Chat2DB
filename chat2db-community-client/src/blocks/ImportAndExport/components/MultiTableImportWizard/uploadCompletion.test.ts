import assert from 'node:assert/strict';
import { test } from 'node:test';
import { claimCurrentUpload } from './uploadCompletion';
import { createStagedFileOwnership } from '../ImportMappingContent/stagedFileOwnership';

test('late uploaded resource is released after scope change removal or unmount', async () => {
  for (const state of [
    { mounted: true, generation: 2, activeSourceIds: new Set(['one']) },
    { mounted: true, generation: 1, activeSourceIds: new Set<string>() },
    { mounted: false, generation: 1, activeSourceIds: new Set(['one']) },
  ]) {
    const released: string[] = [];
    const ownership = createStagedFileOwnership(async (id) => {
      released.push(id);
    });
    assert.equal(claimCurrentUpload('late-file', 'one', 1, state, ownership), false);
    assert.equal(ownership.owns('late-file'), false);
    await ownership.releaseAll();
    assert.deepEqual(released, ['late-file']);
  }
});

test('successful upload stays owned across retry of another step and transfers once submitted', async () => {
  const released: string[] = [];
  const ownership = createStagedFileOwnership(async (id) => {
    released.push(id);
  });
  assert.equal(
    claimCurrentUpload(
      'kept-file',
      'one',
      1,
      { mounted: true, generation: 1, activeSourceIds: new Set(['one']) },
      ownership,
    ),
    true,
  );
  assert.equal(ownership.owns('kept-file'), true);
  assert.deepEqual(released, []);
  ownership.transfer(['kept-file']);
  await ownership.releaseAll();
  assert.deepEqual(released, []);
});
