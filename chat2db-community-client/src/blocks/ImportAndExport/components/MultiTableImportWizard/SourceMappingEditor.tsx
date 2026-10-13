import { useState } from 'react';
import { Alert, Button, Spin } from 'antd';
import i18n from '@/i18n';
import { ImportUnmappedTarget, SKIP_IMPORT_SOURCE_FIELD } from '@/constants/importExport';
import CsvOptionsSections from '../ImportMappingContent/CsvOptionsSections';
import useImportDataSections from '../ImportMappingContent/ImportDataSections';
import { getDuplicateImportMappings } from '../ImportMappingContent/mapping';
import { getImportMappingIssues } from '../ImportExportFile/mappingValidation';
import { DEFAULT_CSV_OPTIONS } from '../../utils/csvOptions';
import type { MultiTableSourceDraft } from './model';

interface Props {
  source: MultiTableSourceDraft;
  onChange: (patch: Partial<MultiTableSourceDraft>) => void;
  onRetry: () => void;
}

const SourceMappingEditor = ({ source, onChange, onRetry }: Props) => {
  const [activeKeys, setActiveKeys] = useState(['mapping', 'preview']);
  const mapping = Object.fromEntries(
    (source.preview?.fileColumns || []).map((name) => [
      name,
      source.columnMappings?.[name] || SKIP_IMPORT_SOURCE_FIELD,
    ]),
  );
  const options = source.csvOptions || DEFAULT_CSV_OPTIONS;
  const issues = getImportMappingIssues(source.preview || null, source.columnMappings || {}, source.unmappedTarget);
  const preview = source.preview?.targetColumns
    ? {
        sourceColumns: source.preview.fileColumns,
        previewData: source.preview.sampleRows,
        targetTableName: source.displayFileName,
        targetColumns: source.preview.targetColumns,
        suggestedMapping: [],
        previewLimit: source.preview.sampleRows.length,
      }
    : null;
  const dataItems = useImportDataSections({
    preview,
    mapping,
    duplicateMappings: getDuplicateImportMappings(mapping),
    blockedColumnNames: new Set(issues.required.map((column) => column.name)),
    unmappedTarget: source.unmappedTarget === 'NULL' ? ImportUnmappedTarget.NULL : ImportUnmappedTarget.DEFAULT,
    emptyAsNull: options.emptyAsNull,
    emptyAsNullLabel: i18n('workspace.importExport.emptyAsNull'),
    loading: source.previewStatus === 'LOADING',
    disabled: source.status !== 'READY',
    onMappingChange: (name, value) =>
      onChange({
        columnMappings: { ...source.columnMappings, [name]: value === SKIP_IMPORT_SOURCE_FIELD ? undefined : value },
      }),
    onUnmappedTargetChange: (unmappedTarget) => onChange({ unmappedTarget }),
    onEmptyAsNullChange: (emptyAsNull) => onChange({ csvOptions: { ...options, emptyAsNull } }),
  });
  return (
    <section aria-label={`${source.displayFileName}: ${i18n('workspace.importExport.fieldMapping')}`}>
      {source.previewStatus === 'LOADING' && (
        <Spin aria-label={i18n('workspace.importExport.multiTable.statusPending')} />
      )}
      {source.previewError && (
        <Alert
          type="error"
          showIcon
          message={source.previewError}
          action={<Button onClick={onRetry}>{i18n('common.button.retry')}</Button>}
        />
      )}
      {(issues.duplicate || issues.empty || issues.required.length > 0) && preview && (
        <Alert
          type="warning"
          showIcon
          message={
            issues.duplicate
              ? i18n('workspace.importExport.duplicateMappingTitle')
              : i18n('workspace.importExport.unmappedRequired')
          }
        />
      )}
      <CsvOptionsSections
        value={options}
        activeKeys={activeKeys}
        dataItems={dataItems}
        disabled={source.status !== 'READY'}
        onActiveKeysChange={setActiveKeys}
        onChange={(csvOptions) => onChange({ csvOptions })}
      />
    </section>
  );
};

export default SourceMappingEditor;
