const { chromium } = require(process.env.C2D_PLAYWRIGHT_MODULE || "playwright");
const fs = require("node:fs"),
  assert = require("node:assert/strict"),
  { execFileSync } = require("node:child_process");
const fixturePath = process.env.C2D_BROWSER_FIXTURE;
if (!fixturePath) throw Error("Provide a named isolated C2D_BROWSER_FIXTURE");
const fixture = JSON.parse(fs.readFileSync(fixturePath));
const path = require("node:path"),
  crypto = require("node:crypto");
const suffix = crypto.randomBytes(6).toString("hex").toUpperCase(),
  retryTable = "C2D_UI_LIFECYCLE_" + suffix + "_RETRY",
  cancelTable = "C2D_UI_LIFECYCLE_" + suffix + "_CANCEL";
function localOrigin(value) {
  const u = new URL(value);
  assert.equal(u.protocol, "http:");
  assert.ok(["127.0.0.1", "localhost", "[::1]"].includes(u.hostname));
  assert.ok(!u.username && !u.password && u.pathname === "/");
  return u.origin;
}
const backend = localOrigin(
    process.env.C2D_BROWSER_BACKEND_URL || "http://127.0.0.1:10825"
  ),
  frontend = localOrigin(
    process.env.C2D_BROWSER_FRONTEND_URL || "http://127.0.0.1:8889"
  );
