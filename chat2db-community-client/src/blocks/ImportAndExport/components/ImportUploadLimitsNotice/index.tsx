import { Alert, Button } from 'antd';
import i18n from '@/i18n';
import { IMPORT_MIB, type ImportUploadLimits } from '@/utils/importUploadLimits';

export default function ImportUploadLimitsNotice({ capabilities, invalid }: {
  capabilities: { limits: ImportUploadLimits; loading: boolean; failed: boolean; retry: () => void };
  invalid?: boolean;
}) {
  const { limits, loading, failed, retry } = capabilities;
  return (
    <div>
      <div>{i18n('workspace.importExport.uploadLimits', limits.maxFileBytes / IMPORT_MIB,
        limits.maxTotalSizeBytes / IMPORT_MIB, limits.maxFiles)}</div>
      {failed && <Alert type="warning" showIcon message={i18n('workspace.importExport.uploadLimitsFallback')}
        action={<Button size="small" disabled={loading} onClick={retry}>{i18n('common.button.retry')}</Button>}
                 />}
      {invalid && <Alert type="error" showIcon message={i18n('workspace.importExport.uploadSelectionInvalid')} />}
    </div>
  );
}
