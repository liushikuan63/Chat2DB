import type { IImportAdmissionReport } from '@/typings/importExport';

export default function ImportAdmissionDetails({ report, translate }: {
  report: IImportAdmissionReport;
  translate: (key: keyof typeof import('@/i18n/en-US').default) => string;
}) {
  const mode = (value: IImportAdmissionReport['effectiveMode']) => translate(
    value === 'ULTRA_FAST' ? 'workspace.importExport.modeBadgeUltra' : 'workspace.importExport.modeBadgeStandard',
  );
  return (
    <div>
      <div>{translate('workspace.importExport.admission.requestedMode')}: {mode(report.requestedMode)}</div>
      <div>{translate('workspace.importExport.parallelAdmissionEffectiveMode')}: {mode(report.effectiveMode)}</div>
      <ul>
        {report.findings.map((finding, index) => (
          <li key={`${finding.code}-${index}`}>
            <strong>{translate(finding.severity === 'BLOCKER'
              ? 'workspace.importExport.admission.blocker' : 'workspace.importExport.admission.degradation')}</strong>
            {`: ${finding.message}`}
            {finding.evidence && <div>{translate('workspace.importExport.admission.evidence')}: {finding.evidence}</div>}
            {finding.remediation && <div>{translate('workspace.importExport.admission.remediation')}: {finding.remediation}</div>}
          </li>
        ))}
      </ul>
    </div>
  );
}
