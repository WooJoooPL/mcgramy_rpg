// Test strony w prawdziwej przeglądarce (Chromium przez Playwright) bez Minecrafta.
// Najpierw uruchom DevServer (zob. README, „Test bez gry”), potem: node narzedzia/test_przegladarka.js
'use strict';
const { chromium } = require('playwright');

const HTTP = 'http://127.0.0.1:8786';
const WS = 'ws://127.0.0.1:8787';
const results = [];

async function dev(action) {
  const r = await fetch(`${HTTP}/__dev/${action}`);
  return r.text();
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const state = (page) => page.evaluate(() => window.__dzwiek.state());
const level = (page) => page.evaluate(() => window.__dzwiek.level());

function check(name, ok, info) {
  results.push({ name, ok: !!ok });
  console.log(`${ok ? 'OK  ' : 'BŁĄD'} ${name}${info !== undefined ? '  ' + JSON.stringify(info) : ''}`);
}

async function waitFor(page, fn, timeout = 8000) {
  const end = Date.now() + timeout;
  for (;;) {
    const s = await state(page);
    if (fn(s)) return s;
    if (Date.now() > end) return s;
    await sleep(100);
  }
}

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext();
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

  // 1. Link z gry
  const token = await dev('link');
  await page.goto(`${HTTP}/#t=${token}&ws=${encodeURIComponent(WS)}`);
  let s = await waitFor(page, (x) => x.connected && x.status.includes('Włącz dźwięk'));
  check('logowanie linkiem', s.connected && s.hasResume, s.status);
  check('token usunięty z paska adresu', !page.url().includes(token), page.url());
  check('przycisk włączenia widoczny', await page.isVisible('#unlock'));

  // 2. Odblokowanie dźwięku i wejście do gry
  await page.click('#unlock');
  s = await waitFor(page, (x) => x.unlocked);
  check('dźwięk odblokowany', s.unlocked && s.ctxState === 'running', s.ctxState);
  s = await waitFor(page, (x) => x.status.includes('Czekam'));
  check('poza grą: czeka', s.status.includes('Czekam') && s.decks.length === 0, s.status);
  await dev('online?v=true');
  s = await waitFor(page, (x) => x.online);
  check('gracz w grze', s.online && s.status === 'Połączono z grą', s.status);

  // 3. Muzyka regionu
  await dev('music?id=demo_miasto&fade=800');
  s = await waitFor(page, (x) => x.decks.length === 1 && x.decks[0].time > 0.5 && x.decks[0].gain > 0.7);
  check('muzyka gra i narasta', s.current === 'demo_miasto' && s.decks[0].gain > 0.7 && !s.decks[0].paused, s.decks);
  const lvl = await level(page);
  check('słychać sygnał na wyjściu', lvl > 0.005, lvl.toFixed(4));
  check('tytuł utworu na stronie', (await page.textContent('#track')) === 'Miasto o świcie');

  // 4. Płynne przejście do muzyki bossa
  await dev('music?id=demo_boss&fade=2000');
  await sleep(700);
  s = await state(page);
  const old = s.decks.find((d) => d.id === 'demo_miasto');
  const boss = s.decks.find((d) => d.id === 'demo_boss');
  check('w trakcie przejścia grają oba', !!old && !!boss && old.stopping && old.gain < 0.8 && boss.gain > 0 && boss.gain < 0.9,
    s.decks.map((d) => [d.id, +d.gain.toFixed(2)]));
  s = await waitFor(page, (x) => x.decks.length === 1);
  check('po przejściu tylko boss', s.decks.length === 1 && s.decks[0].id === 'demo_boss', s.decks.map((d) => d.id));

  // 5. Szybki powrót: boss -> miasto -> boss (powrót do wygasającego utworu)
  await dev('music?id=demo_miasto&fade=3000');
  await sleep(300);
  await dev('music?id=demo_boss&fade=500');
  s = await waitFor(page, (x) => x.decks.length === 1, 6000);
  check('szybkie zmiany bez nawarstwiania', s.decks.length === 1 && s.current === 'demo_boss', s.decks.map((d) => d.id));

  // 6. Efekt
  await dev('sfx?id=demo_ryk');
  s = await waitFor(page, (x) => x.sfxActive > 0, 2000);
  check('efekt odtworzony', s.sfxActive > 0, s.sfxActive);
  await dev('sfx?id=nie_ma_takiego');

  // 7. Wyjście z gry: muzyka wygasa
  await dev('online?v=false');
  s = await waitFor(page, (x) => x.decks.length === 0);
  check('wyjście z gry wycisza', s.decks.length === 0 && s.status.includes('Czekam'), s.status);
  await dev('online?v=true');
  await dev('music?id=demo_dzicz&fade=500');
  s = await waitFor(page, (x) => x.current === 'demo_dzicz');
  check('powrót do gry wznawia', s.current === 'demo_dzicz');

  // 8. Restart serwera: karta łączy się sama tokenem wznowienia
  await dev('restart');
  await sleep(300);
  s = await waitFor(page, (x) => x.connected && x.status === 'Połączono z grą', 12000);
  check('auto-połączenie po restarcie', s.connected && s.status === 'Połączono z grą', s.status);
  check('muzyka gra dalej po restarcie', s.current === 'demo_dzicz' && s.decks.length === 1, s.decks.map((d) => d.id));

  // 9. Odświeżenie strony: wznowienie z sessionStorage
  await page.reload();
  s = await waitFor(page, (x) => x.connected && x.status.includes('Włącz dźwięk'));
  check('odświeżenie strony bez nowego linku', s.connected, s.status);

  // 10. Stary link nie działa drugi raz
  const page2 = await context.newPage();
  await page2.goto(`${HTTP}/#t=${token}&ws=${encodeURIComponent(WS)}`);
  await sleep(1500);
  s = await state(page2);
  check('zużyty link odrzucony', !s.connected && s.status.includes('Link wygasł'), s.status);
  s = await state(page);
  check('pierwsza karta nadal działa', s.connected, s.status);

  // 11. Nowy link w drugiej karcie zastępuje pierwszą
  const token2 = await dev('link');
  const page3 = await context.newPage();
  await page3.goto(`${HTTP}/#t=${token2}&ws=${encodeURIComponent(WS)}`);
  await waitFor(page3, (x) => x.connected);
  s = await waitFor(page, (x) => !x.connected && x.status.includes('innej karcie'));
  check('stara karta zastąpiona', !s.connected && s.status.includes('innej karcie'), s.status);
  await sleep(2500);
  s = await state(page);
  check('zastąpiona karta nie odbija sesji', !s.connected && !s.hasResume, s);

  // 12. Wylogowanie komendą w grze
  await dev('logout');
  s = await waitFor(page3, (x) => x.status.includes('komendą'));
  check('rozłączenie komendą', !s.connected && !s.hasResume, s.status);

  // 13. Obcy Origin nie może się połączyć
  const evil = await page.evaluate(async (ws) => new Promise((res) => {
    const sock = new WebSocket(ws);
    sock.onopen = () => res('open');
    sock.onclose = (e) => res('closed ' + e.code);
  }), WS).catch((e) => 'err ' + e);
  // ta karta ma dobry Origin, więc sprawdzamy z innego źródła: about:blank ma Origin "null"
  const blank = await context.newPage();
  const evil2 = await blank.evaluate(async (ws) => new Promise((res) => {
    const sock = new WebSocket(ws);
    sock.onopen = () => res('open');
    sock.onclose = (e) => res('closed ' + e.code);
  }), WS);
  check('połączenie z obcej strony odrzucone', evil2 !== 'open', { dobra: evil, obca: evil2 });

  check('brak błędów JS na stronie', errors.length === 0, errors.slice(0, 5));
  await page.screenshot({ path: process.env.ZRZUT || 'zrzut_strony.png' });
  await browser.close();

  const failed = results.filter((r) => !r.ok).length;
  console.log(`\n${results.length - failed}/${results.length} sprawdzeń OK`);
  process.exit(failed ? 1 : 0);
})().catch((e) => {
  console.error(e);
  process.exit(2);
});
