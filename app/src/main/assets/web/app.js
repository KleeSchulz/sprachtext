// Sprachtext – Haupt-Logik
const $ = (id) => document.getElementById(id);
const els = {
  file: $('file'), work: $('work'), status: $('status'), barfill: $('barfill'), meta: $('meta'),
  result: $('result'), text: $('text'), copy: $('copy'), share: $('share'),
  model: $('model'), lang: $('lang'), gpu: $('gpu'),
  historyBox: $('historyBox'), history: $('history'), clearHist: $('clearHist')
};

// ---------- Einstellungen merken ----------
const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} }
};
els.model.value = store.get('model', els.model.value);
els.lang.value = store.get('lang', els.lang.value);
els.gpu.checked = store.get('gpu', true);
els.model.onchange = () => store.set('model', els.model.value);
els.lang.onchange = () => store.set('lang', els.lang.value);
els.gpu.onchange = () => store.set('gpu', els.gpu.checked);

// ---------- Service Worker ----------
const NATIVE = typeof window.AndroidBridge !== 'undefined';
if (!NATIVE && 'serviceWorker' in navigator) {
  navigator.serviceWorker.register('sw.js').catch((e) => console.warn('SW:', e));
}

// ---------- Worker ----------
const worker = new Worker('worker.js', { type: 'module' });
let busy = false;
const fileProgress = new Map();

worker.onmessage = (e) => {
  const m = e.data;
  if (m.type === 'status') {
    setStatus(m.text);
    if (m.text.startsWith('Transkribiere')) setBar(null);
  } else if (m.type === 'progress') {
    fileProgress.set(m.file, m);
    let loaded = 0, total = 0;
    for (const f of fileProgress.values()) { loaded += f.loaded; total += f.total; }
    setStatus('Modell wird geladen (nur beim ersten Mal) …');
    setBar(total ? loaded / total : null);
    els.meta.textContent = `${mb(loaded)} / ${mb(total)} MB`;
  } else if (m.type === 'result') {
    busy = false;
    nativeEnd(m.text || '(kein Text erkannt)', true);
    setBar(1);
    setStatus('Fertig');
    els.meta.textContent = `${m.seconds.toFixed(1)} s · ${m.device}`;
    showResult(m.text || '(kein Text erkannt)');
    addHistory(m.text);
  } else if (m.type === 'error') {
    busy = false;
    nativeEnd(m.message, false);
    setStatus('Fehler: ' + m.message, true);
    setBar(0);
  }
};

// ---------- Hintergrunddienst (nur Android-App) ----------
function nativeStart() { if (NATIVE && window.AndroidBridge.busyStart) try { window.AndroidBridge.busyStart(); } catch {} }
function nativeEnd(text, ok) { if (NATIVE && window.AndroidBridge.busyEnd) try { window.AndroidBridge.busyEnd(String(text || ''), ok); } catch {} }

// ---------- UI-Helfer ----------
function setStatus(t, err = false) {
  els.work.classList.remove('hidden');
  els.status.textContent = t;
  els.status.classList.toggle('err', err);
}
function setBar(frac) {
  if (frac === null) { els.barfill.classList.add('indet'); els.barfill.style.width = ''; }
  else { els.barfill.classList.remove('indet'); els.barfill.style.width = Math.round(frac * 100) + '%'; }
}
const mb = (b) => (b / 1048576).toFixed(0);
function showResult(t) {
  els.result.classList.remove('hidden');
  els.text.value = t;
}

// ---------- Audio dekodieren (16 kHz mono) ----------
async function decodeTo16kMono(blob) {
  const buf = await blob.arrayBuffer();
  const ctx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 16000 });
  try {
    const audio = await ctx.decodeAudioData(buf);
    if (audio.numberOfChannels === 1) return audio.getChannelData(0);
    const out = new Float32Array(audio.length);
    for (let c = 0; c < audio.numberOfChannels; c++) {
      const d = audio.getChannelData(c);
      for (let i = 0; i < d.length; i++) out[i] += d[i] / audio.numberOfChannels;
    }
    return out;
  } finally {
    ctx.close();
  }
}

