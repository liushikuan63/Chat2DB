export const IMPORT_MIB = 1024 * 1024;

export interface ImportUploadCapabilities {
  singleFileMaxBytes: number;
  multiFileMaxBytes: number;
  singleLocalFileMaxBytes: number;
  multiLocalFileMaxBytes: number;
  multiTotalMaxBytes: number;
  maxFiles: number;
  stagingMaxFileBytes: number;
  stagingMaxTotalBytes: number;
  multipartMaxFileBytes: number;
  multipartMaxRequestBytes: number;
  multipartOverheadBytes: number;
}

export type ImportUploadMode = 'single' | 'multi';

const byteCount = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;

/** Unknown/custom server limits cannot be guaranteed locally; server validation remains final. */
export function getImportUploadLimits(value: unknown, mode: ImportUploadMode, desktop = false) {
  const caps = value as Partial<ImportUploadCapabilities> | null | undefined;
  const valid = caps && [caps.singleFileMaxBytes, caps.multiFileMaxBytes,
    caps.singleLocalFileMaxBytes, caps.multiLocalFileMaxBytes, caps.multiTotalMaxBytes,
    caps.maxFiles].every(byteCount) && caps.maxFiles! > 0;
  const policyFile = (mode === 'single' ? 50 : 1024) * IMPORT_MIB;
  const selected = desktop
    ? (mode === 'single' ? caps?.singleLocalFileMaxBytes : caps?.multiLocalFileMaxBytes)
    : (mode === 'single' ? caps?.singleFileMaxBytes : caps?.multiFileMaxBytes);
  const maxTotalSizeBytes = valid ? Math.min(caps.multiTotalMaxBytes!, 10 * 1024 * IMPORT_MIB) : 50 * IMPORT_MIB;
  const maxFileBytes = valid ? Math.min(selected!, policyFile, maxTotalSizeBytes) : 50 * IMPORT_MIB;
  return {
    maxFileBytes,
    // UploadLocalFile.fileSize uses binary MiB despite the historical prop name.
    fileSize: maxFileBytes / IMPORT_MIB,
    maxTotalSizeBytes,
    maxFiles: mode === 'single' ? 1 : valid ? Math.min(1000, caps.maxFiles!) : 1000,
    source: valid ? 'server' as const : 'fallback' as const,
    disabled: maxFileBytes === 0 || maxTotalSizeBytes === 0,
  };
}

export type ImportUploadLimits = ReturnType<typeof getImportUploadLimits>;
export type ImportUploadSelectionViolation =
  'empty' | 'unavailable' | 'unknownSize' | 'maxFiles' | 'maxFileBytes' | 'maxTotalSizeBytes';

/** Revalidate the entire selection when capabilities shrink or immediately before submission. */
export function checkImportUploadSelection(
  files: readonly { fileSize?: number; file?: { size: number } }[],
  limits: ImportUploadLimits,
) {
  const violations = new Set<ImportUploadSelectionViolation>();
  if (!files.length) violations.add('empty');
  if (limits.disabled) violations.add('unavailable');
  if (files.length > limits.maxFiles) violations.add('maxFiles');
  let total = 0;
  for (const file of files) {
    const size = file.file?.size ?? file.fileSize;
    if (!byteCount(size)) {
      violations.add('unknownSize');
      continue;
    }
    if (size > limits.maxFileBytes) violations.add('maxFileBytes');
    if (size > limits.maxTotalSizeBytes - total) violations.add('maxTotalSizeBytes');
    total = Math.min(Number.MAX_SAFE_INTEGER, total + size);
  }
  return { valid: violations.size === 0, violations: [...violations] };
}

export interface ImportUploadCapabilityState {
  capabilities?: ImportUploadCapabilities;
  loading: boolean;
  failed: boolean;
}

/** Returns cleanup so a closed/retried view cannot receive stale completion. */
export function loadImportUploadCapabilities(
  request: () => Promise<ImportUploadCapabilities>,
  publish: (state: ImportUploadCapabilityState) => void,
) {
  let active = true;
  publish({ loading: true, failed: false });
  Promise.resolve()
    .then(request)
    .then((capabilities) => {
      if (active) publish({ capabilities, loading: false,
        failed: getImportUploadLimits(capabilities, 'multi').source === 'fallback' });
    })
    .catch(() => {
      if (active) publish({ loading: false, failed: true });
    });
  return () => { active = false; };
}
