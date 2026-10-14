// UI contract only. Metadata/upload/preview are mocked; no database/task execution is claimed.
const { chromium } = require(process.env.C2D_PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const fixture = JSON.parse(fs.readFileSync(process.env.C2D_BROWSER_FIXTURE, 'utf8'));
const frontend = new URL(process.env.C2D_BROWSER_FRONTEND_URL || 'http://127.0.0.1:8889');
if (frontend.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(frontend.hostname) ||
    fixture.database !== 'c2d_ui_fixture_20261013') throw Error('Named local UI fixture required');
const out = path.resolve(process.env.C2D_BROWSER_OUTPUT || 'target/import-browser-evidence/ui-contract');
fs.mkdirSync(out, { recursive: true });
(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const errors = [];
  const paths = [];
  let uploads = 0;
  let previews = 0;
  const mib = 1024 * 1024;
  page.on('pageerror', (error) => errors.push(error.message));
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    paths.push(pathname);
    const reply = (data) => route.fulfill({ json: { success: true, data } });
    if (pathname === '/api/rdb/database/list') return reply([
      { name: fixture.database, description: 'Mocked UI contract database', count: 1 },
    ]);
    if (pathname === '/api/rdb/ddl/database_schema_list') {
      return reply({ databases: [{ name: fixture.database, schemas: [] }], schemas: [] });
    }
    if (pathname === '/api/rdb/table/list') {
      return reply({ data: [{ name: 'C2D_UI_SINGLE' }], total: 1, pageNo: 1, pageSize: 100, hasNextPage: false });
    }
    if (pathname === '/api/rdb/table/table_list') return reply([{ name: 'C2D_UI_SINGLE' }]);
    if (pathname === '/api/rdb/import_preview/capabilities') return reply({
      singleFileMaxBytes: 50 * mib, multiFileMaxBytes: 1024 * mib,
      singleLocalFileMaxBytes: 50 * mib, multiLocalFileMaxBytes: 1024 * mib,
      multiTotalMaxBytes: 10 * 1024 * mib, maxFiles: 1000,
    });
    if (pathname === '/api/rdb/import_preview/upload') { uploads += 1; return reply('ui-contract-staged-file'); }
    if (pathname === '/api/rdb/import_preview/release') return reply(null);
    if (pathname === '/api/tasks/import/preview') {
      previews += 1;
      if (previews === 1) return route.fulfill({ json: {
        success: false, errorCode: 'task.import.preview.failed', errorMessage: 'Preview fixture failure',
      } });
      const input = request.postDataJSON();
      return reply({
        fileColumns: ['KEY', 'NOTE'], columnMatches: [
          { fileColumn: 'KEY', matched: false }, { fileColumn: 'NOTE', tableColumn: 'NOTE', matched: true },
        ], sampleRows: [['1', '中文']], missingTableColumns: ['ID'], targetColumns: [
          { name: 'ID', nullable: false, autoIncrement: false, defaultValue: null, dataType: 'INT' },
          { name: 'NOTE', nullable: true, autoIncrement: false, defaultValue: null, dataType: 'VARCHAR' },
        ], parallelAdmission: {
          verdict: 'PARALLEL_FORBIDDEN', requestedMode: input.mode, effectiveMode: 'STANDARD',
          parallelAllowed: false, fileFormat: 'CSV', fileSizeBytes: 20, dataRows: 1, fullScan: true,
          relationshipRiskAccepted: false, findings: [{ code: 'C3', severity: 'BLOCKER',
            message: 'Quoted record spans lines', evidence: 'Record 2 has a newline', remediation: 'Use standard mode' }],
        },
      });
    }
    // No task submission, fixture creation, SQL execution or other authenticated write reaches the backend.
    if (request.method() !== 'GET') return route.abort('blockedbyclient');
    return route.continue();
  });
  try {
    await page.goto(frontend.origin, { waitUntil: 'domcontentloaded' });
    const treeTitle = (title) => page.getByText(title, { exact: true }).first()
      .locator('../..').locator('[data-chat2db-general-can-copy-element]');
    await treeTitle('C2D_BROWSER_MYSQL').dblclick();
    await treeTitle(fixture.database).dblclick();
    await page.getByText('表', { exact: true }).first().locator('..').locator('[class*="switcherIconBox"]').click();
    await treeTitle('C2D_UI_SINGLE').click({ button: 'right' });
    await page.getByText('导出数据', { exact: true }).first().click();
    let dialog = page.getByRole('dialog');
    for (const format of ['SQL', 'CSV']) {
      await dialog.getByRole('combobox').first().press('ArrowDown');
      await page.locator('.ant-select-dropdown:visible .ant-select-item-option[title="' + format + '"]').click();
      const exportStart = dialog.getByRole('button', { name: /开\s*始/ });
      await page.waitForFunction((button) => !button.disabled, await exportStart.elementHandle());
      assert.equal(await exportStart.isDisabled(), false);
    }
    await dialog.locator('.ant-modal-close').click();
    await treeTitle('C2D_UI_SINGLE').click({ button: 'right' });
    await page.getByText('导入数据', { exact: true }).first().click();
    dialog = page.getByRole('dialog');
    await dialog.locator('input[name=file]').setInputFiles({ name: 'single.csv', mimeType: 'text/csv',
      buffer: Buffer.from('KEY,NOTE\n1,中文\n') });
    await dialog.getByText('Preview fixture failure', { exact: true }).waitFor();
    await dialog.getByRole('button', { name: /重\s*试/ }).click();
    await dialog.locator('span[title="KEY"]').waitFor();
    assert.equal(uploads, 1);
    assert.equal(previews, 2);
    const start = dialog.getByRole('button', { name: /开\s*始/ });
    assert.equal(await start.isDisabled(), true);
    await dialog.locator('span[title="KEY"]').locator('..').getByRole('combobox').click();
    await page.locator('.ant-select-dropdown:visible .ant-select-item-option[title="ID"]').click();
    assert.equal(await start.isDisabled(), false);
    await dialog.getByRole('switch').click();
    await dialog.getByText('Quoted record spans lines', { exact: false }).waitFor();
    const text = await dialog.innerText();
    assert.ok(text.includes('请求模式') && text.includes('实际执行模式'));
    assert.ok(text.includes('Record 2 has a newline') && text.includes('Use standard mode'));
    assert.equal(await start.isDisabled(), true);
    assert.deepEqual(errors, []);
    await page.screenshot({ path: path.join(out, 'passed.png'), fullPage: true });
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify({
      kind: 'Mocked UI contract; no database or import/export task executed',
      checks: ['export format readiness', 'preview retry retains upload', 'required mapping gate', 'admission details'],
      uploads, previews, errors, paths,
    }, null, 2));
    console.log('UI_CONTRACT_PASSED (mocked metadata/upload/preview; no database task executed)');
  } catch (error) {
    await page.screenshot({ path: path.join(out, 'failure.png'), fullPage: true }).catch(() => {});
    fs.writeFileSync(path.join(out, 'failure.json'), JSON.stringify({ error: error.message, errors, paths }, null, 2));
    console.error(error.message);
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
