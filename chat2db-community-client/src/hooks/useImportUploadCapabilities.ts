import { useCallback, useEffect, useState } from 'react';
import sqlService from '@/service/sql';
import { isDesktop } from '@/utils/env';
import {
  getImportUploadLimits, loadImportUploadCapabilities,
  type ImportUploadMode, type ImportUploadCapabilityState,
} from '@/utils/importUploadLimits';

/** Fetches configured limits; each retry discards stale capabilities until refreshed. */
export function useImportUploadCapabilities(mode: ImportUploadMode, desktop = isDesktop) {
  const [state, setState] = useState<ImportUploadCapabilityState>({ loading: true, failed: false });
  const [attempt, setAttempt] = useState(0);
  useEffect(() => loadImportUploadCapabilities(
    () => sqlService.getImportUploadCapabilities({}), setState,
  ), [attempt]);
  const retry = useCallback(() => setAttempt((current) => current + 1), []);
  return { ...state, limits: getImportUploadLimits(state.capabilities, mode, desktop), retry };
}
