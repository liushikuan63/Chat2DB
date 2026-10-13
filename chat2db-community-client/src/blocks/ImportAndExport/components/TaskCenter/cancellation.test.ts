import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { TaskControlState } from '@/service/importExport';
import { requestTaskCancellation } from './cancellation';

const state = (patch: Partial<TaskControlState> = {}): TaskControlState => ({
  taskId: 7, status: 'RUNNING', cancelSupported: true, cancellationRequested: false,
  pauseSupported: false, pauseReason: 'SAFE_PAUSE_UNSUPPORTED', reason: 'READY', ...patch,
});

test('commit protection and terminal responses do not send a cancellation request', async () => {
  let requests = 0;
  for (const control of [
    state({ cancelSupported: false, reason: 'COMMIT_IN_PROGRESS' }),
    state({ cancelSupported: false, reason: 'TERMINAL', status: 'SUCCESS' }),
  ]) {
    assert.deepEqual(await requestTaskCancellation(7, {
      getTaskControl: async () => control,
      cancelTask: async () => { requests += 1; return state(); },
    }), control);
  }
  assert.equal(requests, 0);
});

test('accepted cancellation keeps the actual running acknowledgement', async () => {
  const requests: number[] = [];
  const result = await requestTaskCancellation(7, {
    getTaskControl: async () => state(),
    cancelTask: async ({ taskId }) => {
      requests.push(taskId);
      return state({ cancellationRequested: true, cancelSupported: false, reason: 'CANCELLATION_REQUESTED' });
    },
  });
  assert.deepEqual(requests, [7]);
  assert.equal(result.status, 'RUNNING');
  assert.equal(result.cancellationRequested, true);
});

test('lost acknowledgement remains an error and mismatched task controls are rejected', async () => {
  await assert.rejects(requestTaskCancellation(7, {
    getTaskControl: async () => state(),
    cancelTask: async () => { throw new Error('Network response lost'); },
  }), /Network response lost/);
  let requests = 0;
  await assert.rejects(requestTaskCancellation(7, {
    getTaskControl: async () => state({ taskId: 9 }),
    cancelTask: async () => { requests += 1; return state(); },
  }), /does not match/);
  assert.equal(requests, 0);
});