async function processFile(blob, name = '') {
  if (busy) return;
  busy = true;
  fileProgress.clear();
  els.result.classList.add('hidden');
  try {
    setStatus('Audio wird gelesen …');
    setBar(null);
    els.meta.textContent = name;
    const audio = await decodeTo16kMono(blob);
    const secs = audio.length / 16000;
    els.meta.textContent = `${name ? name + ' · ' : ''}${fmtDur(secs)}`;
    nativeStart();
    worker.postMessage({
      type: 'transcribe', audio, model: els.model.value,
      language: els.lang.value, preferGPU: els.gpu.checked
    }, [audio.buffer]);
  } catch (e) {
    busy = false;
    setStatus('Audio konnte nicht gelesen werden: ' + (e.message || e), true);
    setBar(0);
  }
}
const fmtDur = (s) => `${Math.floor(s / 60)}:${String(Math.round(s % 60)).padStart(2, '0')} min`;

// ---------- Eingänge ----------
els.file.onchange = () => {
  const f = els.file.files[0];
  if (f) processFile(f, f.name);
  els.file.value = '';
};

async function checkShared() {
  const params = new URLSearchParams(location.search);
  const shared = params.get('shared');
  if (!shared) return;
  history.replaceState(null, '', location.pathname);
  if (shared === 'empty') return setStatus('Es wurde keine Audiodatei geteilt.', true);
  if (shared === 'error') return setStatus('Die geteilte Datei konnte nicht übernommen werden.', true);
  if (shared === 'native') {
    // Android-App: Datei liegt im App-Speicher und wird über /shared/ ausgeliefert
    try {
      const res = await fetch('/shared/audio.bin?t=' + Date.now(), { cache: 'no-store' });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      processFile(await res.blob(), params.get('name') || 'Sprachnachricht');
    } catch (e) {
      setStatus('Fehler beim Lesen der geteilten Datei: ' + e.message, true);
    }
    return;
  }
  try {
    const cache = await caches.open('sprachtext-inbox');
    const key = new URL('shared-audio', location.href).href;
    const res = await cache.match(key);
    if (!res) return setStatus('Geteilte Datei nicht gefunden.', true);
    const name = decodeURIComponent(res.headers.get('X-Filename') || '');
    const blob = await res.blob();
    await cache.delete(key);
    processFile(blob, name);
  } catch (e) {
    setStatus('Fehler beim Lesen der geteilten Datei: ' + e.message, true);
  }
}
checkShared();

// ---------- Aktionen ----------
els.copy.onclick = async () => {
  if (NATIVE) { window.AndroidBridge.copy(els.text.value); return flash(els.copy, 'Kopiert ✓'); }
  try { await navigator.clipboard.writeText(els.text.value); flash(els.copy, 'Kopiert ✓'); }
  catch { els.text.select(); document.execCommand('copy'); flash(els.copy, 'Kopiert ✓'); }
};
els.share.onclick = async () => {
  if (NATIVE) return window.AndroidBridge.share(els.text.value);
  if (navigator.share) { try { await navigator.share({ text: els.text.value }); } catch {} }
  else els.copy.click();
};
function flash(btn, t) { const o = btn.textContent; btn.textContent = t; setTimeout(() => (btn.textContent = o), 1500); }

// ---------- Verlauf (lokal, max. 20) ----------
function addHistory(text) {
  if (!text) return;
  const h = store.get('history', []);
  h.unshift({ t: Date.now(), text });
  store.set('history', h.slice(0, 20));
  renderHistory();
}
function renderHistory() {
  const h = store.get('history', []);
  els.historyBox.classList.toggle('hidden', h.length === 0);
  els.history.innerHTML = '';
  for (const item of h) {
    const li = document.createElement('li');
    const small = document.createElement('small');
    small.textContent = new Date(item.t).toLocaleString('de-DE');
    li.append(small, document.createTextNode(item.text.length > 140 ? item.text.slice(0, 140) + ' …' : item.text));
    li.onclick = () => { showResult(item.text); window.scrollTo({ top: 0, behavior: 'smooth' }); };
    els.history.append(li);
  }
}
els.clearHist.onclick = () => { store.set('history', []); renderHistory(); };
renderHistory();
