import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css }) => {
  return {
    checkboxBody: css`
      .ant-form-item {
        margin-bottom: 0;
      }
      .ant-form-item-control-input{
        min-height: 30px;
      }
    `,
    exportLocationBox: css`
      display: flex;
      align-items: center;
      gap: 4px;
    `,
    iconButton: css`
      flex-shrink: 0;
      border-radius: 6px !important;
    `,
    form: css`
      padding-top: 20px;
    `,
    previewPanel: css`
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding: 12px;
      border: 1px solid rgba(128, 128, 128, 0.25);
      border-radius: 8px;
      margin-bottom: 16px;
    `,
    previewTitle: css`
      font-weight: 600;
    `,
    previewMeta: css`
      display: flex;
      gap: 16px;
      color: rgba(128, 128, 128, 1);
      font-size: 12px;
    `,
    previewRow: css`
      display: flex;
      align-items: center;
      gap: 8px;
      .ant-select {
        flex: 1;
      }
    `,
    previewFileColumn: css`
      width: 120px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    `,
    previewWarning: css`
      color: rgba(230, 162, 60, 1);
      font-size: 12px;
    `,
    modeIndicator: css`
      margin: -8px 0 12px;
      color: rgba(128, 128, 128, 0.75);
      font-size: 12px;
    `,
  };
});
