import assert from 'node:assert/strict';
import { test } from 'node:test';
import { renderToStaticMarkup } from 'react-dom/server';
import { JSDOM } from 'jsdom';
import ImportAdmissionDetails from './index';
import type { IImportAdmissionReport } from '@/typings/importExport';

test('admission panel exposes both modes and actionable findings as escaped text', () => {
  const report: IImportAdmissionReport = {
    verdict: 'PARALLEL_FORBIDDEN', requestedMode: 'ULTRA_FAST', effectiveMode: 'STANDARD',
    parallelAllowed: false, fileFormat: 'CSV', fileSizeBytes: 100, dataRows: 3, fullScan: true,
    relationshipRiskAccepted: true, findings: [
      { code: 'C3', severity: 'BLOCKER', message: 'Quoted record spans lines',
        evidence: '<script>bad()</script>', remediation: 'Use standard mode' },
      { code: 'G0', severity: 'DEGRADATION', message: 'Small file' },
    ],
  };
  const translate = (key: string) => ({
    'workspace.importExport.admission.requestedMode': 'Requested',
    'workspace.importExport.parallelAdmissionEffectiveMode': 'Effective',
    'workspace.importExport.modeBadgeUltra': 'Fast',
    'workspace.importExport.modeBadgeStandard': 'Standard',
    'workspace.importExport.admission.blocker': 'Blocked',
    'workspace.importExport.admission.degradation': 'Reduced performance',
    'workspace.importExport.admission.evidence': 'Evidence',
    'workspace.importExport.admission.remediation': 'Suggested action',
  }[key] || key);
  const document = new JSDOM(renderToStaticMarkup(<ImportAdmissionDetails report={report} translate={translate} />))
    .window.document;
  assert.ok(document.body.textContent?.includes('Requested: Fast'));
  assert.ok(document.body.textContent?.includes('Effective: Standard'));
  assert.deepEqual(Array.from(document.querySelectorAll('strong'), (node) => node.textContent),
    ['Blocked', 'Reduced performance']);
  assert.ok(document.body.textContent?.includes('Quoted record spans lines'));
  assert.ok(document.body.textContent?.includes('Evidence: <script>bad()</script>'));
  assert.ok(document.body.textContent?.includes('Suggested action: Use standard mode'));
  assert.equal(document.querySelector('script'), null);
});
