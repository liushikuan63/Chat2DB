import assert from 'node:assert/strict';
import {
  getImportUploadLimits, IMPORT_MIB, loadImportUploadCapabilities, checkImportUploadSelection,
  type ImportUploadCapabilities, type ImportUploadCapabilityState,
} from './importUploadLimits';

const caps: ImportUploadCapabilities = {
  singleFileMaxBytes: 50 * IMPORT_MIB, multiFileMaxBytes: 1024 * IMPORT_MIB,
  singleLocalFileMaxBytes: 50 * IMPORT_MIB, multiLocalFileMaxBytes: 1024 * IMPORT_MIB,
  multiTotalMaxBytes: 10 * 1024 * IMPORT_MIB, maxFiles: 1000,
  stagingMaxFileBytes: 1024 * IMPORT_MIB, stagingMaxTotalBytes: 10 * 1024 * IMPORT_MIB,
  multipartMaxFileBytes: 1024 * IMPORT_MIB, multipartMaxRequestBytes: 1025 * IMPORT_MIB,
  multipartOverheadBytes: IMPORT_MIB,
};
assert.equal(getImportUploadLimits(caps, 'single').fileSize, 50);
assert.equal(getImportUploadLimits(caps, 'multi').fileSize, 1024);
assert.equal(getImportUploadLimits({ ...caps, multiFileMaxBytes: 1234567 }, 'multi').maxFileBytes, 1234567);
assert.equal(getImportUploadLimits({ ...caps, multiFileMaxBytes: 1234567 }, 'multi').fileSize * IMPORT_MIB, 1234567);
assert.equal(getImportUploadLimits({ ...caps, multiFileMaxBytes: 3 * IMPORT_MIB }, 'multi', true).fileSize, 1024);
assert.equal(getImportUploadLimits({ ...caps, multiTotalMaxBytes: IMPORT_MIB }, 'multi').fileSize, 1);
assert.equal(getImportUploadLimits({ ...caps, multiFileMaxBytes: 0 }, 'multi').disabled, true);
const small = getImportUploadLimits({ ...caps, multiFileMaxBytes: 4, multiTotalMaxBytes: 6, maxFiles: 2 }, 'multi');
assert.equal(checkImportUploadSelection([{ fileSize: 4 }, { fileSize: 2 }], small).valid, true);
assert.deepEqual(checkImportUploadSelection([{ fileSize: 4 }, { fileSize: 3 }], small).violations,
  ['maxTotalSizeBytes']);
assert.deepEqual(checkImportUploadSelection([{ fileSize: 5 }], small).violations, ['maxFileBytes']);
assert.deepEqual(checkImportUploadSelection([{}], small).violations, ['unknownSize']);
assert.equal(checkImportUploadSelection([{ fileSize: 1 }, { fileSize: 1 }, { fileSize: 1 }], small).valid, false);
assert.equal(checkImportUploadSelection([], small).valid, false);
assert.equal(checkImportUploadSelection([{ fileSize: 1 }],
  getImportUploadLimits({ ...caps, multiFileMaxBytes: 0 }, 'multi')).valid, false);
assert.equal(checkImportUploadSelection([{ fileSize: 1, file: { size: 5 } }], small).valid, false);
for (const invalid of [undefined, {}, '<html>SPA fallback</html>', { ...caps, maxFiles: -1 },
  { ...caps, multiFileMaxBytes: Infinity }, { ...caps, multiFileMaxBytes: '1' }]) {
  const limits = getImportUploadLimits(invalid, 'multi');
  assert.equal(limits.source, 'fallback');
  assert.equal(limits.maxTotalSizeBytes, 50 * IMPORT_MIB);
  assert.equal(limits.maxFiles, 1000, 'fallback must retain multi-file selection');
}
const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
async function lifecycle() {
  const states: ImportUploadCapabilityState[] = [];
  let finish!: (value: ImportUploadCapabilities) => void;
  const cancel = loadImportUploadCapabilities(
    () => new Promise((resolve) => { finish = resolve; }), (s) => states.push(s),
  );
  await flush();
  cancel(); finish(caps); await flush();
  assert.equal(states.length, 1, 'unmounted/stale request must not publish completion');
  loadImportUploadCapabilities(async () => { throw new Error('offline'); }, (s) => states.push(s));
  await flush();
  assert.equal(states.at(-1)?.failed, true);
  loadImportUploadCapabilities(async () => caps, (s) => states.push(s));
  await flush();
  assert.equal(states.at(-1)?.capabilities, caps);
  assert.equal(states.at(-1)?.loading, false);
  assert.equal(states.at(-1)?.failed, false);
  console.log('import upload limits and request lifecycle tests passed');
}
void lifecycle();
