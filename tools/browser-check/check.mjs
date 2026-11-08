// A walk through the board in a real Chrome at phone size (390 x 844). Developer tool: not part of the Maven build and not run by CI.
//
//   1. Start the fixture:  mvn -pl web -am verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
//                              -Dit.test=BrowserCheckFixtureIT -Dbrowser.check=true -Dfailsafe.failIfNoSpecifiedTests=false
//      It starts the application on a throwaway database, writes /tmp/browser-check.json and waits for /tmp/browser-check.done.
//   2. Run:                npm ci --ignore-scripts && node check.mjs
//   3. Stop the fixture:   touch /tmp/browser-check.done
//
// Everything here uses synthetic names and numbers. Screenshots go to ./screenshots (ignored by git).
import fs from 'node:fs';
import puppeteer from 'puppeteer-core';

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const fixture = JSON.parse(fs.readFileSync(process.env.FIXTURE || '/tmp/browser-check.json', 'utf8'));
const base = `http://localhost:${fixture.port}`;
fs.mkdirSync('screenshots', { recursive: true });

const results = [];
function record(step, ok, detail = '') {
  results.push({ step, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${step}${detail ? '  -  ' + detail : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function smsDate() {
  const d = new Date(Date.now() - 5 * 60000 + 3600000);   // five minutes ago, written in UTC+1 like the shipped format
  const p = (n) => String(n).padStart(2, '0');
  return `${p(d.getUTCDate())}/${p(d.getUTCMonth() + 1)}/${d.getUTCFullYear()} ${p(d.getUTCHours())}:${p(d.getUTCMinutes())}`;
}
const sms = (amount, name, phone, tx, ref) =>
  `You received ${amount} XAF from ${name} (${phone}). Txn ${tx}${ref ? `. Ref ${ref}` : ''}. ${smsDate()}`;

async function phoneSize(page) {
  await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
}

async function signIn(page, who) {
  await page.goto(`${base}/login`, { waitUntil: 'networkidle0' });
  await page.type('input[name=workspace]', fixture.slug);
  await page.type('input[name=email]', who.email);
  await page.type('input[name=password]', who.password);
  await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle0' }), page.click('button[type=submit]')]);
}

async function text(page, selector) {
  return page.$eval(selector, (el) => el.textContent.replace(/\s+/g, ' ').trim()).catch(() => '');
}

async function chip(page, name) {
  return text(page, `#chips a[href="#col-${name}"]`);
}

async function waitText(page, selector, expected, ms = 8000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if ((await text(page, selector)).includes(expected)) return true;
    await sleep(100);
  }
  return false;
}

// A card in a column that is scrolled out of sight: bring it into view the way a thumb would, then tap it.
async function tap(page, selector) {
  await page.$eval(selector, (el) => el.scrollIntoView({ inline: 'center', block: 'center' }));
  await sleep(200);
  // Puppeteer cannot compute a click point for an element inside the horizontal scroller in mobile emulation, so the tap is a DOM click.
  await page.$eval(selector, (el) => el.click());
}

const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-first-run', '--no-default-browser-check'] });
try {
  // ── With JavaScript (htmx) ───────────────────────────────────────────────
  const page = await browser.newPage();
  await phoneSize(page);
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error' && !/status of 404|Response Status Error Code 404/.test(m.text())) consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push(String(e)));
  await signIn(page, fixture.seller);
  record('signed in and landed on the board', page.url() === `${base}/app`, page.url());
  await page.screenshot({ path: 'screenshots/01-first-screen.png' });

  const htmxLoaded = await page.evaluate(() => typeof window.htmx === 'object' && typeof window.htmx.version === 'string');
  record('the vendored htmx script loaded and runs under the content security policy', htmxLoaded);

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  record('no sideways scroll of the page at 390 px', overflow <= 0, `overflow ${overflow}px`);
  const small = await page.evaluate(() => [...document.querySelectorAll('button, .button, summary, .chips a, .links a')]
    .filter((e) => e.offsetParent !== null).map((e) => ({ t: e.textContent.trim().slice(0, 20), h: Math.round(e.getBoundingClientRect().height) }))
    .filter((e) => e.h < 44));
  record('every visible button, link and summary is at least 44 px tall', small.length === 0, JSON.stringify(small));
  const fonts = await page.evaluate(() => ({ body: getComputedStyle(document.body).fontSize, area: getComputedStyle(document.querySelector('textarea')).fontSize }));
  record('text is at least 16 px', parseFloat(fonts.body) >= 16 && parseFloat(fonts.area) >= 16, JSON.stringify(fonts));
  const zoomAllowed = await page.evaluate(() => !/user-scalable\s*=\s*no|maximum-scale\s*=\s*1(\.0)?\b/.test(document.querySelector('meta[name=viewport]').content));
  record('pinch zoom is not disabled', zoomAllowed);

  // Keyboard: tab from the top reaches the order box and the button without a pointer.
  await page.evaluate(() => document.activeElement && document.activeElement.blur());
  let reached = false;
  for (let i = 0; i < 20 && !reached; i++) {
    await page.keyboard.press('Tab');
    reached = await page.evaluate(() => document.activeElement && document.activeElement.id === 'order-text');
  }
  const outline = await page.evaluate(() => getComputedStyle(document.activeElement).outlineStyle);
  record('Tab reaches the order box and shows a focus outline', reached && outline !== 'none', `outline ${outline}`);

  // Paste an order: htmx swaps the paste section, the page does not navigate.
  const urlBefore = page.url();
  await page.click('#order-text');
  await page.type('#order-text', 'Order: BR-1\nName: Browser Ana\nPhone: +999 000 000 1234\n1 x rice @ 5000');
  await tap(page, '#paste details:first-of-type button[type=submit]');
  record('pasting an order answers in place with a sentence', await waitText(page, '#paste .result', 'Order recorded.'));
  record('the page did not navigate (htmx swapped)', page.url() === urlBefore);
  record('the Awaiting chip updated out of band', (await chip(page, 'awaiting')).includes('Awaiting 1'), await chip(page, 'awaiting'));

  // The matching payment, recorded by the seller, matches by itself.
  await page.evaluate(() => { document.querySelectorAll('#paste details')[1].open = true; });
  await page.type('#payment-text', sms(5000, 'Browser Ana', '+999 000 000 1234', 'TXBR0000000001', 'BR-1'));
  await tap(page, '#paste details:nth-of-type(2) button[type=submit]');
  record('the matching payment is recorded', await waitText(page, '#paste .result', 'Payment recorded.'));
  record('Matched chip shows 1 and Awaiting 0', (await chip(page, 'matched')).includes('Matched 1') && (await chip(page, 'awaiting')).includes('Awaiting 0'),
    `${await chip(page, 'matched')} / ${await chip(page, 'awaiting')}`);

  // An underpayment becomes a suggestion; confirm it from its card.
  await page.type('#order-text', 'Order: BR-2\nName: Browser Bo\nPhone: +999 000 000 2222\n1 x rice @ 5000');
  await tap(page, '#paste details:first-of-type button[type=submit]');
  await waitText(page, '#paste .result', 'Order recorded.');
  await page.evaluate(() => { document.querySelectorAll('#paste details')[1].open = true; });
  await page.type('#payment-text', sms(3000, 'Browser Bo', '+999 000 000 2222', 'TXBR0000000002', 'BR-2'));
  await tap(page, '#paste details:nth-of-type(2) button[type=submit]');
  await waitText(page, '#paste .result', 'Payment recorded.');
  await page.reload({ waitUntil: 'networkidle0' });
  record('the underpayment waits in Review with Confirm and Reject', (await chip(page, 'review')).includes('Review 1') && (await page.$('#col-review form[action$="/confirm"]')) !== null,
    await chip(page, 'review'));
  await page.screenshot({ path: 'screenshots/02-review-column-top.png' });
  await tap(page, '#col-review form[action$="/confirm"] button');
  record('Confirm from the card replaces the card with the result', await waitText(page, '#col-review .card.done', 'Confirmed and applied to the order.'));
  record('the notice area carries the same sentence', await waitText(page, '#notice', 'Confirmed and applied to the order.'));
  await page.reload({ waitUntil: 'networkidle0' });
  record('the order is back in Awaiting as part paid', (await text(page, '#col-awaiting')).includes('Part paid: 3,000 XAF of 5,000 XAF'), (await text(page, '#col-awaiting')).slice(0, 200));

  // The same transaction again is flagged; free text is kept as unread.
  await page.evaluate(() => { document.querySelectorAll('#paste details')[1].open = true; });
  await page.type('#payment-text', sms(5000, 'Browser Ana', '+999 000 000 1234', 'TXBR0000000001', 'BR-1'));
  await tap(page, '#paste details:nth-of-type(2) button[type=submit]');
  record('a repeated transaction is flagged', await waitText(page, '#paste .result', 'This transaction id was already recorded.'));
  await page.type('#order-text', 'hello, is the shop open today?');
  await tap(page, '#paste details:first-of-type button[type=submit]');
  record('free text is kept as unread', await waitText(page, '#paste .result', 'could not be read as an order'));
  await page.reload({ waitUntil: 'networkidle0' });
  record('the Flagged and Unparsed chips count them', (await chip(page, 'flagged')).includes('Flagged 1') && (await chip(page, 'unparsed')).includes('Unparsed 1'),
    `${await chip(page, 'flagged')} / ${await chip(page, 'unparsed')}`);

  // Screenshots of each column: scroll the snapping scroller to each section.
  const names = ['awaiting', 'review', 'matched', 'flagged', 'unparsed'];
  for (let i = 0; i < names.length; i++) {
    await page.evaluate((n) => document.querySelector(`#col-${n}`).scrollIntoView({ inline: 'start', block: 'start' }), names[i]);
    await sleep(300);
    await page.screenshot({ path: `screenshots/03-column-${names[i]}.png` });
  }
  const snap = await page.evaluate(() => getComputedStyle(document.querySelector('.columns')).scrollSnapType);
  record('the columns scroll and snap', /x/.test(snap), snap);

  // A refused htmx request is shown in the notice area, not dropped: an action on an id that does not exist, as a stale page would send.
  // (No swap option is passed, so the configured response handling decides where the answer goes.)
  await page.evaluate(() => window.htmx.ajax('POST', '/app/payments/00000000-0000-4000-8000-000000000000/set-aside', { source: '#paste', target: '#paste' }));
  record('a 404 answer to an htmx request lands in the notice area', await waitText(page, '#notice', 'The request could not be completed (404)'), await text(page, '#notice'));
  record('the rest of the page was left alone', (await page.$('#paste form')) !== null);

  // No page snapshots are kept in the browser's storage.
  const storage = await page.evaluate(() => ({ local: Object.keys(localStorage), session: Object.keys(sessionStorage) }));
  record('htmx keeps no history snapshots in storage (its one session key is only the current path)', storage.local.length === 0 && !storage.session.includes('htmx-history-cache'), JSON.stringify(storage));

  // Detail page and the masked raw text of a message that could not be read.
  const unread = await page.$eval('#col-unparsed a', (a) => a.getAttribute('href'));
  await page.goto(base + unread, { waitUntil: 'networkidle0' });
  await page.click('details.raw summary');
  const raw = await text(page, 'details.raw');
  record('the unread message is under a closed details and shown as text', raw.includes('hello, is the shop open today?'), raw.slice(0, 80));
  await page.screenshot({ path: 'screenshots/04-unparsed-detail.png' });

  record('no console errors or CSP violations in the whole walk', consoleErrors.length === 0, consoleErrors.join(' | ').slice(0, 300));

  // ── The helper sees no seller controls ───────────────────────────────────
  const helperContext = await browser.createBrowserContext();
  const helperPage = await helperContext.newPage();
  await phoneSize(helperPage);
  await signIn(helperPage, fixture.helper);
  const helperHtml = await helperPage.content();
  record('a helper sees the paste forms and no seller controls',
    helperHtml.includes('Paste an order') && !helperHtml.includes('/rematch') && !helperHtml.includes('/app/books') && !helperHtml.includes('/app/export') && !helperHtml.includes('Confirm'));
  await helperPage.screenshot({ path: 'screenshots/05-helper-board.png' });
  await helperContext.close();

  // ── Without JavaScript: the same actions as plain forms ──────────────────
  const plainContext = await browser.createBrowserContext();
  const plain = await plainContext.newPage();
  await plain.setJavaScriptEnabled(false);
  await phoneSize(plain);
  await signIn(plain, fixture.seller);
  await plain.type('#order-text', 'Order: NJ-1\nName: Plain Cy\nPhone: +999 000 000 3333\n1 x rice @ 1200');
  await Promise.all([plain.waitForNavigation({ waitUntil: 'networkidle0' }), tap(plain, '#paste details:first-of-type button[type=submit]')]);
  record('without JavaScript a paste redirects to the board with a notice', plain.url().includes('/app?n=ORDER_RECORDED') && (await text(plain, '#notice')).includes('Order recorded.'), plain.url());
  await plain.screenshot({ path: 'screenshots/06-no-javascript-after-paste.png' });
  await plain.evaluate(() => { document.querySelectorAll('#paste details')[1].open = true; });
  await plain.type('#payment-text', sms(700, 'Plain Dee', '+999 000 000 4444', 'TXBR0000000003', null));
  await Promise.all([plain.waitForNavigation({ waitUntil: 'networkidle0' }), tap(plain, '#paste details:nth-of-type(2) button[type=submit]')]);
  const aside = await plain.$('#col-review form[action$="/set-aside"]');
  record('without JavaScript the card has its plain buttons', aside !== null);
  if (aside) {
    await plain.$eval('#col-review form[action$="/set-aside"] button', (el) => el.scrollIntoView({ inline: 'center', block: 'center' }));
    await Promise.all([plain.waitForNavigation({ waitUntil: 'networkidle0' }), tap(plain, '#col-review form[action$="/set-aside"] button')]);
    record('without JavaScript Set aside redirects with a notice', (await text(plain, '#notice')).includes('Payment set aside.'), plain.url());
  }
  await plainContext.close();
} finally {
  await browser.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length} of ${results.length} checks passed.`);
fs.writeFileSync('screenshots/results.json', JSON.stringify(results, null, 2));
process.exit(failed.length === 0 ? 0 : 1);
