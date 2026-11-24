// The accessibility walk of the demo flow in a real Chrome (task 12a). Run by CI's "Accessibility in a browser" job, and by hand:
//
//   1. Start the fixture (the application as the demo deployment, with seeded workspaces; it writes /tmp/browser-demo.json):
//        mvn -pl web -am verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
//            -Dit.test=DemoBrowserFixtureIT -Dbrowser.demo=true -Dfailsafe.failIfNoSpecifiedTests=false
//   2. Run:  CHROME_PATH=<Chrome for Testing> node a11y-walk.mjs
//   3. The walk stops the fixture itself (it writes /tmp/browser-demo.done) when it ends.
//
// What it proves, which no static check can: the keyboard alone reaches and runs the demo, the skip link comes first, the focused
// element is always visible and never under the sticky bar, focus lands on the result after a card action, the notice region keeps its
// node and only its text changes, nothing scrolls sideways at 360 px or at 200 % zoom, and axe-core finds no serious or critical issue.
// Every check prints PASS or FAIL; the exit status is 1 if any failed. No network beyond localhost.
import fs from 'node:fs';
import { createRequire } from 'node:module';
import puppeteer from 'puppeteer-core';

const require = createRequire(import.meta.url);
const AXE = fs.readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');
const CHROME = process.env.CHROME_PATH;
if (!CHROME) {
  console.error('Set CHROME_PATH to the pinned Chrome for Testing binary.');
  process.exit(2);
}
const FIXTURE = process.env.FIXTURE || '/tmp/browser-demo.json';
const base = `http://localhost:${JSON.parse(fs.readFileSync(FIXTURE, 'utf8')).port}`;

let failures = 0;
function check(what, ok, detail = '') {
  if (!ok) failures++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${what}${detail ? '  -  ' + detail : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * No page-level sideways scroll (WCAG 1.4.10). Compared with the width that was set, not with window.innerWidth: a mobile viewport widens
 * itself to fit content that is too wide, so innerWidth would grow with the overflow and hide it.
 */
async function noSidewaysScroll(page, what) {
  const width = page.viewport().width;
  const [scroll, inner, wide] = await page.evaluate((w) => {
    // The innermost elements that reach past the set width, to say what to fix.
    const past = [...document.body.querySelectorAll('*')].filter((e) => e.getBoundingClientRect().right > w + 0.5);
    const leaves = past.filter((e) => !past.some((o) => o !== e && e.contains(o)));
    const name = (e) => e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (e.className && typeof e.className === 'string' ? '.' + e.className.trim().split(/\s+/).join('.') : '');
    return [document.documentElement.scrollWidth, window.innerWidth, leaves.slice(0, 3).map((e) => `${name(e)} ${Math.round(e.getBoundingClientRect().right)}`)];
  }, width);
  const ok = scroll <= width && inner <= width;
  check(`${what}: nothing scrolls sideways`, ok,
    `scrollWidth ${scroll}, innerWidth ${inner}, set ${width}${!ok && wide.length ? ', past it: ' + wide.join('; ') : ''}`);
}

/** axe-core on the page: no serious or critical violation (WCAG 2.2 A and AA rules). */
async function axe(page, what) {
  await page.evaluate(AXE);
  const result = await page.evaluate(async () => {
    // eslint-disable-next-line no-undef
    const r = await axe.run(document, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] } });
    return r.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => `${v.id} (${v.nodes.length})`);
  });
  check(`${what}: axe-core finds no serious or critical issue`, result.length === 0, result.join(', '));
}

