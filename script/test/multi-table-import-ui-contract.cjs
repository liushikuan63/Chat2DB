// MOCKED UI contract: real headless browser, mocked metadata/upload/preview, no DB/task execution.
// Required: C2D_BROWSER_FIXTURE. Optional: C2D_PLAYWRIGHT_MODULE, C2D_BROWSER_FRONTEND_URL,
// C2D_BROWSER_CONNECTION_LABEL, C2D_BROWSER_OUTPUT, C2D_BROWSER_CHANNEL.
const { chromium } = require(process.env.C2D_PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const fixture = JSON.parse(fs.readFileSync(process.env.C2D_BROWSER_FIXTURE, 'utf8'));
const frontend = new URL(process.env.C2D_BROWSER_FRONTEND_URL || 'http://127.0.0.1:8889');
if (frontend.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(frontend.hostname) ||
    !/^c2d_ui_fixture_/.test(fixture.database)) throw Error('Explicit named loopback UI fixture required');
const out = path.resolve(process.env.C2D_BROWSER_OUTPUT || 'target/import-browser-evidence/multi-ui-contract');
const connectionLabel = process.env.C2D_BROWSER_CONNECTION_LABEL || 'C2D_BROWSER_MYSQL';
fs.mkdirSync(out, { recursive: true });
(async () => {
  const browser = await chromium.launch({ channel: process.env.C2D_BROWSER_CHANNEL || 'chrome', headless: true });
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 1440, height: 1100 } });
  const page = await context.newPage();
  page.setDefaultTimeout(12000);
  const errors = [], paths = [], checks = [], previews = [], blockedWrites = [];
  const uploads = { 'users.csv': 0, 'orders.csv': 0 };
  const responseCounts = {};
  page.on('response', (response) => {
    const pathname = new URL(response.url()).pathname;
    if (pathname.startsWith('/api/')) responseCounts[pathname] = (responseCounts[pathname] || 0) + 1;
  });
  let targetLoads = 0;
  let stage = 'navigation';
  const mib = 1024 * 1024;
  page.on('pageerror', (error) => errors.push(error.message));
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    paths.push(pathname);
    const reply = (data) => route.fulfill({ json: { success: true, data } });
    const failure = (message) => route.fulfill({ json: { success: false,
      errorCode: 'task.import.ui.fixture.failure', errorMessage: message } });
    if (pathname === '/api/rdb/database/list') return reply([
      { name: fixture.database, description: 'MOCKED multi-table UI fixture', count: 3 },
    ]);
    if (pathname === '/api/rdb/ddl/database_schema_list') {
      return reply({ databases: [{ name: fixture.database, schemas: [] }], schemas: [] });
    }
    if (pathname === '/api/rdb/table/list') return reply({ data: ['users', 'orders', 'archive'].map((name) => ({ name })),
      total: 3, pageNo: 1, pageSize: 100, hasNextPage: false });
    if (pathname === '/api/rdb/table/table_list') {
      if (++targetLoads === 1) return failure('Target list fixture failure');
      return reply(['users', 'orders', 'archive'].map((name) => ({ name })));
    }
    if (pathname === '/api/rdb/import_preview/capabilities') return reply({
      singleFileMaxBytes: 50 * mib, multiFileMaxBytes: 1024 * mib,
      singleLocalFileMaxBytes: 50 * mib, multiLocalFileMaxBytes: 1024 * mib,
      multiTotalMaxBytes: 10 * 1024 * mib, maxFiles: 1000,
    });
    if (pathname === '/api/rdb/import_preview/upload') {
      const name = /filename="([^"]+)"/.exec(request.postData() || '')?.[1];
      assert.ok(Object.hasOwn(uploads, name), 'Only the two mock upload files are expected');
      uploads[name] += 1;
      if (name === 'orders.csv' && uploads[name] === 1) return failure('Orders upload fixture failure');
      return reply('multi-ui-' + name.replace('.csv', ''));
    }
    if (pathname === '/api/rdb/import_preview/release') return reply(null);
    if (pathname === '/api/rdb/import_preview/preview') {
      const input = request.postDataJSON();
      previews.push({ fileId: input.fileId, tableName: input.tableName, csvOptions: input.csvOptions });
      return reply({ sourceColumns: ['KEY', 'NOTE'], previewData: [['1', 'mock row']],
        targetTableName: input.tableName, previewLimit: 1,
        suggestedMapping: [{ sourceColumn: 'NOTE', targetColumn: 'NOTE' }],
        targetColumns: [
          { name: 'ID', nullable: false, autoIncrement: false, defaultValue: null, dataType: 'INT', comment: null },
          { name: 'NOTE', nullable: true, autoIncrement: false, defaultValue: null, dataType: 'VARCHAR', comment: null },
        ] });
    }
    // Never send task submissions, SQL, connection mutations or other real writes.
    if (request.method() !== 'GET') {
      blockedWrites.push(pathname);
      return route.abort('blockedbyclient');
    }
    return route.continue();
  });
  const evidence = () => ({ kind: 'MOCKED UI; no database or import task executed', stage, checks,
    uploads, targetLoads, previews, responseCounts, pageErrors: errors, blockedWrites, paths });
  const waitDisabled = async (locator, disabled) => {
    await page.waitForFunction(({ element, disabled: wanted }) => element.disabled === wanted,
      { element: await locator.elementHandle(), disabled });
    assert.equal(await locator.isDisabled(), disabled);
  };
  try {
    await page.goto(frontend.origin, { waitUntil: 'domcontentloaded' });
    const treeTitle = (title) => page.getByText(title, { exact: true }).first()
      .locator('../..').locator('[data-chat2db-general-can-copy-element]');
    await treeTitle(connectionLabel).dblclick();
    await treeTitle(fixture.database).click({ button: 'right' });
    await page.getByText('多表导入', { exact: true }).first().click();
    const dialog = page.getByRole('dialog');
    const next = dialog.getByRole('button', { name: /下\s*一\s*步/ });
    const start = dialog.getByRole('button', { name: /开\s*始/ });
    stage = 'target failure and independent staging';
    await dialog.getByText('Target list fixture failure', { exact: true }).waitFor();
    await dialog.locator('input[name=file]').setInputFiles([
      { name: 'users.csv', mimeType: 'text/csv', buffer: Buffer.from('KEY,NOTE\n1,user\n') },
      { name: 'orders.csv', mimeType: 'text/csv', buffer: Buffer.from('KEY,NOTE\n2,order\n') },
    ]);
    const row = (name) => dialog.getByRole('row').filter({ has: page.getByText(name, { exact: true }) });
    await row('orders.csv').getByRole('button', { name: /重\s*试/ }).waitFor();
    await dialog.locator('.ant-alert').filter({ hasText: 'Target list fixture failure' })
      .getByRole('button', { name: /重\s*试/ }).click();
    await row('users.csv').getByRole('combobox').waitFor();
    await row('users.csv').getByText('必须映射', { exact: false }).waitFor();
    assert.equal(targetLoads, 2); assert.equal(uploads['users.csv'], 1);
    checks.push('target-list failure explicit retry retains successful staged file');
    stage = 'failed-only upload retry';
    await row('orders.csv').getByRole('button', { name: /重\s*试/ }).click();
    await row('orders.csv').getByText('必须映射', { exact: false }).waitFor();
    assert.deepEqual(uploads, { 'users.csv': 1, 'orders.csv': 2 });
    checks.push('upload retry requests only failed file and retains successful source');
    stage = 'independent required mappings';
    await waitDisabled(next, true);
    await row('users.csv').locator('.ant-table-row-expand-icon').click();
    await row('orders.csv').locator('.ant-table-row-expand-icon').click();
    const region = (name) => dialog.getByRole('region', { name: new RegExp(name.replace('.', '\\.') + ':.*字段映射') });
    const selectOption = async (combobox, selector) => {
      await combobox.press('ArrowDown');
      const controls = await combobox.getAttribute('aria-controls');
      assert.ok(controls, 'Ant combobox must identify its own option list');
      // Each expanded source owns a portal. Use its aria linkage, not all visible dropdowns.
      await page.locator(`[id="${controls}"]`).locator('..').locator(selector).click();
    };
    const mapId = async (name) => {
      await selectOption(region(name).getByRole('row').filter({ hasText: 'KEY' }).getByRole('combobox'),
        '.ant-select-item-option[title^="ID ("]');
    };
    await mapId('users.csv'); await waitDisabled(next, true);
    await mapId('orders.csv'); await waitDisabled(next, false);
    assert.equal(previews.length, 2);
    assert.deepEqual(previews.map(({ fileId }) => fileId).sort(), ['multi-ui-orders', 'multi-ui-users']);
    checks.push('two independent previews and required mappings gate next');
    stage = 'one-source option invalidation';
    const changed = page.waitForResponse((response) => response.url().includes('/api/rdb/import_preview/preview')
      && response.request().postDataJSON().fileId === 'multi-ui-users');
    await Promise.all([changed, region('users.csv').getByRole('checkbox').first().uncheck()]); await waitDisabled(next, true); await mapId('users.csv'); await waitDisabled(next, false);
    assert.equal(previews.filter(({ fileId }) => fileId === 'multi-ui-users').length, 2);
    assert.equal(previews.filter(({ fileId }) => fileId === 'multi-ui-orders').length, 1);
    assert.equal(previews.at(-1).csvOptions.emptyAsNull, false);
    checks.push('changing one CSV option repreviews only that source and invalidates its mapping');
    stage = 'one-source target invalidation';
    const retargeted = page.waitForResponse((response) => response.url().includes('/api/rdb/import_preview/preview')
      && response.request().postDataJSON().tableName === 'archive');
    await Promise.all([retargeted, selectOption(
      row('users.csv').getByRole('combobox', { name: /目标表.*users\.csv/ }),
      '.ant-select-item-option[title="archive"]')]); await waitDisabled(next, true); await mapId('users.csv'); await waitDisabled(next, false);
    assert.equal(previews.length, 4);
    assert.equal(previews.filter(({ fileId }) => fileId === 'multi-ui-orders').length, 1);
    assert.equal(previews.at(-1).fileId, 'multi-ui-users');
    assert.equal(previews.at(-1).tableName, 'archive');
    checks.push('changing one target repreviews only that source and keeps both file IDs');
    await page.screenshot({ path: path.join(out, 'sources-ready.png'), fullPage: true });
    stage = 'navigation and footer readiness without submission';
    await next.click(); await next.click(); await waitDisabled(start, false);
    assert.deepEqual(errors, []);
    assert.equal(paths.filter((value) => value === '/api/tasks/import').length, 0);
    assert.deepEqual(blockedWrites, []);
    checks.push('footer ready after navigation; no submit or database/task execution');
    await page.screenshot({ path: path.join(out, 'passed.png'), fullPage: true });
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify(evidence(), null, 2));
    console.log('MULTI_UI_CONTRACT_PASSED (MOCKED metadata/upload/preview; no DB or task execution)');
  } catch (error) {
    await page.screenshot({ path: path.join(out, 'failure.png'), fullPage: true }).catch(() => {});
    fs.writeFileSync(path.join(out, 'failure.json'), JSON.stringify({ ...evidence(), error: error.message }, null, 2));
    console.error('MOCKED UI harness failure at ' + stage + ': ' + error.message);
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
