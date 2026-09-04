import { memo, useMemo, useState, forwardRef, ForwardedRef, useImperativeHandle, useEffect } from 'react';
import { useStyles } from './style';
import UploadLocalFile from '@/components/UploadLocalFile';
import { Form, Input, Select, InputNumber, Switch, Tooltip } from 'antd';
import i18n from '@/i18n';
import { useImportExportStore } from '@/store/importExport';
import { IconButton } from '@chat2db/ui';
import { ImportExportType, ImportExportFileType, ImportExportTaskType } from '@/constants/importExport';
import importExportServices, { ExportTaskParams, ImportTaskParams } from '@/service/importExport';
import { IImportPreview, ImportExecutionMode } from '@/typings/importExport';
import { isDesktop } from '@/utils/env';
import jcefApi from '@/jcef';
import { CircleHelp } from 'lucide-react';
import { buildTaskParams, initialFileType, type ImportExportFormValue } from './taskParams';

interface IProps {
  className?: string;
  setIsReady?: (p: boolean) => void;
}

export interface ImportExportFileRef {
  getValues: () => ExportTaskParams | ImportTaskParams | null;
}

const exportTypeOptions = [
  { label: 'CSV', value: ImportExportFileType.CSV, accept: '.csv' },
  { label: 'XLSX', value: ImportExportFileType.XLSX, accept: '.xlsx' },
  { label: 'XLS', value: ImportExportFileType.XLS, accept: '.xls' },
  { label: 'JSON', value: ImportExportFileType.JSON, accept: '.json' },
  { label: 'NDJSON', value: ImportExportFileType.NDJSON, accept: '.ndjson' },
  { label: 'Markdown', value: ImportExportFileType.MARKDOWN, accept: '.md' },
  { label: 'SQL', value: ImportExportFileType.SQL, accept: '.sql' },
];

// The import backend parses these formats; NDJSON/Markdown are export-only.
const importTypeOptions = exportTypeOptions.filter(
  (option) =>
    option.value !== ImportExportFileType.NDJSON && option.value !== ImportExportFileType.MARKDOWN,
);

// Formats that can be checkpointed for resumable export.
const checkpointableFormats = [
  ImportExportFileType.CSV,
  ImportExportFileType.NDJSON,
  ImportExportFileType.MARKDOWN,
  ImportExportFileType.SQL,
];