const out = path.resolve(
  process.env.C2D_BROWSER_OUTPUT || "target/import-lifecycle-browser-evidence"
);
assert.equal(
  fs.existsSync(out),
  false,
  "Preserve an earlier result; choose a new evidence directory"
);
fs.mkdirSync(out, { recursive: true });
assert.equal(fixture.database, "c2d_ui_fixture_20261013");
assert.equal(fixture.host, "127.0.0.1");
assert.equal(
  execFileSync(
    "docker",
    [
      "inspect",
      "--format",
      '{{index .Config.Labels "chat2db.browser.owner"}}',
      fixture.containerId,
    ],
    { encoding: "utf8" }
  ).trim(),
  fixture.marker
);
function sql(query) {
  return execFileSync(
    "docker",
    [
      "exec",
      fixture.containerId,
      "sh",
      "-c",
      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B "$1" -e "$2"',
      "sh",
      fixture.database,
      query,
    ],
    { encoding: "utf8" }
  ).trim();
}
async function api(path, body) {
  const r = await fetch(
    backend + path,
    body
      ? {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        }
      : undefined
  );
  const e = await r.json();
  assert.equal(e.success, true, JSON.stringify(e));
  return e.data;
}
async function until(fn, predicate, timeout = 45000) {
  const deadline = Date.now() + timeout;
  let r;
  do {
    r = await fn();
    if (predicate(r)) return r;
    await new Promise((resolve) => setTimeout(resolve, 100));
  } while (Date.now() < deadline);
  throw Error("Condition not met: " + JSON.stringify(r));
}
(async () => {
  const browser = await chromium.launch({ channel: "chrome", headless: true });
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    locale: "zh-CN",
  });
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const record = {
    retryTable,
    cancelTable,
    errors: [],
    consoleErrors: [],
    responses: [],
    attempts: [],
  };
  page.on("pageerror", (e) => record.errors.push(e.message));
  page.on("console", (m) => {
    if (m.type() === "error") record.consoleErrors.push(m.text());
  });
  page.on("response", (r) => {
    if (r.url().includes("/api/"))
      record.responses.push({
        path: new URL(r.url()).pathname,
        status: r.status(),
      });
  });
  await context.tracing.start({ screenshots: true, snapshots: true });
  try {
    const ds = await api(
      "/api/connection/datasource?id=" + fixture.dataSourceId
    );
    assert.equal(ds.alias, "C2D_BROWSER_MYSQL");
    assert.ok(
      ds.url.startsWith(
        "jdbc:mysql://127.0.0.1:" + fixture.port + "/" + fixture.database + "?"
      )
    );
    sql(
      "CREATE TABLE " +
        retryTable +
        " (ID INT NOT NULL, NOTE VARCHAR(255), QTY DECIMAL(16,2)) ENGINE=InnoDB; CREATE TABLE " +
        cancelTable +
        " (ID INT PRIMARY KEY, NOTE VARCHAR(255), QTY DECIMAL(16,2)) ENGINE=InnoDB; CREATE TRIGGER " +
        cancelTable +
        "_SLOW BEFORE INSERT ON " +
        cancelTable +
        " FOR EACH ROW SET @fixture_sleep=SLEEP(0.03)"
    );
    await api(
      "/api/rdb/table/list?dataSourceId=" +
        fixture.dataSourceId +
        "&databaseName=" +
        fixture.database +
        "&refresh=true&pageNo=1&pageSize=100"
    );
    const ready = page.waitForResponse(
      (r) => new URL(r.url()).pathname === "/api/common/environment/list_all"
    );
    await page.goto(frontend, { waitUntil: "domcontentloaded" });
    await ready;
    await page
      .getByText("C2D_BROWSER_MYSQL", { exact: true })
      .first()
      .locator("../..")
      .locator("[data-chat2db-general-can-copy-element]")
      .dblclick();
    await page
      .getByText(fixture.database, { exact: true })
      .first()
      .locator("../..")
      .locator("[data-chat2db-general-can-copy-element]")
      .dblclick();
    await page
      .getByText("表", { exact: true })
      .first()
      .locator("..")
      .locator('[class*="switcherIconBox"]')
      .click();
    async function form(table, buffer) {
      await page
        .getByText(table, { exact: true })
        .first()
        .locator("../..")
        .locator("[data-chat2db-general-can-copy-element]")
        .click({ button: "right" });
      await page.getByText("导入数据", { exact: true }).first().click();
      const dialog = page.getByRole("dialog");
      await dialog.locator("input[name=file]").waitFor({ state: "attached" });
      const preview = page.waitForResponse(
        (r) => new URL(r.url()).pathname === "/api/tasks/import/preview"
      );
      await dialog
        .locator("input[name=file]")
        .setInputFiles({ name: table + ".csv", mimeType: "text/csv", buffer });
      assert.equal((await (await preview).json()).success, true);
      const start = dialog.getByRole("button", { name: /开\s*始/ });
      assert.equal(await start.isDisabled(), true);
      await dialog
        .locator('span[title="KEY"]')
        .locator("..")
        .getByRole("combobox")
        .click();
      await page
        .locator(
          '.ant-select-dropdown:visible .ant-select-item-option[title="ID"]'
        )
        .click();
      return { dialog, start };
    }
    const retry = await form(
      retryTable,
      Buffer.from(
        "KEY,NOTE,QTY\n1,中文重试,1.25\n2,emoji😀,2.50\n3,third,3.75\n"
      )
    );
    let lostId;
    let resolveLost;
    const lostReady = new Promise((resolve) => (resolveLost = resolve));
    let attempts = 0;
    await page.route("**/api/tasks/import", async (route) => {
      record.attempts.push(route.request().postDataJSON());
      if (attempts++ === 0) {
        const response = await route.fetch();
        const e = await response.json();
        assert.equal(e.success, true);
        lostId = e.data.taskId;
        await route.abort("failed");
        resolveLost();
      } else await route.continue();
    });
    await retry.start.click();
    await lostReady;
    await until(() => retry.start.isEnabled(), Boolean);
    const retried = page.waitForResponse(
      (r) => new URL(r.url()).pathname === "/api/tasks/import"
    );
    await retry.start.click();
    const e = await (await retried).json();
    assert.equal(e.success, true);
    assert.equal(e.data.taskId, lostId);
    assert.equal(record.attempts.length, 2);
    assert.equal(
      record.attempts[0].clientSubmissionId,
      record.attempts[1].clientSubmissionId
    );
    record.retryTask = await until(
      () => api("/api/tasks/get?taskId=" + lostId),
      (t) => ["SUCCESS", "FAILED", "CANCELLED"].includes(t.status)
    );
    assert.equal(record.retryTask.status, "SUCCESS");
    record.retryRows = sql(
      "SELECT COUNT(*),COUNT(DISTINCT ID),SUM(ID),SUM(QTY) FROM " +
        retryTable +
        ""
    );
    assert.equal(record.retryRows, "3\t3\t6\t7.50");
    await retry.dialog
      .getByText("100%", { exact: true })
      .waitFor({ state: "visible" });
    await page.screenshot({ path: out + "/retry-success.png", fullPage: true });
    await retry.dialog.getByRole("button", { name: /关\s*闭/ }).click();
    await page.unroute("**/api/tasks/import");
    const cancel = await form(
      cancelTable,
      Buffer.from(
        "KEY,NOTE,QTY\n" +
          Array.from(
            { length: 3000 },
            (_, i) => `${i + 1},cancel-${i + 1},1.25\n`
          ).join("")
      )
    );
    const submitted = page.waitForResponse(
      (r) => new URL(r.url()).pathname === "/api/tasks/import"
    );
    await cancel.start.click();
    const c = await (await submitted).json();
    assert.equal(c.success, true);
    const taskId = c.data.taskId;
    await until(
      () => api("/api/tasks/get?taskId=" + taskId),
      (t) => t.status === "RUNNING"
    );
    await cancel.dialog.getByRole("button", { name: /关\s*闭/ }).click();
    await page
      .locator("svg.lucide-rotate-ccw-clock")
      .first()
      .locator("..")
      .click();
    await page
      .getByRole("tablist")
      .locator("svg.lucide-list-todo")
      .locator("..")
      .click();
    const card = page
      .locator('div[data-status="RUNNING"]')
      .filter({ hasText: cancelTable })
      .first();
    await card.waitFor({ state: "visible" });
    await until(
      () =>
        Promise.resolve(
          Number(sql("SELECT COUNT(*) FROM " + cancelTable + ""))
        ),
      (n) => n > 0 && n < 3000
    );
    await card.hover();
    await card.locator("svg.lucide-square").locator("..").click();
    const confirm = page.getByRole("dialog");
    await confirm
      .getByText("取消将停止后续写入；已提交的数据会保留。是否取消此任务？", {
        exact: true,
      })
      .waitFor();
    record.controlBefore = await until(
      () => api("/api/tasks/control?taskId=" + taskId),
      (t) => t.cancelSupported
    );
    const controlResponse = page.waitForResponse(
      (r) => new URL(r.url()).pathname === "/api/tasks/control"
    );
    controlResponse.catch(() => {});
    const cancelled = page.waitForResponse(
      (r) => new URL(r.url()).pathname === "/api/tasks/cancel"
    );
    cancelled.catch(() => {});
    await confirm.getByRole("button", { name: /确\s*认/ }).click();
    record.controlResponse = await (await controlResponse).json();
    fs.writeFileSync(
      out + "/control.json",
      JSON.stringify(record.controlResponse)
    );
    record.cancelAcknowledgement = await (await cancelled).json();
    assert.equal(record.cancelAcknowledgement.success, true);
    record.cancelTask = await until(
      () => api("/api/tasks/get?taskId=" + taskId),
      (t) => ["SUCCESS", "FAILED", "CANCELLED"].includes(t.status)
    );
    assert.equal(record.cancelTask.status, "CANCELLED");
    const resultCard = page
      .locator('div[data-status="CANCELLED"]')
      .filter({ hasText: cancelTable })
      .first();
    await resultCard
      .getByRole("img", { name: "已取消", exact: true })
      .waitFor();
    await page.screenshot({ path: out + "/cancel-result.png", fullPage: true });
    record.cancelRows = sql(
      "SELECT COUNT(*),COUNT(DISTINCT ID),MIN(ID),MAX(ID) FROM " +
        cancelTable +
        ""
    );
    const n = Number(record.cancelRows.split("\t")[0]);
    assert.ok(n > 0 && n < 3000);
    assert.equal(Number(record.cancelRows.split("\t")[1]), n);
    assert.equal(
      await (
        await fetch(backend + "/api/tasks/import", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(record.attempts[1]),
        })
      )
        .json()
        .then((r) => r.data.taskId),
      lostId
    );
    assert.equal(sql("SELECT COUNT(*) FROM " + cancelTable + ""), String(n));
    const spec = JSON.parse(record.cancelTask.specJson);
    assert.equal(fs.existsSync(spec.sourceFile), false);
    assert.deepEqual(record.errors, []);
    const unexpected = record.consoleErrors.filter(
      (m) =>
        !m.includes("net::ERR_FAILED") &&
        !m.startsWith("Warning: findDOMNode is deprecated")
    );
    assert.deepEqual(unexpected, []);
    record.passed = true;
    console.log(
      "BROWSER_REAL_RESPONSE_LOSS_RETRY_AND_CANCEL_PASSED",
      lostId,
      taskId,
      record.cancelRows
    );
  } catch (e) {
    record.failure = e.stack;
    record.body = await page
      .locator("body")
      .innerText()
      .catch(() => "");
    await page
      .screenshot({ path: out + "/failure.png", fullPage: true })
      .catch(() => {});
    console.error(e.stack);
    process.exitCode = 1;
  } finally {
    fs.writeFileSync(out + "/result.json", JSON.stringify(record, null, 2));
    await context.tracing.stop({ path: out + "/trace.zip" });
    await browser.close();
  }
})();
