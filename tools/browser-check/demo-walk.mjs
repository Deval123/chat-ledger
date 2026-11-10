// The demo video story (task 9 design stop, section 5) walked in a real Chrome at phone size (390 x 844), with and without JavaScript.
// Developer tool: not part of the Maven build and not run by CI.
//
//   1. Start the fixture:  mvn -pl web -am verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
//                              -Dit.test=DemoBrowserFixtureIT -Dbrowser.demo=true -Dfailsafe.failIfNoSpecifiedTests=false
//      It starts the application as the demo deployment, with seeded workspaces, and writes /tmp/browser-demo.json.
//   2. Run:                node demo-walk.mjs
//   3. Stop the fixture:   touch /tmp/browser-demo.done
//
// The walk enters like a judge, with the one button: there are no demo credentials. Screenshots go to ./screenshots/demo-*.png.
import fs from 'node:fs';
import puppeteer from 'puppeteer-core';

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const fixture = JSON.parse(fs.readFileSync(process.env.FIXTURE || '/tmp/browser-demo.json', 'utf8'));
const base = `http://localhost:${fixture.port}`;
fs.mkdirSync('screenshots', { recursive: true });

const results = [];
function record(step, ok, detail = '') {
  results.push({ step, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${step}${detail ? '  -  ' + detail : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let shot = 0;
const snap = (page, name) => page.screenshot({ path: `screenshots/demo-${String(++shot).padStart(2, '0')}-${name}.png`, fullPage: false });

async function phoneSize(page) {
  await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
}

async function bodyText(page) {
  return page.evaluate(() => document.body.innerText.replace(/\s+/g, ' '));
}

async function chip(page, name) {
  return page.$eval(`#chips a[href="#col-${name}"]`, (el) => el.textContent.replace(/\s+/g, ' ').trim()).catch(() => '');
}

/** Clicks the button of the first card (optionally in one column) whose text has `has`, inside a form whose action ends with `suffix`. */
async function cardButton(page, has, suffix, column = null) {
  return page.evaluate((has, suffix, column) => {
    const scope = column ? document.querySelector(`#col-${column}`) : document;
    for (const card of (scope ? scope.querySelectorAll('.card') : [])) {
      if (!card.textContent.includes(has)) continue;
      const button = card.querySelector(`form[action$="${suffix}"] button`);
      if (button) { button.scrollIntoView({ block: 'center', inline: 'center' }); button.click(); return true; }
    }
    return false;
  }, has, suffix, column);
}

async function cardText(page, has, column = null) {
  return page.evaluate((has, column) => {
    const scope = column ? document.querySelector(`#col-${column}`) : document;
    for (const card of (scope ? scope.querySelectorAll('.card') : [])) if (card.textContent.includes(has)) return card.textContent.replace(/\s+/g, ' ');
    return '';
  }, has, column);
}

/** On the scenarios page, submits one scenario's step and waits for the page it redirects to. */
async function step(page, scenario, which) {
  await page.goto(`${base}/app/demo`, { waitUntil: 'networkidle0' });
  await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle0' }),
    page.$eval(`form[action="/app/demo/scenarios/${scenario}/${which}"] button`, (b) => { b.scrollIntoView({ block: 'center' }); b.click(); })]);
  return page.url();
}

async function waitFor(page, fn, ms = 8000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (await fn()) return true;
    await sleep(150);
  }
  return false;
}

async function layoutChecks(page, label) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  record(`${label}: no sideways scroll at 390 px`, overflow <= 0, `overflow ${overflow}px`);
  const small = await page.evaluate(() => [...document.querySelectorAll('button, a, summary')]
    .filter((el) => el.offsetParent !== null && !el.closest('.chips') && el.getBoundingClientRect().height < 44)
    .map((el) => el.textContent.trim().slice(0, 30)));
  record(`${label}: every visible button and link is at least 44 px tall`, small.length === 0, JSON.stringify(small.slice(0, 5)));
}

