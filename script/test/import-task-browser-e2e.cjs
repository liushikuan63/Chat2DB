const { chromium } = require(process.env.C2D_PLAYWRIGHT_MODULE || 'playwright');
const path = require('node:path');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const OUT = path.resolve(process.env.C2D_BROWSER_OUTPUT || 'target/import-browser-evidence');
const fixturePath = process.env.C2D_BROWSER_FIXTURE;
if (!fixturePath) throw Error('C2D_BROWSER_FIXTURE must name an explicitly prepared local test fixture');
const fixture = JSON.parse(fs.readFileSync(fixturePath, 'utf8'));
if (fixture.database !== 'c2d_ui_fixture_20261013' || !/^c2db-browser-[a-z0-9-]+$/.test(fixture.marker || '') ||
    !Number.isSafeInteger(fixture.dataSourceId) || fixture.host !== '127.0.0.1') {
  throw Error('Only the named loopback browser fixture may be reset by this test');
}
function localUrl(value) {
  const url = new URL(value);
  if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) ||
      url.username || url.password || url.pathname !== '/') throw Error('Expected a credential-free loopback HTTP origin');
  return url.origin;
}
const backend = localUrl(process.env.C2D_BROWSER_BACKEND_URL || 'http://127.0.0.1:10825');
const frontend = localUrl(process.env.C2D_BROWSER_FRONTEND_URL || 'http://127.0.0.1:8889');
fs.mkdirSync(OUT, { recursive: true });
(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN', acceptDownloads:true });
  const page = await context.newPage();
  page.setDefaultTimeout(8000);
  const errors = []; const responses = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('response', r => { if (r.url().includes('/api/')) responses.push({ path: new URL(r.url()).pathname, status: r.status() }); });
  try {
    const connection = await (await fetch(backend + '/api/connection/datasource?id=' + fixture.dataSourceId)).json();
    if (!connection.success || connection.data.alias !== 'C2D_BROWSER_MYSQL') throw Error('Fixture datasource identity changed');
    const jdbc = connection.data.url || '';
    if (!jdbc.startsWith('jdbc:mysql://127.0.0.1:' + fixture.port + '/' + fixture.database + '?')) {
      throw Error('Fixture datasource no longer points to the declared local test database');
    }
    const reset = await fetch(backend + '/api/rdb/dml/execute', { method:'POST', headers:{'Content-Type':'application/json'},
      body:JSON.stringify({dataSourceId:fixture.dataSourceId,databaseName:fixture.database,
        sql:'DELETE FROM C2D_UI_SINGLE',single:true,pageNo:1,pageSize:100}) });
    const resetResult = await reset.json();
    if (!resetResult.success || !resetResult.data[0].success) throw Error('Fixture reset failed');
    const ready = page.waitForResponse(r => new URL(r.url()).pathname === '/api/common/environment/list_all', { timeout: 45000 });
    await page.goto(frontend, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await ready;
    await page.getByText('C2D_BROWSER_MYSQL', { exact: true }).first().waitFor({ state: 'visible', timeout: 30000 });
    await page.getByText('C2D_BROWSER_MYSQL', { exact: true }).first().locator('../..').locator('[data-chat2db-general-can-copy-element]').dblclick();
    await page.getByText('c2d_ui_fixture_20261013', { exact: true }).first().waitFor({ state: 'visible', timeout: 30000 });
    await page.getByText('c2d_ui_fixture_20261013', { exact: true }).first().locator('../..').locator('[data-chat2db-general-can-copy-element]').dblclick();
    await page.getByText('表', { exact: true }).first().waitFor({ state: 'visible', timeout: 30000 });
    await page.getByText('表', { exact: true }).first().locator('..').locator('[class*="switcherIconBox"]').click();
    await page.getByText('C2D_UI_SINGLE', { exact: true }).first().waitFor({ state: 'visible', timeout: 30000 });
    await page.getByText('C2D_UI_SINGLE', { exact: true }).first().locator('../..').locator('[data-chat2db-general-can-copy-element]').click({button:'right'});
    await page.getByText('导入数据', { exact: true }).first().click();
    const dialog = page.getByRole('dialog');
    await dialog.locator('input[name=file]').waitFor({ state: 'attached', timeout: 30000 });
    const previewResponse = page.waitForResponse(r => new URL(r.url()).pathname === '/api/tasks/import/preview');
    previewResponse.catch(() => {});
    await dialog.locator('input[name=file]').setInputFiles({ name:'single.csv', mimeType:'text/csv',
      buffer: Buffer.from('KEY,NOTE,QTY\n1,"中文,hello",12.34\n2,"emoji😀",56.78\n3,"line\nbreak",90.12\n','utf8') });
    const preview = await (await previewResponse).json();
    assert.equal(preview.success, true);
    console.log('PREVIEW_PASSED');
    const start = dialog.getByRole('button', { name: /开\s*始/ });
    if (!(await start.isDisabled())) throw Error('Required unmapped ID should block submission');
    await dialog.locator('span[title="KEY"]').locator('..').getByRole('combobox').click();
    await page.locator('.ant-select-dropdown:visible .ant-select-item-option[title="ID"]').click();
    const submitted = page.waitForResponse(r => new URL(r.url()).pathname === '/api/tasks/import');
    submitted.catch(() => {});
    await start.click();
    const submission = await (await submitted).json();
    if (!submission.success || !submission.data.taskId) throw Error('Real task submission failed');
    const taskId = submission.data.taskId;
    console.log('SUBMITTED ' + taskId);
    let task;
    const deadline = Date.now() + 30000;
    do {
      const status = await fetch(backend + '/api/tasks/get?taskId=' + taskId);
      const envelope = await status.json();
      if (!envelope.success) throw Error('Task read failed');
      task = envelope.data;
      if (['SUCCESS','FAILED','CANCELLED'].includes(task.status)) break;
      await new Promise(resolve => setTimeout(resolve, 200));
    } while (Date.now() < deadline);
    if (task.status !== 'SUCCESS') throw Error('Task failed: ' + JSON.stringify(task));
    const query = await fetch(backend + '/api/rdb/dml/execute', { method:'POST', headers:{'Content-Type':'application/json'},
      body:JSON.stringify({dataSourceId:fixture.dataSourceId,databaseName:fixture.database,
        sql:'SELECT ID, NOTE, QTY FROM C2D_UI_SINGLE ORDER BY ID',single:true,pageNo:1,pageSize:100}) });
    const data = await query.json();
    assert.equal(data.success, true);
    assert.equal(data.data[0].success, true);
    const rows = data.data[0].dataList.map(row => row.slice(1).map(cell => cell.value));
    assert.deepEqual(rows, [['1','中文,hello','12.34'],['2','emoji😀','56.78'],['3','line\nbreak','90.12']]);
    await dialog.getByText('100%', { exact: true }).waitFor({ state:'visible', timeout:15000 });
    console.log('DATABASE_VALUES_PASSED ' + JSON.stringify(rows));
    fs.writeFileSync(OUT + '/single-real-task.json', JSON.stringify({taskId,task,preview,data},null,2));
    await page.screenshot({ path: OUT + '/single-success.png', fullPage:true });
    await dialog.getByRole('button',{name:/关\s*闭/}).click();
    await page.getByText('C2D_UI_SINGLE',{exact:true}).first().locator('../..').locator('[data-chat2db-general-can-copy-element]').click({button:'right'});
    await page.getByText('导出数据',{exact:true}).first().click();
    await page.getByRole('dialog').getByRole('combobox').first().press('ArrowDown');
    await page.locator('.ant-select-dropdown:visible .ant-select-item-option[title="CSV"]').click();
    const exportedResponse = page.waitForResponse(r => new URL(r.url()).pathname === '/api/tasks/export');
    exportedResponse.catch(()=>{});
    console.log('EXPORT_FORM '+JSON.stringify({body:await page.getByRole('dialog').innerText(),startDisabled:await page.getByRole('dialog').getByRole('button',{name:/开\s*始/}).isDisabled()}));
    await page.getByRole('dialog').getByRole('button',{name:/开\s*始/}).click();
    const exportSubmission = await (await exportedResponse).json();
    if (!exportSubmission.success) throw Error('Export submission failed');
    const exportTaskId = exportSubmission.data.taskId;
    let exportTask;
    const exportDeadline = Date.now()+30000;
    do {
      exportTask = (await (await fetch(backend + '/api/tasks/get?taskId='+exportTaskId)).json()).data;
      if (['SUCCESS','FAILED','CANCELLED'].includes(exportTask.status)) break;
      await new Promise(resolve=>setTimeout(resolve,200));
    } while(Date.now()<exportDeadline);
    if (exportTask.status!=='SUCCESS') throw Error('Export failed '+JSON.stringify(exportTask));
    await page.getByRole('dialog').getByText('100%',{exact:true}).waitFor({timeout:15000});
    const artifacts = await (await fetch(backend + '/api/tasks/artifacts?taskId='+exportTaskId)).json();
    console.log('EXPORTED '+JSON.stringify({taskId:exportTaskId,artifacts}));
    fs.writeFileSync(OUT+'/export-task.json',JSON.stringify({exportTaskId,exportTask,artifacts},null,2));
    assert.equal(artifacts.data.length,1);
    assert.equal(artifacts.data[0].mediaType,'text/csv');
    let resolveDownload; let rejectDownload; let downloadTimer;
    const downloadReady = new Promise((resolve,reject)=>{ resolveDownload=resolve; rejectDownload=reject;
      downloadTimer=setTimeout(()=>reject(Error('Expected real browser download did not arrive')),15000); });
    downloadReady.catch(()=>{});
    const received = d => { clearTimeout(downloadTimer); resolveDownload(d); };
    page.on('download',received);
    context.on('page',popup=>popup.on('download',received));
    await page.getByRole('dialog').getByRole('button',{name:'任务产物 (1)',exact:true}).click();
    const artifactName = artifacts.data[0].artifactId.split(/[\\/]/).pop();
    await page.getByRole('menuitem').filter({hasText:artifactName}).click();
    const download = await downloadReady;
    assert.equal(await download.failure(),null);
    const downloadedFile=OUT+'/export.csv';
    await download.saveAs(downloadedFile);
    const text=new TextDecoder('utf-8',{fatal:true}).decode(fs.readFileSync(downloadedFile));
    assert.ok(text.includes('中文,hello')&&text.includes('emoji😀'));
    const { execFileSync }=require('node:child_process');
    const parsed=JSON.parse(execFileSync('python',['-X','utf8','-c',
      'import csv,json,sys;print(json.dumps(list(csv.reader(open(sys.argv[1],encoding="utf-8-sig",newline=""))),ensure_ascii=False))',downloadedFile],{encoding:'utf8'}));
    assert.deepEqual(parsed,[['ID','NOTE','QTY'],...rows]);
    console.log('BROWSER_DOWNLOAD_VALUES_PASSED '+JSON.stringify({exportTaskId,filename:download.suggestedFilename(),rows:parsed.length-1}));
    await page.screenshot({ path: OUT + '/initial-ui.png', fullPage: true });
    const result = { url: page.url(), body: await page.locator('body').innerText(), errors, responses,
      buttons: await page.locator('button').allTextContents() };
    fs.writeFileSync(OUT + '/initial-ui.json', JSON.stringify(result, null, 2));
    assert.deepEqual(errors, []);
    console.log('SINGLE_IMPORT_EXPORT_BROWSER_PASSED');
  } catch (e) {
    await page.screenshot({ path: OUT + '/initial-ui-failure.png', fullPage: true }).catch(() => {});
    console.log(JSON.stringify({ error: e.message, errors, responses, body: await page.locator('body').innerText().catch(() => '') }));
    process.exitCode = 1;
  } finally { await browser.close(); }
})();
