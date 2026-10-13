import type { StagedFileOwnership } from '../ImportMappingContent/stagedFileOwnership';

export const claimCurrentUpload = (
  fileId: string,
  sourceId: string,
  generation: number,
  state: { mounted: boolean; generation: number; activeSourceIds: ReadonlySet<string> },
  ownership: StagedFileOwnership,
): boolean => {
  ownership.own(fileId);
  if (!state.mounted || state.generation !== generation || !state.activeSourceIds.has(sourceId)) {
    void ownership.release(fileId);
    return false;
  }
  return true;
};
