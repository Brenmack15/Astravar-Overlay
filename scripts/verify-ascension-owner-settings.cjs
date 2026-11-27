/* Browser-driven owner administration on an isolated, in-memory worker. Run with Node 24, Playwright and Chromium. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

async function verify() {
  const { handleWorker } = await import('../backend/owner-bridge/worker.mjs');
  const { SqliteAdapter } = await import('../backend/owner-bridge/sqlite-adapter.mjs');
  const db = new SqliteAdapter();
  const origin = 'https://isolated-owner.example';
  const id = 'qa-settings-browser';
  const base = '/v3/continuities/' + id;
  const owner = 'fixture-owner';
  const env = { DB: db, OWNER_SITE_USER_ID: owner, ASTRAVAR_CLOUD_ENV: 'isolated', ASTRAVAR_CLOUD_ENABLED: '1' };
  const identity = { 'oai-authenticated-user-id': owner, Origin: origin };
  let browser;
  try {
    db.exec(fs.readFileSync(path.join(__dirname, '../backend/owner-bridge/schema.sql'), 'utf8'));
    const init = await handleWorker(new Request(origin + base + '/isolated', {
      method: 'POST', headers: { ...identity, 'Content-Type': 'application/json' }, body: '{}'
    }), env);
    assert.equal(init.status, 200);
    browser = await chromium.launch({
      ...(process.env.CHROMIUM_EXECUTABLE ? { executablePath: process.env.CHROMIUM_EXECUTABLE } :
        (fs.existsSync('/usr/bin/chromium') ? { executablePath: '/usr/bin/chromium' } : {})),
      args: ['--disable-dev-shm-usage']
    });
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', err => errors.push(err.message));
    const commands = [];
    const fetched = [];
    let loseNextCommandResponse = true;
    await page.route('**/*', async route => {
      const request = route.request();
      const url = new URL(request.url());
      if (url.origin !== origin) return route.abort();
      const headers = { ...(await request.allHeaders()), 'oai-authenticated-user-id': owner };
      const body = request.postDataBuffer();
      const raw = await handleWorker(new Request(url, { method: request.method(), headers, ...(body ? { body } : {}) }), env);
      fetched.push(url.pathname);
      if (url.pathname === base + '/commands' && request.method() === 'POST') {
        commands.push(JSON.parse(body));
        if (loseNextCommandResponse) {
          loseNextCommandResponse = false;
          return route.abort('connectionreset');
        }
      }
      return route.fulfill({ status: raw.status, headers: Object.fromEntries(raw.headers), body: Buffer.from(await raw.arrayBuffer()) });
    });
    await page.goto(origin + '/owner/ascension/' + id + '/access');
    await page.locator('summary').filter({ hasText: 'Campaign settings' }).click();
    const name = page.getByLabel('Campaign name', { exact: true });
    const notes = page.getByLabel('Campaign notes', { exact: true });
    const unknown = page.getByLabel('Notes unknown', { exact: true });
    await name.fill('Synthetic Chronicle');
    assert.equal(await unknown.isChecked(), true);
    assert.equal(await notes.isDisabled(), true);
    await unknown.uncheck();
    const content = 'Workshop <map>\nSecond line';
    await notes.fill(content);
    await page.getByRole('button', { name: 'Review settings' }).click();
    assert.equal(await page.getByText('Synthetic Chronicle', { exact: true }).count(), 1);
    assert.equal(await page.locator('#metadata pre').last().textContent(), content);
    assert.equal(commands.length, 0);
    await page.getByRole('button', { name: 'Save reviewed settings' }).click();
    await page.getByRole('button', { name: 'Retry same reviewed request' }).click();
    const status = page.locator('#metadata [role=status]');
    await status.filter({ hasText: 'Campaign settings saved at revision 1.' }).waitFor({ timeout: 5000 });
    assert.equal(commands.length, 2);
    assert.deepEqual(commands[0], commands[1]);
    assert(fetched.includes(base + '/receipts/' + commands[0].commandId));
    const check = await handleWorker(new Request(origin + base + '/settings', { headers: identity }), env);
    const result = await check.json();
    assert.equal(result.revision, 1);
    assert.equal(result.profile.title, 'Synthetic Chronicle');
    assert.equal(result.profile.data.notes, content);
    assert.equal(await name.inputValue(), result.profile.title);
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ browser: 'Chromium', isolated: true, previewWrites: 0, identicalAttempts: 2,
      committedRevisions: result.revision, receiptRead: true, stateReadback: true, scriptErrors: errors.length }));
  } finally {
    if (browser) await browser.close();
    db.close();
  }
}
verify().catch(error => { console.error(error); process.exitCode = 1; });