const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-first-run', '--no-default-browser-check'] });
try {
  // ── The story, with JavaScript ───────────────────────────────────────────
  const page = await browser.newPage();
  await phoneSize(page);
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push(String(e)));

  // 0:00 Open the app's address: the one-sentence page and its button.
  await page.goto(`${base}/`, { waitUntil: 'networkidle0' });
  record('0:00 the app address lands on the demo page', page.url() === `${base}/demo`, page.url());
  record('0:00 it says the data is made up and has one button', (await bodyText(page)).includes('Nothing here is real.')
    && (await page.$$('form[action="/demo"] button')).length === 1 && (await page.$$('input[type=text], textarea')).length === 0);
  await layoutChecks(page, '/demo');
  await snap(page, 'landing');
  await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle0' }), page.click('form[action="/demo"] button')]);
  record('0:00 one click opens a board', page.url() === `${base}/app`, page.url());

  // 0:15 The seeded board.
  const board = await bodyText(page);
  record('0:15 the board says it is a demo and links the scenarios', board.includes('Demo, made-up data') && (await page.$('a[href="/app/demo"]')) !== null);
  record('0:15 the seeded board has matched, review, flagged and unread items',
    (await chip(page, 'matched')).includes('1') && !(await chip(page, 'flagged')).endsWith('(0)') && (await chip(page, 'unparsed')).includes('1'),
    `${await chip(page, 'matched')} / ${await chip(page, 'review')} / ${await chip(page, 'flagged')} / ${await chip(page, 'unparsed')}`);
  record('0:15 the flagged column shows fixed sentences', board.includes('This transaction id was already recorded.') && board.includes('I checked this'));
  await snap(page, 'seeded-board');

  // The scenarios page itself.
  await page.goto(`${base}/app/demo`, { waitUntil: 'networkidle0' });
  const scenarios = await bodyText(page);
  record('the scenarios page lists the seven, as simulated chat and confirmations', ['1. Happy path', '2. Underpayment', '3. Overpayment',
    '4. Duplicate confirmation', '5. Screenshot-only payment', '6. Wrong sender name', '7. Late payment'].every((t) => scenarios.includes(t))
    && scenarios.includes('Simulated chat') && (await page.$$('.bubble')).length >= 14 && (await page.$$('.sms')).length >= 7);
  record('the scenarios page has no text field', (await page.$$('input[type=text], textarea')).length === 0);
  await layoutChecks(page, '/app/demo');
  await snap(page, 'scenarios');

  // 0:35 Happy path in two steps.
  record('0:35 "Send the order" returns to the scenarios page', (await step(page, 'happy-path', 'order')).includes('/app/demo?n=DEMO_ORDER_SENT'));
  record('0:35 the scenario waits for its payment', (await bodyText(page)).includes('The order was sent. Now send the payment.'));
  record('0:35 "Send the payment" shows the board', (await step(page, 'happy-path', 'payment')).includes('/app?n=DEMO_PAYMENT_SENT'));
  record('0:35 the happy path matched by itself', (await chip(page, 'matched')).includes('2'), await chip(page, 'matched'));
  await snap(page, 'happy-path');

  // 0:55 Underpayment: a suggestion, confirmed from its card (htmx), then part paid.
  await step(page, 'underpayment', 'both');
  record('0:55 the underpayment is a suggestion with Confirm', await cardButton(page, 'Nia Notreal', '/confirm', 'review'));
  record('0:55 Confirm answers in place', await waitFor(page, async () => (await bodyText(page)).includes('Confirmed and applied to the order.')));
  await page.goto(`${base}/app`, { waitUntil: 'networkidle0' });
  record('0:55 the order is part paid', (await cardText(page, 'Nia Notreal')).includes('Part paid: 3,000 XAF of 5,000 XAF'), (await cardText(page, 'Nia Notreal')).slice(0, 90));
  await snap(page, 'underpayment');

  // 1:10 Overpayment: flagged with its sentence; confirmed, the excess goes to customer credit.
  await step(page, 'overpayment', 'both');
  const over = await cardText(page, 'Oto Sampled', 'flagged');
  record('1:10 the overpayment is flagged with its sentence', over.includes('The payment is more than the order.'), over.slice(0, 120));
  record('1:10 it can still be confirmed (flags are advisory)', await cardButton(page, 'Oto Sampled', '/confirm'));
  record('1:10 Confirm answers in place', await waitFor(page, async () => (await bodyText(page)).includes('Confirmed and applied to the order.')));
  await snap(page, 'overpayment');

  // 1:30 Duplicate: flagged; "I checked this" in place; it moves to the payments set aside or repeated.
  await step(page, 'duplicate', 'both');
  record('1:30 the repeat is flagged', (await cardText(page, 'Pia Fictive', 'flagged')).includes('This transaction id was already recorded.'));
  record('1:30 "I checked this" on its card', await cardButton(page, 'Pia Fictive', '/resolve', 'flagged'));
  record('1:30 resolving answers in place', await waitFor(page, async () => (await bodyText(page)).includes('Marked as checked.')));
  await page.goto(`${base}/app`, { waitUntil: 'networkidle0' });
  record('1:30 the repeat is now with the payments set aside or repeated', (await bodyText(page)).includes('Payments set aside or repeated:'));
  await snap(page, 'duplicate');

  // 1:45 Screenshot only: unverified, never automatic, with its reason.
  await step(page, 'screenshot', 'both');
  record('1:45 the screenshot payment is flagged for its missing transaction id',
    (await cardText(page, 'Quin Testcase', 'flagged')).includes('This confirmation has no transaction id.'));
  await snap(page, 'screenshot');

  // 2:00 Wrong sender: two sentences on the payment page; Reject from the card.
  await step(page, 'wrong-sender', 'both');
  const link = await page.evaluate(() => {
    const cards = [...document.querySelectorAll('#col-flagged .card')].filter((c) => c.textContent.includes('Kim Unknown'));
    const a = cards.length ? cards[0].querySelector('a[href^="/app/payments/"]') : null;
    return a ? a.getAttribute('href') : null;
  });
  await page.goto(`${base}${link}`, { waitUntil: 'networkidle0' });
  const sender = await bodyText(page);
  record('2:00 the wrong sender has both sentences', sender.includes("The sender's name does not resemble the customer's name.")
    && sender.includes('The payment came from a different phone number than the one on the order.'));
  await snap(page, 'wrong-sender');
  await page.goto(`${base}/app`, { waitUntil: 'networkidle0' });
  record('2:00 Reject from a card', await cardButton(page, 'Kim Unknown', '/reject'));
  record('2:00 Reject answers in place', await waitFor(page, async () => (await bodyText(page)).includes('Suggestion rejected.')));

  // 2:15 Late payment: the old-confirmation sentences.
  await step(page, 'late-payment', 'both');
  const lateLink = await page.evaluate(() => {
    const card = [...document.querySelectorAll('#col-flagged .card')].find((c) => c.textContent.includes('Sol Invented'));
    const a = card ? card.querySelector('a[href^="/app/payments/"]') : null;
    return a ? a.getAttribute('href') : null;
  });
  await page.goto(`${base}${lateLink}`, { waitUntil: 'networkidle0' });
  const late = await bodyText(page);
  record('2:15 the late payment has both time sentences', late.includes('The payment time is far from when it was recorded.')
    && late.includes('This payment was made before the order existed.'));
  await snap(page, 'late-payment');

  // 2:30 The books and the download.
  await page.goto(`${base}/app/books`, { waitUntil: 'networkidle0' });
  record('2:30 the books balance after the whole story', (await bodyText(page)).includes('The books balance.'));
  await snap(page, 'books');
  await page.goto(`${base}/app/export`, { waitUntil: 'networkidle0' });
  const csv = await page.evaluate(async () => {
    const r = await fetch('/app/export/orders.csv');   // the default dates: the last thirty days
    return { status: r.status, type: r.headers.get('content-type') || '', head: (await r.text()).slice(0, 80) };
  });
  record('2:30 the orders download is a CSV file', csv.status === 200 && csv.type.includes('text/csv'), `${csv.status} ${csv.type}`);

  // 2:45 The board answers "what needs my attention now".
  await page.goto(`${base}/app`, { waitUntil: 'networkidle0' });
  await snap(page, 'end');
  record('no console errors or CSP violations in the whole walk', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));

  // A second visit from the same browser goes back to the same workspace (D22).
  await page.goto(`${base}/demo`, { waitUntil: 'networkidle0' });
  record('opening /demo again goes back to the same workspace', page.url() === `${base}/app` && (await bodyText(page)).includes('Nia Notreal'),
    page.url());

  // ── Without JavaScript ───────────────────────────────────────────────────
  const plainContext = await browser.createBrowserContext();
  const plain = await plainContext.newPage();
  await phoneSize(plain);
  await plain.setJavaScriptEnabled(false);
  await plain.goto(`${base}/`, { waitUntil: 'networkidle0' });
  await Promise.all([plain.waitForNavigation({ waitUntil: 'networkidle0' }), plain.click('form[action="/demo"] button')]);
  record('without JavaScript the button opens a board (another workspace)', plain.url() === `${base}/app` && (await chip(plain, 'matched')).includes('1'),
    `${plain.url()} ${await chip(plain, 'matched')}`);
  record('without JavaScript a scenario runs and shows the board with its notice',
    (await step(plain, 'happy-path', 'both')).includes('/app?n=DEMO_PAYMENT_SENT') && (await bodyText(plain)).includes('The payment was sent.'));
  await step(plain, 'duplicate', 'both');
  await Promise.all([plain.waitForNavigation({ waitUntil: 'networkidle0' }), cardButton(plain, 'Pia Fictive', '/resolve', 'flagged')]);
  record('without JavaScript "I checked this" is a plain form that returns with its notice', plain.url().includes('n=FLAG_RESOLVED'), plain.url());
  await plain.goto(`${base}/app`, { waitUntil: 'networkidle0' });
  await Promise.all([plain.waitForNavigation({ waitUntil: 'networkidle0' }), cardButton(plain, 'Gus Placeholder', '/confirm')]);
  record('without JavaScript Confirm is a plain form that returns with its notice', plain.url().includes('n=MATCH_CONFIRMED'), plain.url());
  await snap(plain, 'no-js-board');
  await plainContext.close();
} finally {
  await browser.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length} of ${results.length} checks passed.`);
process.exit(failed.length === 0 ? 0 : 1);
