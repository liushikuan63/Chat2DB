import {
  acceptSourcePreview,
  createSourcePreviewRequests,
  retrySourceUpload,
  sourceCsvOptions,
  sourcePreviewKey,
  sourcePreviewReady,
  updateSourceSelection,
} from './sourcePreview';
import { DEFAULT_CSV_OPTIONS } from '../../utils/csvOptions';
import type { IImportPreview } from '@/typings/importExport';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { getMultiTableSourceIssues, multiTableTargetKey, type MultiTableSourceDraft } from './model';
import { buildMultiTableImportParams, defaultMultiTableImportSettingsFor } from './taskParams';
import { DatabaseTypeCode } from '@/constants/common';
import { ImportExportType } from '@/constants/importExport';

const target = { databaseName: 'app', tableName: 'users' };
const stagedSource = {
  id: 'one',
  selection: { fileName: 'users.csv' },
  displayFileName: 'users.csv',
  fileId: 'opaque-file',
  status: 'READY' as const,
  targetKey: multiTableTargetKey(target),
};

test('staged files with a target cannot be ready without their own preview', () => {
  assert.equal(getMultiTableSourceIssues([stagedSource]).ready, false);
});

test('payload builder refuses a staged source before preview and mapping validation', () => {
  assert.equal(
    buildMultiTableImportParams({
      boundInfo: {
        dataSourceId: 7,
        databaseName: 'app',
        databaseType: DatabaseTypeCode.MYSQL,
        targetScope: 'DATABASE',
        type: ImportExportType.IMPORT,
      },
      sources: [stagedSource],
      targets: [target],
      dependencies: [],
      settings: defaultMultiTableImportSettingsFor(DatabaseTypeCode.MYSQL),
    }),
    null,
  );
});

const preview: IImportPreview = {
  fileColumns: ['key', 'name'],
  sampleRows: [['1', 'one']],
  missingTableColumns: [],
  columnMatches: [
    { fileColumn: 'key', tableColumn: 'id', matched: true },
    { fileColumn: 'name', tableColumn: 'name', matched: true },
  ],
  targetColumns: [
    { name: 'id', dataType: 'INT', nullable: false, autoIncrement: false, defaultValue: null, comment: null },
    { name: 'name', dataType: 'VARCHAR', nullable: true, autoIncrement: false, defaultValue: null, comment: null },
  ],
};
const readySource = (): MultiTableSourceDraft => ({
  ...stagedSource,
  preview,
  previewStatus: 'READY',
  previewKey: sourcePreviewKey(stagedSource),
  columnMappings: { key: 'id', name: 'name' },
});

test('current preview and all required mappings allow readiness', () => {
  assert.equal(sourcePreviewReady(readySource()), true);
  assert.equal(getMultiTableSourceIssues([readySource()]).ready, true);
});

test('required empty duplicate and foreign-target mappings each block submission', () => {
  for (const columnMappings of [
    { key: undefined },
    {},
    { key: 'id', name: 'id' },
    { key: 'gone', name: 'name' },
    { notInFile: 'id' },
  ]) {
    const source = { ...readySource(), columnMappings };
    assert.equal(sourcePreviewReady(source), false);
  }
});

test('option validation and preview signature prevent stale readiness', () => {
  assert.equal(sourcePreviewReady({ ...readySource(), csvOptions: { ...DEFAULT_CSV_OPTIONS, delimiter: ';' } }), false);
  assert.equal(
    sourcePreviewReady({ ...readySource(), csvOptions: { ...DEFAULT_CSV_OPTIONS, delimiter: 'bad' } }),
    false,
  );
  assert.equal(sourcePreviewReady({ ...readySource(), previewKey: undefined }), false);
  assert.equal(sourcePreviewReady({ ...readySource(), previewStatus: 'LOADING' }), false);
  assert.equal(
    sourcePreviewKey(stagedSource),
    JSON.stringify(['opaque-file', multiTableTargetKey(target), DEFAULT_CSV_OPTIONS]),
  );
});

test('target or options change invalidates preview and mapping but retains staged selection', () => {
  for (const patch of [
    { targetKey: 'another-target' },
    { csvOptions: { ...DEFAULT_CSV_OPTIONS, hasHeader: false, dataStartRow: 1 } },
  ]) {
    const changed = updateSourceSelection(readySource(), patch);
    assert.equal(changed.fileId, 'opaque-file');
    assert.equal(changed.selection, stagedSource.selection);
    assert.equal(changed.preview, undefined);
    assert.equal(changed.previewKey, undefined);
    assert.deepEqual(changed.columnMappings, {});
    assert.equal(sourcePreviewReady(changed), false);
  }
});