const ImportExportFile = forwardRef((props: IProps, ref: ForwardedRef<ImportExportFileRef>) => {
  const { setIsReady } = props;
  const { styles } = useStyles();
  const [form] = Form.useForm();
  const { importExportDataBoundInfo } = useImportExportStore((state) => {
    return {
      importExportDataBoundInfo: state.importExportDataBoundInfo,
    };
  });
  const defaultFileType = importExportDataBoundInfo
    ? initialFileType(importExportDataBoundInfo)
    : ImportExportFileType.CSV;
  const [fileUrlList, setFileUrlList] = useState<string[]>([]);
  const [exportLocation, setExportLocation] = useState<string>('');
  const [importPreview, setImportPreview] = useState<IImportPreview | null>(null);
  const [columnMappings, setColumnMappings] = useState<Record<string, string | undefined>>({});
  const [formValue, setFormValue] = useState<ImportExportFormValue>({
    exportType: defaultFileType,
    containsHeader: true,
  });
  const [mode, setMode] = useState<ImportExecutionMode>('STANDARD');

  const isImport = importExportDataBoundInfo?.type === ImportExportType.IMPORT;
  const isExport = importExportDataBoundInfo?.type === ImportExportType.EXPORT;
  const isTableTarget = importExportDataBoundInfo?.targetScope === 'TABLE';
  const isSqlExport = isExport && !!importExportDataBoundInfo?.sqlExportScope;
  const fileTypeOptions = importExportDataBoundInfo?.fileType
    ? exportTypeOptions.filter((option) => option.value === importExportDataBoundInfo.fileType)
    : isImport
      ? importTypeOptions
      : exportTypeOptions;

  useEffect(() => {
    if (importExportDataBoundInfo) {
      const { dataSourceName, databaseName, schemaName, tableName } = importExportDataBoundInfo;
      const tableNameDisplay = [dataSourceName, databaseName, schemaName, tableName].filter(Boolean).join('/');
      const exportType = initialFileType(importExportDataBoundInfo);
      const initialValues: ImportExportFormValue = {
        exportType,
        containsHeader: true,
      };
      setFormValue(initialValues);
      setFileUrlList([]);
      setExportLocation('');
      setImportPreview(null);
      setColumnMappings({});
      setMode('STANDARD');
      form.resetFields();
      form.setFieldsValue({
        tableNameDisplay: tableNameDisplay,
        ...initialValues,
      });
    }
  }, [form, importExportDataBoundInfo]);

  // Gets the corresponding file type based on the export type
  const uploadLocalFileAccept = useMemo(() => {
    return formValue.exportType ? exportTypeOptions.find((item) => item.value === formValue.exportType)?.accept : '';
  }, [formValue.exportType]);

  // file list changes
  useEffect(() => {
    if (isImport) {
      setIsReady?.(!!fileUrlList.length);
    }
  }, [fileUrlList, isImport, setIsReady]);

  // Previews the selected import file once both the file and the format are known, so the
  // column mapping panel below reflects what the backend will actually import.
  const importSourceFile = fileUrlList[0] || '';
  const previewableFormat =
    isImport && isTableTarget && importSourceFile && formValue.exportType !== ImportExportFileType.SQL;
  useEffect(() => {
    if (!previewableFormat || !importExportDataBoundInfo) {
      setImportPreview(null);
      return () => {};
    }
    let cancelled = false;
    const { dataSourceId, databaseName, schemaName, tableName } = importExportDataBoundInfo;
    importExportServices
      .previewImport({
        dataSourceId,
        databaseName,
        schemaName,
        taskType: ImportExportTaskType.DATA_FILE_IMPORT,
        format: formValue.exportType,
        tableName,
        sourceFile: importSourceFile,
        options:
          formValue.exportType === ImportExportFileType.CSV
            ? {
                charset: formValue.charset || undefined,
                delimiter: formValue.delimiter || undefined,
                quoteChar: formValue.quoteChar || undefined,
              }
            : undefined,
      })
      .then((preview) => {
        if (cancelled) return;
        setImportPreview(preview);
        const initial: Record<string, string | undefined> = {};
        preview.columnMatches.forEach((match) => {
          initial[match.fileColumn] = match.matched ? match.tableColumn : undefined;
        });
        setColumnMappings(initial);
      })
      .catch(() => {
        if (!cancelled) setImportPreview(null);
      });
    return () => {
      cancelled = true;
    };
  }, [
    previewableFormat,
    importSourceFile,
    formValue.exportType,
    formValue.charset,
    formValue.delimiter,
    formValue.quoteChar,
    importExportDataBoundInfo,
  ]);

  const targetColumnOptions = useMemo(() => {
    if (!importPreview) return [];
    const names = new Set<string>();
    importPreview.columnMatches.forEach((match) => {
      if (match.tableColumn) names.add(match.tableColumn);
    });
    importPreview.missingTableColumns.forEach((name) => names.add(name));
    return Array.from(names)
      .sort()
      .map((name) => ({ label: name, value: name }));
  }, [importPreview]);

  useEffect(() => {
    if (isExport) {
      setIsReady?.(!isDesktop || !!exportLocation);
    }
  }, [exportLocation, isExport, setIsReady]);

  const handleFileUrlListChange = (_fileUrlList) => {
    setFileUrlList(
      _fileUrlList.map((item) => item.filePath).filter((filePath): filePath is string => !!filePath),
    );
  };

  useImperativeHandle(ref, () => ({
    getValues: () => {
      if (!importExportDataBoundInfo) return null;
      return buildTaskParams({
        boundInfo: importExportDataBoundInfo,
        formValue,
        mode,
        sourceFile: fileUrlList[0] || '',
        exportLocation,
        desktop: isDesktop,
        importPreview,
        columnMappings,
        checkpointableFormats,
      });
    },
  }));

  const handleFormChange = (changedValues, allValues) => {
    const nextValue: ImportExportFormValue = {
      ...formValue,
      ...allValues,
    };
    if (
      changedValues.compression ||
      (changedValues.exportType && !checkpointableFormats.includes(changedValues.exportType))
    ) {
      form.setFieldValue('checkpointRows', undefined);
      nextValue.checkpointRows = undefined;
    }
    setFormValue(nextValue);
  };

  const handleSelectExportLocation = async () => {
    const fileName = await jcefApi?.selectDirectory();
    if (!fileName) return;
    setExportLocation(fileName);
  };

  const handleModeToggle = (checked: boolean) => {
    setMode(checked ? 'ULTRA_FAST' : 'STANDARD');
  };

  const ultraModeTooltip = (
    <div className={styles.modeTooltip}>
      <div>{i18n('workspace.importExport.ultraModeHint')}</div>
      <div className={styles.modeTooltipTitle}>
        {i18n('workspace.importExport.ultraModeConfirmIntro')}
      </div>
      <ul>
        <li>{i18n('workspace.importExport.ultraModeBenefit1')}</li>
        <li>{i18n('workspace.importExport.ultraModeBenefit2')}</li>
        <li>{i18n('workspace.importExport.ultraModeBenefit3')}</li>
        <li>{i18n('workspace.importExport.ultraModeRisk1')}</li>
        <li>{i18n('workspace.importExport.ultraModeRisk2')}</li>
        <li>{i18n('workspace.importExport.ultraModeRisk3')}</li>
      </ul>
    </div>
  );

  return (
    <Form
      className={styles.form}
      layout="vertical"
      form={form}
      autoComplete="off"
      onValuesChange={handleFormChange}
      initialValues={formValue}
    >
      <Form.Item label={`${i18n('workspace.importExport.fileType')}:`} name="exportType">
        <Select disabled={!!importExportDataBoundInfo?.fileType} options={fileTypeOptions} />
      </Form.Item>
      {isExport && isDesktop && (
        <Form.Item label={`${i18n('workspace.importExport.exportLocation')}:`} name="exportLocation">
          <div className={styles.exportLocationBox}>
            <Input
              autoComplete="off"
              disabled
              placeholder={i18n('workspace.importExport.exportLocation')}
              value={exportLocation}
            />
            <IconButton
              className={styles.iconButton}
              size={{ boxSize: 30, iconSize: 22, borderRadius: 6 }}
              code="icon-folder"
              title={i18n('workspace.importExport.exportLocation')}
              onClick={handleSelectExportLocation}
            />
          </div>
        </Form.Item>
      )}
      {isExport && !isSqlExport && (
        <>
          <Form.Item label={`${i18n('workspace.importExport.compression')}:`} name="compression">
            <Select
              allowClear
              placeholder={i18n('workspace.importExport.off')}
              options={[
                { label: 'GZIP', value: 'GZIP' },
              ]}
            />
          </Form.Item>
          {!formValue.compression && checkpointableFormats.includes(formValue.exportType) && (
            <Form.Item label={`${i18n('workspace.importExport.checkpoint')}:`} name="checkpointRows">
              <Select
                allowClear
                placeholder={i18n('workspace.importExport.off')}
                options={[
                  { label: '10,000', value: 10000 },
                  { label: '100,000', value: 100000 },
                  { label: '1,000,000', value: 1000000 },
                ]}
              />
            </Form.Item>
          )}
        </>
      )}
      {isImport && (
        <Form.Item label={`${i18n('workspace.importExport.sourceFile')}:`}>
          <UploadLocalFile fileUrlListChange={handleFileUrlListChange} accept={uploadLocalFileAccept} />
        </Form.Item>
      )}
      {isImport && formValue.exportType === ImportExportFileType.CSV && (
        <>
          <Form.Item label={`${i18n('workspace.importExport.charset')}:`} name="charset">
            <Input autoComplete="off" placeholder={i18n('workspace.importExport.auto')} />
          </Form.Item>
          <Form.Item label={`${i18n('workspace.importExport.delimiter')}:`} name="delimiter">
            <Input autoComplete="off" maxLength={1} placeholder={i18n('workspace.importExport.auto')} />
          </Form.Item>
          <Form.Item label={`${i18n('workspace.importExport.quoteChar')}:`} name="quoteChar">
            <Input autoComplete="off" maxLength={1} placeholder="&quot;" />
          </Form.Item>
          <Form.Item label={`${i18n('workspace.importExport.skipRows')}:`} name="skipRows">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label={`${i18n('workspace.importExport.nullString')}:`} name="nullString">
            <Input autoComplete="off" placeholder="\\N" />
          </Form.Item>
          <Form.Item label={`${i18n('workspace.importExport.onError')}:`} name="onError">
            <Select
              allowClear
              placeholder="ABORT"
              options={[
                { label: 'ABORT', value: 'ABORT' },
                { label: 'SKIP', value: 'SKIP' },
              ]}
            />
          </Form.Item>
          {formValue.onError === 'SKIP' && (
            <Form.Item label={`${i18n('workspace.importExport.maxErrors')}:`} name="maxErrors">
              <InputNumber min={1} style={{ width: '100%' }} />
            </Form.Item>
          )}
        </>
      )}
      {isImport && importPreview && (
        <div className={styles.previewPanel}>
          <div className={styles.previewTitle}>{i18n('workspace.importExport.preview.columnMapping')}</div>
          {(importPreview.detectedCharset || importPreview.detectedDelimiter) && (
            <div className={styles.previewMeta}>
              {importPreview.detectedCharset && (
                <span>
                  {i18n('workspace.importExport.charset')}: {importPreview.detectedCharset}
                </span>
              )}
              {importPreview.detectedDelimiter && (
                <span>
                  {i18n('workspace.importExport.delimiter')}: {importPreview.detectedDelimiter}
                </span>
              )}
            </div>
          )}
          {importPreview.columnMatches.map((match) => (
            <div key={match.fileColumn} className={styles.previewRow}>
              <span className={styles.previewFileColumn} title={match.fileColumn}>
                {match.fileColumn}
              </span>
              <Select
                allowClear
                size="small"
                placeholder={i18n('workspace.importExport.preview.ignore')}
                value={columnMappings[match.fileColumn]}
                options={targetColumnOptions}
                onChange={(value) =>
                  setColumnMappings((previous) => ({ ...previous, [match.fileColumn]: value }))
                }
              />
            </div>
          ))}
          {importPreview.missingTableColumns.length > 0 && (
            <div className={styles.previewWarning}>
              {i18n('workspace.importExport.preview.unmatchedColumns')}:{' '}
              {importPreview.missingTableColumns.join(', ')}
            </div>
          )}
        </div>
      )}
      <Form.Item
        label={`${
          isTableTarget
            ? i18n('workspace.importExport.targetTable')
            : i18n('workspace.importExport.executionEnvironment')
        }:`}
        name="tableNameDisplay"
      >
        <Input autoComplete="off" disabled />
      </Form.Item>
      <Form.Item label={i18n('workspace.importExport.ultraMode')}>
        <div className={styles.modeControl}>
          <Switch checked={mode === 'ULTRA_FAST'} onChange={handleModeToggle} />
          <Tooltip title={ultraModeTooltip} mouseEnterDelay={0.2} overlayStyle={{ maxWidth: 440 }}>
            <button
              aria-label={i18n('workspace.importExport.ultraModeHint')}
              className={styles.modeHelpButton}
              type="button"
            >
              <CircleHelp aria-hidden size={16} />
            </button>
          </Tooltip>
        </div>
      </Form.Item>
      <div className={styles.modeIndicator}>
        {mode === 'ULTRA_FAST'
          ? i18n('workspace.importExport.modeBadgeUltra')
          : i18n('workspace.importExport.modeBadgeStandard')}
      </div>
      {/* <Form.Item name="containsHeader" valuePropName="checked">
        <Checkbox>{i18n('workspace.importExport.containsHeader')}</Checkbox>
      </Form.Item> */}
    </Form>
  );
});

export default memo(ImportExportFile);