/** The focused element: its box is in the viewport, below the sticky chips when they show, and it has a visible outline. */
async function focusVisible(page) {
  return page.evaluate(() => {
    const el = document.activeElement;
    if (!el || el === document.body) return { ok: false, why: 'nothing focused' };
    const r = el.getBoundingClientRect();
    const chips = document.querySelector('#chips');
    const cr = chips ? chips.getBoundingClientRect() : null;
    const chipsShown = chips && getComputedStyle(chips).display !== 'none' && cr.height > 0;
    const style = getComputedStyle(el);
    const inView = r.bottom > 0 && r.top < window.innerHeight && r.right > 0 && r.left < window.innerWidth;
    const underChips = chipsShown && !chips.contains(el) && r.top < cr.bottom - 1 && r.bottom > cr.top;
    const outline = style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) >= 2;
    const label = (el.textContent || el.getAttribute('aria-label') || el.tagName).replace(/\s+/g, ' ').trim().slice(0, 60);
    return { ok: inView && !underChips && outline, why: `${label}: inView=${inView} underChips=${underChips} outline=${outline}` };
  });
}

/** Presses Tab until the focused element matches `predicate` (at most `max` presses); each stop must be visibly focused. */
async function tabTo(page, predicateSource, max, what) {
  const hidden = [];
  for (let i = 0; i < max; i++) {
    await page.keyboard.press('Tab');
    const f = await focusVisible(page);
    if (!f.ok) hidden.push(f.why);
    // eslint-disable-next-line no-new-func
    if (await page.evaluate(new Function(`return (${predicateSource})(document.activeElement);`))) {
      check(`${what}: every element on the way had a visible focus`, hidden.length === 0, hidden.slice(0, 3).join(' | '));
      return true;
    }
  }
  check(`${what}: reached by the keyboard within ${max} Tab presses`, false);
  return false;
}

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: true,
  // The sandbox stays on. Ubuntu 24.04 forbids its user namespaces, so CI installs Chrome's setuid sandbox helper and names it in
  // CHROME_DEVEL_SANDBOX instead (job a11y-browser). The flag that turns it off is refused by WorkflowFileTest.
});
try {
  const page = await browser.newPage();
  if (process.platform === 'linux') {
    // Chrome's own report of its sandbox (Linux only): the setuid helper must have taken over, not a silently weaker mode.
    await page.goto('chrome://sandbox', { waitUntil: 'load' });
    const report = await page.evaluate(() => document.body.innerText);
    check('the browser runs sandboxed', /adequately sandboxed/i.test(report), report.split('\n').filter(Boolean).slice(-1).join(''));
  }
  await page.setViewport({ width: 360, height: 780, deviceScaleFactor: 2, isMobile: true, hasTouch: false });

  // The entry page, by keyboard only: the skip link is the first stop, the demo button is reached and pressed with Enter.
  await page.goto(`${base}/demo`, { waitUntil: 'networkidle0' });
  await page.keyboard.press('Tab');
  check('entry: the first Tab stop is the skip link',
    await page.evaluate(() => document.activeElement.matches('a.skip[href="#main"]')));
  check('entry: the focused skip link is visible', (await focusVisible(page)).ok);
  await noSidewaysScroll(page, 'entry at 360 px');
  await axe(page, 'entry');
  if (await tabTo(page, "(el) => el.matches('main form button[type=submit]')", 10, 'entry: the demo button')) {
    await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle0' }), page.keyboard.press('Enter')]);
  }
  check('entry: Enter on the button opens a board', new URL(page.url()).pathname === '/app', page.url());

  // The board at 360 px: the columns stack, every heading is reachable, the chips jump to the columns.
  await noSidewaysScroll(page, 'board at 360 px');
  const headings = await page.evaluate(() => [...document.querySelectorAll('.column h2')].map((h) => {
    const r = h.getBoundingClientRect();
    return { text: h.textContent.trim(), left: r.left, right: r.right };
  }));
  check('board at 360 px: five column headings', headings.length === 5, String(headings.length));
  check('board at 360 px: every column starts on screen horizontally',
    headings.every((h) => h.left >= 0 && h.right <= 360), JSON.stringify(headings.filter((h) => h.left < 0 || h.right > 360)));
  check('board: one h1', await page.evaluate(() => document.querySelectorAll('h1').length === 1));

  // Each chip, by keyboard, jumps to its column; the column's heading lands below the sticky chips, not under them (2.4.11).
  const chips = await page.$$eval('#chips a[href^="#"]', (as) => as.map((a) => a.getAttribute('href')));
  check('board: the chips link to the columns', chips.length === 5, chips.join(' '));
  const hidden = [];
  for (const href of chips) {
    await page.focus(`#chips a[href="${href}"]`);
    await page.keyboard.press('Enter');
    await sleep(150);
    const where = await page.evaluate((h) => {
      const heading = document.querySelector(`${h} h2`);
      const bar = document.getElementById('chips').getBoundingClientRect();
      const r = heading.getBoundingClientRect();
      return { ok: r.top >= bar.bottom - 1 && r.top < window.innerHeight, top: Math.round(r.top), bar: Math.round(bar.bottom) };
    }, href);
    if (!where.ok) hidden.push(`${href}: heading at ${where.top}, chips end at ${where.bar}`);
  }
  check('board: after a chip jump the column heading is below the chips', hidden.length === 0, hidden.join(' | '));
  // Backwards by keyboard from the end of the board: each element scrolled in from above lands below the chips, not under them (2.4.11).
  await page.evaluate(() => {
    const all = [...document.querySelectorAll('main a[href], main button')];
    all[all.length - 1].focus();
  });
  const under = [];
  for (let i = 0; i < 30; i++) {
    await page.keyboard.down('Shift');
    await page.keyboard.press('Tab');
    await page.keyboard.up('Shift');
    const f = await focusVisible(page);
    if (!f.ok) under.push(f.why);
  }
  check('board: Shift+Tab from the end keeps each focused element visible, below the chips', under.length === 0, under.slice(0, 3).join(' | '));
  // Start the next part from the top of a fresh board: a jump moves the keyboard's starting point to the column.
  await page.goto(`${base}/app`, { waitUntil: 'networkidle0' });
  await axe(page, 'board');

  // A card action by keyboard: Tab to an "I checked this" button, press Enter. The notice region is the same node and says the result;
  // the focus is on the result card, not lost at the top of the page.
  const noticeBefore = await page.evaluateHandle(() => document.getElementById('notice'));
  if (await tabTo(page, "(el) => el.matches('.card .flag form button')", 200, 'board: an \"I checked this\" button')) {
    await page.keyboard.press('Enter');
    await page.waitForSelector('.card.done', { timeout: 10000 }).catch(() => null);
    await sleep(300);
    const same = await page.evaluate((before) => before === document.getElementById('notice') && before.isConnected, noticeBefore);
    check('board: the notice region keeps its node after the action', same);
    const said = await page.evaluate(() => document.getElementById('notice').textContent.trim());
    check('board: the notice region says the result', said.length > 0, said);
    check('board: the focus is on the result card', await page.evaluate(() => document.activeElement.matches('.card.done')));
    check('board: the focused result card is visible', (await focusVisible(page)).ok);
  }

  // A decision on an order's page, by keyboard: Confirm a suggestion. The page's header (status and "Still to pay") follows the answer,
  // without a reload, and agrees with a reload; the notice region keeps its node and says the result and the new status; the focus is on
  // the result card; axe-core before and after.
  let orderPage = null;
  for (const listing of ['/app', '/app/columns/review', '/app/columns/awaiting']) {
    await page.goto(`${base}${listing}`, { waitUntil: 'networkidle0' });
    const links = await page.evaluate(() => [...new Set([...document.querySelectorAll('a[href^="/app/orders/"]')]
      .map((a) => a.getAttribute('href')).filter((h) => /^\/app\/orders\/[0-9a-f-]{36}$/.test(h)))]);
    for (const href of links.slice(0, 20)) {
      await page.goto(`${base}${href}`, { waitUntil: 'networkidle0' });
      if (await page.evaluate(() => document.querySelector('article.match form[action$="/confirm"] input[name="from"]') !== null)) {
        orderPage = href;
        break;
      }
    }
    if (orderPage) break;
  }
  check('order page: one with a suggestion to confirm was found', orderPage !== null);
  if (orderPage) {
    await axe(page, 'order page');
    const before = await page.evaluate(() => [document.getElementById('order-status').textContent.trim(),
      (document.getElementById('order-balance') || { textContent: '' }).textContent.trim()]);
    const notice = await page.evaluateHandle(() => document.getElementById('notice'));
    if (await tabTo(page, "(el) => el.matches('article.match form[action$=\"/confirm\"] button')", 80, 'order page: Confirm')) {
      await page.keyboard.press('Enter');
      await page.waitForSelector('.card.done', { timeout: 10000 }).catch(() => null);
      await sleep(300);
      const after = await page.evaluate(() => [document.getElementById('order-status').textContent.trim(),
        (document.getElementById('order-balance') || { textContent: '' }).textContent.trim(),
        document.getElementById('notice').textContent.trim()]);
      check('order page: the status line changed without a reload', after[0] !== before[0], `${before[0]} -> ${after[0]}`);
      check('order page: "Still to pay" changed without a reload', after[1] !== before[1], `${before[1]} -> ${after[1]}`);
      check('order page: the notice region keeps its node after the action',
        await page.evaluate((n) => n === document.getElementById('notice') && n.isConnected, notice));
      check('order page: the notice region says the new status', after[2].includes(after[0]), after[2]);
      check('order page: one live region, the notice', await page.evaluate(() => document.querySelectorAll('[role="status"]').length === 1
        && document.querySelector('[role="status"]').id === 'notice'));
      check('order page: the focus is on the result card', await page.evaluate(() => document.activeElement.matches('.card.done')));
      await axe(page, 'order page after Confirm');
      await page.goto(`${base}${orderPage}`, { waitUntil: 'networkidle0' });
      const reloaded = await page.evaluate(() => [document.getElementById('order-status').textContent.trim(),
        (document.getElementById('order-balance') || { textContent: '' }).textContent.trim()]);
      check('order page: a reload shows the same status and "Still to pay"', reloaded[0] === after[0] && reloaded[1] === after[1],
        `${after[0]} / ${after[1]} vs ${reloaded[0]} / ${reloaded[1]}`);
    }
  }

  // A scenario, by keyboard only: Tab to "Run both" of the first scenario, press Enter, land on the board with the notice.
  await page.goto(`${base}/app/demo`, { waitUntil: 'networkidle0' });
  await noSidewaysScroll(page, 'scenarios at 360 px');
  await axe(page, 'scenarios');
  if (await tabTo(page, "(el) => el.matches('section.scenario button') && el.textContent.startsWith('Run both')", 60, 'scenarios: Run both')) {
    await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle0' }), page.keyboard.press('Enter')]);
    check('scenarios: Enter runs the scenario and opens the board', new URL(page.url()).pathname === '/app', page.url());
    check('scenarios: the board says what happened', (await page.evaluate(() => document.getElementById('notice').textContent.trim())).length > 0);
  }

  // The other pages a judge opens, at 360 px and at 200 % zoom (a 1280 px window at 200 % is 640 CSS px wide).
  for (const path of ['/app', '/app/books', '/app/summary', '/app/columns/flagged']) {
    for (const [width, label] of [[360, '360 px'], [640, '200 % zoom']]) {
      await page.setViewport({ width, height: 800, deviceScaleFactor: 2, isMobile: width === 360 });
      await page.goto(`${base}${path}`, { waitUntil: 'networkidle0' });
      await noSidewaysScroll(page, `${path} at ${label}`);
    }
    await axe(page, path);
  }

  // The skip link works: Tab once, Enter, and the main region has the focus.
  await page.goto(`${base}/app`, { waitUntil: 'networkidle0' });
  await page.keyboard.press('Tab');
  await page.keyboard.press('Enter');
  await sleep(200);
  check('board: the skip link moves the focus to the main region', await page.evaluate(() => document.activeElement.id === 'main'));
} finally {
  await browser.close();
  fs.writeFileSync('/tmp/browser-demo.done', '');
}
console.log(`Failures: ${failures}`);
process.exit(failures === 0 ? 0 : 1);