test('stale preview cannot replace mapping for a changed target', () => {
  const changed = updateSourceSelection(readySource(), { targetKey: 'new-target' });
  assert.equal(acceptSourcePreview(changed, sourcePreviewKey(stagedSource)!, preview), changed);
});

test('each source serializes its own CSV options and mapping with submission and file IDs intact', () => {
  const otherTarget = { databaseName: 'app', tableName: 'orders' };
  const first = readySource();
  const second = {
    ...first,
    id: 'two',
    fileId: 'second-file',
    targetKey: multiTableTargetKey(otherTarget),
    csvOptions: { ...DEFAULT_CSV_OPTIONS, delimiter: ';', hasHeader: false, dataStartRow: 1 },
    columnMappings: { key: 'id', name: undefined },
  };
  second.previewKey = sourcePreviewKey(second);
  const result = buildMultiTableImportParams({
    boundInfo: {
      dataSourceId: 7,
      databaseName: 'app',
      databaseType: DatabaseTypeCode.MYSQL,
      targetScope: 'DATABASE',
      type: ImportExportType.IMPORT,
    },
    sources: [first, second],
    targets: [target, otherTarget],
    dependencies: [],
    clientSubmissionId: 'stable-attempt',
    settings: defaultMultiTableImportSettingsFor(DatabaseTypeCode.MYSQL),
  });
  assert.equal(result?.clientSubmissionId, 'stable-attempt');
  assert.deepEqual(
    result?.tableSources?.map((source) => ({
      fileId: source.fileId,
      delimiter: source.csvOptions?.delimiter,
      hasHeader: source.csvOptions?.hasHeader,
      mappings: source.columnMappings,
    })),
    [
      {
        fileId: 'opaque-file',
        delimiter: ',',
        hasHeader: true,
        mappings: [
          { sourceColumn: 'key', targetColumn: 'id' },
          { sourceColumn: 'name', targetColumn: 'name' },
        ],
      },
      {
        fileId: 'second-file',
        delimiter: ';',
        hasHeader: false,
        mappings: [{ sourceColumn: 'key', targetColumn: 'id' }],
      },
    ],
  );
  assert.equal(sourceCsvOptions(first).delimiter, ',');
});

const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
};
const settle = () => new Promise<void>((resolve) => setImmediate(resolve));

test('pending preview is deduplicated and superseded result does not overwrite the current selection', async () => {
  const requests = createSourcePreviewRequests();
  const old = deferred<IImportPreview>();
  const next = deferred<IImportPreview>();
  const results: string[] = [];
  let calls = 0;
  const load = () => {
    calls++;
    return old.promise;
  };
  assert.equal(
    requests.run(
      'one',
      'old',
      load,
      () => results.push('old'),
      () => results.push('old-error'),
    ),
    true,
  );
  assert.equal(
    requests.run(
      'one',
      'old',
      load,
      () => results.push('duplicate'),
      () => {},
    ),
    false,
  );
  requests.run(
    'one',
    'new',
    () => next.promise,
    () => results.push('new'),
    () => results.push('new-error'),
  );
  old.resolve(preview);
  next.resolve(preview);
  await settle();
  assert.equal(calls, 1);
  assert.deepEqual(results, ['new']);
});

test('scope cleanup discards late errors without automatic retry', async () => {
  const requests = createSourcePreviewRequests();
  const old = deferred<IImportPreview>();
  let errors = 0;
  requests.run(
    'one',
    'old',
    () => old.promise,
    () => assert.fail('stale success'),
    () => errors++,
  );
  requests.invalidate();
  old.reject(new Error('old failure'));
  await settle();
  assert.equal(errors, 0);
});

test('explicit upload retry changes only the failed step and preserves successful sources', () => {
  const succeeded = readySource();
  const failed = {
    ...succeeded,
    id: 'two',
    status: 'ERROR' as const,
    fileId: undefined,
    error: 'temporary upload error',
  };
  const retried = retrySourceUpload(failed);
  assert.equal(retried.status, 'PENDING');
  assert.equal(retried.error, undefined);
  assert.equal(retried.targetKey, failed.targetKey);
  assert.equal(retried.selection, failed.selection);
  assert.equal(retrySourceUpload(succeeded), succeeded);
  assert.equal(retrySourceUpload(retried), retried);
});

test('NULL strategy requires a mapping for non-null targets with a default, DEFAULT does not', () => {
  const source = readySource();
  source.preview = {
    ...preview,
    targetColumns: [
      ...preview.targetColumns!,
      {
        name: 'state',
        dataType: 'VARCHAR',
        nullable: false,
        autoIncrement: false,
        defaultValue: "'NEW'",
        comment: null,
      },
    ],
  };
  assert.equal(sourcePreviewReady({ ...source, unmappedTarget: 'DEFAULT' }), true);
  assert.equal(sourcePreviewReady({ ...source, unmappedTarget: 'NULL' }), false);
});
