/* Dźwięk gry McRPG: odtwarzacz w przeglądarce.
 * Łączy się z serwerem gry przez WebSocket, gra muzykę regionu z płynnymi przejściami
 * i efekty wysyłane z gry. Protokół: dzwiek/README.md. */
(() => {
  'use strict';

  const LOCAL = location.hostname === 'localhost' || location.hostname === '127.0.0.1';
  const RESUME_KEY = 'mcrpg.dzwiek.resume';
  const VOL_KEY = 'mcrpg.dzwiek.vol';
  const MAX_SFX = 8;
  const MAX_DECKS = 3;
  const MAX_RETRIES = 40;
  const SAFE_SRC = /^[A-Za-z0-9_-]+(\/[A-Za-z0-9_-]+)*\.(ogg|m4a|mp3|webm|opus)$/;
  const SAFE_BASE = /^([A-Za-z0-9_-]+\/)*$/;
  const SAFE_ID = /^[a-z0-9_]{1,32}$/;

  // ---------------------------------------------------------------- pamięć przeglądarki
  const store = {
    get(area, key) {
      try { return window[area].getItem(key); } catch (e) { return null; }
    },
    set(area, key, value) {
      try { window[area].setItem(key, value); } catch (e) { /* tryb prywatny itp. */ }
    },
    del(area, key) {
      try { window[area].removeItem(key); } catch (e) { /* j.w. */ }
    }
  };
  // Token wznowienia tylko w sessionStorage: znika po zamknięciu karty.
  const resume = {
    get: () => store.get('sessionStorage', RESUME_KEY),
    set: (v) => store.set('sessionStorage', RESUME_KEY, v),
    del: () => store.del('sessionStorage', RESUME_KEY)
  };

  // ---------------------------------------------------------------- token z adresu
  // Token jest po # (nie trafia do serwera www ani do logów). Od razu usuwamy go z paska adresu.
  const params = new URLSearchParams(location.hash.slice(1));
  let linkToken = params.get('t');
  // Adres WebSocket podany ręcznie tylko przy testach na localhost (pamiętany do odświeżenia strony).
  const DEV_WS_KEY = 'mcrpg.dzwiek.devws';
  if (LOCAL && params.get('ws')) store.set('sessionStorage', DEV_WS_KEY, params.get('ws'));
  const devWs = LOCAL ? store.get('sessionStorage', DEV_WS_KEY) : null;
  if (location.hash) {
    history.replaceState(null, '', location.pathname + location.search);
  }
  const wsUrl = devWs || (() => {
    const u = new URL('ws', location.href);
    u.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    u.search = '';
    u.hash = '';
    return u.href;
  })();

  const vol = Object.assign({ master: 80, music: 70, sfx: 90, muted: false }, (() => {
    try { return JSON.parse(store.get('localStorage', VOL_KEY)) || {}; } catch (e) { return {}; }
  })());
  const saveVol = () => store.set('localStorage', VOL_KEY, JSON.stringify(vol));

  // ---------------------------------------------------------------- interfejs
  const $ = (id) => document.getElementById(id);
  const ui = {
    dot: $('dot'), status: $('status'), player: $('player'), nick: $('nick'), track: $('track'),
    unlock: $('unlock'), controls: $('controls'), mute: $('mute'),
    vol: { master: $('vol-master'), music: $('vol-music'), sfx: $('vol-sfx') }
  };

  function setStatus(text, kind) {
    ui.status.textContent = text;
    ui.dot.className = 'dot dot-' + kind;
  }

  // ---------------------------------------------------------------- stan
  const S = {
    catalog: null, base: 'audio/', name: null, online: false,
    want: null, fade: 2000, unlocked: false, connected: false
  };

  const clamp = (x, lo, hi) => Math.min(hi, Math.max(lo, x));
  const num = (x, def) => (typeof x === 'number' && isFinite(x) ? x : def);

  function sanitizeCatalog(raw) {
    const out = Object.create(null);
    if (!raw || typeof raw !== 'object') return out;
    for (const id of Object.keys(raw)) {
      const e = raw[id];
      if (!SAFE_ID.test(id) || !e || (e.k !== 'music' && e.k !== 'sfx') || !Array.isArray(e.src)) continue;
      const src = e.src.filter((p) => typeof p === 'string' && SAFE_SRC.test(p));
      if (!src.length) continue;
      out[id] = {
        k: e.k,
        src,
        v: clamp(num(e.v, 1), 0, 1),
        title: typeof e.title === 'string' ? e.title.slice(0, 80) : id
      };
    }
    return out;
  }

  // ---------------------------------------------------------------- silnik dźwięku
  let ctx = null;
  let master, musicBus, sfxBus, analyser;
  const decks = []; // { id, slot, el, gain, stopping, timer }
  // Stała pula elementów audio odblokowana kliknięciem. Safari pozwala grać bez kolejnego
  // kliknięcia tylko elementom, które raz zagrały w trakcie gestu, więc ich nie tworzymy na nowo.
  const pool = []; // { el, gain, busy }
  let silentUrl = null;
  let current = null;
  const sfxCache = new Map();
  let sfxActive = 0;

  const probe = document.createElement('audio');
  const MIME = {
    ogg: 'audio/ogg; codecs="opus"', opus: 'audio/ogg; codecs="opus"', webm: 'audio/webm; codecs="opus"',
    m4a: 'audio/mp4; codecs="mp4a.40.2"', mp3: 'audio/mpeg'
  };

  function pickSrc(list) {
    for (const p of list) {
      const mime = MIME[p.split('.').pop().toLowerCase()];
      if (mime && probe.canPlayType(mime)) return S.base + p;
    }
    return null;
  }

  const gainOf = (v) => Math.pow(clamp(v, 0, 100) / 100, 2);

  function applyVolumes() {
    if (!ctx) return;
    const t = ctx.currentTime;
    master.gain.setTargetAtTime(vol.muted ? 0 : gainOf(vol.master), t, 0.05);
    musicBus.gain.setTargetAtTime(gainOf(vol.music), t, 0.05);
    sfxBus.gain.setTargetAtTime(gainOf(vol.sfx), t, 0.05);
  }

  function ramp(param, value, ms) {
    const t = ctx.currentTime;
    param.cancelScheduledValues(t);
    param.setValueAtTime(param.value, t);
    if (ms <= 0) param.setValueAtTime(value, t);
    else param.linearRampToValueAtTime(value, t + ms / 1000);
  }

  function unlock() {
    if (S.unlocked) return;
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) {
      setStatus('Ta przeglądarka nie obsługuje dźwięku z gry', 'err');
      return;
    }
    ctx = new AC();
    master = ctx.createGain();
    musicBus = ctx.createGain();
    sfxBus = ctx.createGain();
    musicBus.connect(master);
    sfxBus.connect(master);
    master.connect(ctx.destination);
    if (LOCAL) {
      analyser = ctx.createAnalyser();
      analyser.fftSize = 2048;
      master.connect(analyser);
    }
    applyVolumes();
    ctx.resume();
    silentUrl = silentWav();
    for (let i = 0; i < MAX_DECKS; i++) {
      const el = new Audio();
      el.preload = 'auto';
      el.loop = true;
      el.src = silentUrl;
      const gain = ctx.createGain();
      gain.gain.value = 0;
      ctx.createMediaElementSource(el).connect(gain);
      gain.connect(musicBus);
      const p = el.play();
      if (p) p.then(() => el.pause()).catch(() => {});
      pool.push({ el, gain, busy: false });
    }
    S.unlocked = true;
    ui.unlock.hidden = true;
    ui.controls.hidden = false;
    preloadSfx();
    applyMusic();
    refreshStatus();
  }

  // 0,1 s ciszy (WAV) jako adres blob:, do odblokowania puli w trakcie kliknięcia
  function silentWav() {
    const n = 4800;
    const buf = new ArrayBuffer(44 + n * 2);
    const v = new DataView(buf);
    const str = (o, t) => { for (let i = 0; i < t.length; i++) v.setUint8(o + i, t.charCodeAt(i)); };
    str(0, 'RIFF'); v.setUint32(4, 36 + n * 2, true); str(8, 'WAVE'); str(12, 'fmt ');
    v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
    v.setUint32(24, 48000, true); v.setUint32(28, 96000, true); v.setUint16(32, 2, true);
    v.setUint16(34, 16, true); str(36, 'data'); v.setUint32(40, n * 2, true);
    return URL.createObjectURL(new Blob([buf], { type: 'audio/wav' }));
  }

  function applyMusic() {
    if (!S.unlocked || !S.catalog) return;
    const target = S.online && S.want && S.catalog[S.want] && S.catalog[S.want].k === 'music' ? S.want : null;
    if (target === current) return;
    current = target;
    const fade = clamp(S.fade, 0, 15000);
    for (const d of decks) if (!d.stopping) fadeOut(d, fade);
    if (target) {
      const back = decks.find((d) => d.id === target && d.stopping);
      if (back) {
        // ten sam utwór jeszcze wygasa: wracamy do niego zamiast zaczynać od nowa
        clearTimeout(back.timer);
        back.stopping = false;
        ramp(back.gain.gain, S.catalog[target].v, fade);
      } else {
        if (decks.length >= MAX_DECKS) dispose(decks[0]);
        const d = startDeck(target, fade);
        if (d) decks.push(d);
      }
    }
    showTrack();
  }

  function startDeck(id, fade) {
    const entry = S.catalog[id];
    const url = pickSrc(entry.src);
    if (!url) {
      console.warn('Brak obsługiwanego pliku dla', id);
      return null;
    }
    const slot = pool.find((x) => !x.busy);
    if (!slot) return null;
    slot.busy = true;
    const el = slot.el;
    const gain = slot.gain;
    el.loop = true;
    el.src = url;
    gain.gain.cancelScheduledValues(ctx.currentTime);
    gain.gain.setValueAtTime(0, ctx.currentTime);
    const d = { id, slot, el, gain, stopping: false, timer: 0 };
    ramp(gain.gain, entry.v, fade);
    el.play().catch((err) => console.warn('Nie udało się odtworzyć', id, err));
    return d;
  }

  function fadeOut(d, fade) {
    d.stopping = true;
    ramp(d.gain.gain, 0, fade);
    clearTimeout(d.timer);
    d.timer = setTimeout(() => dispose(d), fade + 250);
  }

  function dispose(d) {
    clearTimeout(d.timer);
    try {
      d.el.pause();
      d.gain.gain.cancelScheduledValues(ctx.currentTime);
      d.gain.gain.setValueAtTime(0, ctx.currentTime);
      d.el.src = silentUrl; // element zostaje odblokowany do ponownego użycia
    } catch (e) { /* element już zwolniony */ }
    d.slot.busy = false;
    const i = decks.indexOf(d);
    if (i >= 0) decks.splice(i, 1);
  }

  function stopAllAudio() {
    S.want = null;
    current = null;
    while (decks.length) dispose(decks[0]);
    showTrack();
  }

  function loadSfx(id) {
    let p = sfxCache.get(id);
    if (!p) {
      const entry = S.catalog && S.catalog[id];
      const url = entry && entry.k === 'sfx' ? pickSrc(entry.src) : null;
      if (!url) return Promise.reject(new Error('Brak pliku efektu ' + id));
      p = fetch(url, { credentials: 'same-origin' })
        .then((r) => {
          if (!r.ok) throw new Error('HTTP ' + r.status);
          return r.arrayBuffer();
        })
        .then((buf) => ctx.decodeAudioData(buf));
      p.catch(() => sfxCache.delete(id));
      sfxCache.set(id, p);
    }
    return p;
  }

  function preloadSfx() {
    if (!S.unlocked || !S.catalog) return;
    let n = 0;
    for (const id of Object.keys(S.catalog)) {
      if (S.catalog[id].k === 'sfx' && n++ < 40) loadSfx(id).catch(() => {});
    }
  }

  function playSfx(id, v) {
    if (!S.unlocked || !S.catalog || !S.online) return;
    const entry = S.catalog[id];
    if (!entry || entry.k !== 'sfx' || sfxActive >= MAX_SFX) return;
    sfxActive++;
    loadSfx(id).then((buf) => {
      const s = ctx.createBufferSource();
      s.buffer = buf;
      const g = ctx.createGain();
      g.gain.value = entry.v * clamp(v, 0, 2);
      s.connect(g);
      g.connect(sfxBus);
      s.onended = () => {
        sfxActive--;
        s.disconnect();
        g.disconnect();
      };
      s.start();
    }).catch((err) => {
      sfxActive--;
      console.warn('Efekt', id, err);
    });
  }

  // ---------------------------------------------------------------- widok
  function showTrack() {
    const entry = current && S.catalog && S.catalog[current];
    ui.track.textContent = entry ? entry.title : 'cisza';
    ui.track.classList.toggle('muted', !entry);
  }

  function refreshStatus() {
    if (!S.connected || !S.catalog) return;
    ui.player.hidden = false;
    ui.nick.textContent = S.name || '';
    if (!S.unlocked) {
      ui.unlock.hidden = false;
      setStatus('Połączono. Kliknij „Włącz dźwięk”', 'ok');
    } else if (S.online) {
      setStatus('Połączono z grą', 'ok');
    } else {
      setStatus('Czekam, aż wejdziesz do gry', 'idle');
    }
  }

  function needLink(text) {
    setStatus(text || 'Wpisz w grze /muzyka i kliknij link z czatu', 'idle');
  }

  // ---------------------------------------------------------------- połączenie
  const HARD_CLOSE = {
    4001: 'Link wygasł albo był już użyty. Wpisz w grze /muzyka',
    4002: 'Dźwięk otwarto w innej karcie',
    4005: 'Rozłączono po dłuższym czasie poza grą. Wpisz /muzyka po powrocie',
    4006: 'Dźwięk rozłączono komendą w grze'
  };

  let ws = null;
  let retry = 0;
  let retryTimer = 0;

  function connect() {
    clearTimeout(retryTimer);
    if (!linkToken && !resume.get()) {
      needLink();
      return;
    }
    setStatus(retry ? 'Ponowne łączenie…' : 'Łączenie…', 'wait');
    let sock;
    try {
      sock = new WebSocket(wsUrl);
    } catch (e) {
      scheduleRetry();
      return;
    }
    ws = sock;
    sock.onopen = () => {
      // token wybieramy dopiero po otwarciu: link nie przepada, gdy serwer akurat nie działa
      let msg;
      if (linkToken) {
        msg = { t: 'auth', token: linkToken };
        linkToken = null;
      } else {
        msg = { t: 'resume', token: resume.get() || '' };
      }
      sock.send(JSON.stringify(msg));
    };
    sock.onmessage = (e) => {
      if (ws !== sock || typeof e.data !== 'string') return;
      let m;
      try { m = JSON.parse(e.data); } catch (err) { return; }
      if (m && typeof m === 'object') handle(m);
    };
    sock.onclose = (e) => {
      if (ws !== sock) return;
      ws = null;
      S.connected = false;
      closed(e.code);
    };
  }

  function closed(code) {
    if (HARD_CLOSE[code]) {
      resume.del();
      stopAllAudio();
      ui.unlock.hidden = true;
      setStatus(HARD_CLOSE[code], code === 4002 ? 'idle' : 'err');
      return;
    }
    scheduleRetry(code === 4008 ? 30000 : 0);
  }

  function scheduleRetry(minDelay) {
    if (!linkToken && !resume.get()) {
      needLink();
      return;
    }
    if (retry >= MAX_RETRIES) {
      stopAllAudio();
      setStatus('Brak połączenia z serwerem. Wpisz /muzyka, gdy serwer wróci', 'err');
      return;
    }
    const delay = Math.max(minDelay || 0, Math.min(30000, 1000 * Math.pow(2, retry)) + Math.random() * 500);
    retry++;
    setStatus('Brak połączenia, ponawiam…', 'wait');
    retryTimer = setTimeout(connect, delay);
  }

  function handle(m) {
    switch (m.t) {
      case 'session':
        if (typeof m.resume === 'string') resume.set(m.resume);
        retry = 0;
        S.connected = true;
        break;
      case 'hello':
        S.catalog = sanitizeCatalog(m.catalog);
        S.base = typeof m.base === 'string' && SAFE_BASE.test(m.base) ? m.base : 'audio/';
        S.name = typeof m.name === 'string' ? m.name.slice(0, 32) : null;
        S.online = m.online === true;
        sfxCache.clear();
        preloadSfx();
        refreshStatus();
        applyMusic();
        break;
      case 'music':
        S.want = typeof m.id === 'string' ? m.id : null;
        S.fade = num(m.fade, 2000);
        applyMusic();
        break;
      case 'sfx':
        if (typeof m.id === 'string') playSfx(m.id, num(m.vol, 1));
        break;
      case 'status':
        S.online = m.online === true;
        if (!S.online) S.want = null; // po powrocie serwer sam przyśle muzykę
        refreshStatus();
        applyMusic();
        break;
      default:
        break;
    }
  }

  // ---------------------------------------------------------------- obsługa przycisków
  for (const k of ['master', 'music', 'sfx']) {
    ui.vol[k].value = String(clamp(num(vol[k], 80), 0, 100));
    ui.vol[k].addEventListener('input', () => {
      vol[k] = Number(ui.vol[k].value);
      saveVol();
      applyVolumes();
    });
  }
  const showMute = () => {
    ui.mute.setAttribute('aria-pressed', String(!!vol.muted));
    ui.mute.textContent = vol.muted ? 'Włącz głos' : 'Wycisz';
  };
  showMute();
  ui.mute.addEventListener('click', () => {
    vol.muted = !vol.muted;
    saveVol();
    showMute();
    applyVolumes();
  });
  ui.unlock.addEventListener('click', unlock);

  document.addEventListener('visibilitychange', () => {
    if (ctx && document.visibilityState === 'visible' && ctx.state !== 'running') ctx.resume();
  });

  // ---------------------------------------------------------------- testy lokalne
  if (LOCAL) {
    window.__dzwiek = {
      state: () => ({
        connected: S.connected, unlocked: S.unlocked, online: S.online, want: S.want, current,
        hasResume: !!resume.get(), sfxActive, ctxState: ctx ? ctx.state : null, status: ui.status.textContent,
        decks: decks.map((d) => ({
          id: d.id, gain: d.gain.gain.value, stopping: d.stopping, time: d.el.currentTime, paused: d.el.paused
        }))
      }),
      level: () => {
        if (!analyser) return 0;
        const a = new Float32Array(analyser.fftSize);
        analyser.getFloatTimeDomainData(a);
        let s = 0;
        for (const x of a) s += x * x;
        return Math.sqrt(s / a.length);
      }
    };
  }

  connect();
})();
