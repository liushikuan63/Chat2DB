import type { ICsvOptions, IImportPreview } from '@/typings/importExport';
import type { MultiTableSourceDraft } from './model';
import { DEFAULT_CSV_OPTIONS, validateCsvOptions } from '../../utils/csvOptions';
import { getImportMappingIssues, mergeImportColumnMappings } from '../ImportExportFile/mappingValidation';

export const sourceCsvOptions = (source: MultiTableSourceDraft): ICsvOptions =>
  validateCsvOptions(source.csvOptions || { ...DEFAULT_CSV_OPTIONS });

export const sourcePreviewKey = (source: MultiTableSourceDraft): string | undefined => {
  if (!source.fileId || !source.targetKey) return undefined;
  try {
    return JSON.stringify([source.fileId, source.targetKey, sourceCsvOptions(source)]);
  } catch {
    return undefined;
  }
};

export const sourcePreviewReady = (source: MultiTableSourceDraft): boolean => {
  const key = sourcePreviewKey(source);
  if (!key || source.previewStatus !== 'READY' || !source.preview?.targetColumns?.length || source.previewKey !== key)
    return false;
  const validTargets = new Set(source.preview.targetColumns.map((column) => column.name));
  if (
    Object.entries(source.columnMappings || {}).some(
      ([name, target]) => !!target && (!source.preview!.fileColumns.includes(name) || !validTargets.has(target)),
    )
  )
    return false;
  const issues = getImportMappingIssues(source.preview, source.columnMappings || {}, source.unmappedTarget);
  return !issues.empty && !issues.duplicate && issues.required.length === 0;
};

export const updateSourceSelection = (
  source: MultiTableSourceDraft,
  patch: Partial<MultiTableSourceDraft>,
): MultiTableSourceDraft => {
  const next = { ...source, ...patch };
  if (sourcePreviewKey(next) === sourcePreviewKey(source)) return next;
  return {
    ...next,
    preview: undefined,
    previewKey: undefined,
    previewStatus: undefined,
    previewError: undefined,
    columnMappings: {},
  };
};

export const acceptSourcePreview = (source: MultiTableSourceDraft, key: string, preview: IImportPreview) =>
  key === sourcePreviewKey(source)
    ? {
        ...source,
        preview,
        previewKey: key,
        previewStatus: 'READY' as const,
        previewError: undefined,
        columnMappings: mergeImportColumnMappings(preview, source.columnMappings || {}),
      }
    : source;

export const retrySourceUpload = (source: MultiTableSourceDraft): MultiTableSourceDraft =>
  source.status === 'ERROR' ? { ...source, status: 'PENDING', error: undefined } : source;

/** Deduplicates pending requests and suppresses results superseded by a changed selection. */
export const createSourcePreviewRequests = () => {
  const active = new Map<string, { key: string; cancel: () => void }>();
  return {
    invalidate: (id?: string) => {
      if (id === undefined) {
        active.forEach((entry) => entry.cancel());
        active.clear();
      } else {
        active.get(id)?.cancel();
        active.delete(id);
      }
    },
    run: (
      id: string,
      key: string,
      load: () => Promise<IImportPreview>,
      success: (preview: IImportPreview) => void,
      failure: (error: unknown) => void,
    ) => {
      if (active.get(id)?.key === key) return false;
      active.get(id)?.cancel();
      let cancelled = false;
      const entry = {
        key,
        cancel: () => {
          cancelled = true;
        },
      };
      active.set(id, entry);
      void load()
        .then(
          (preview) => {
            if (!cancelled) success(preview);
          },
          (error) => {
            if (!cancelled) failure(error);
          },
        )
        .finally(() => {
          if (active.get(id) === entry) active.delete(id);
        });
      return true;
    },
  };
};
