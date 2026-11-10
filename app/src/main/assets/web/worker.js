// Web Worker: Whisper lokal im Browser (transformers.js)
// Bibliothek liegt fest in der App (lib/), wird beim Build mitgeliefert → offline nutzbar
import { pipeline, env } from './lib/transformers.min.js';

env.allowLocalModels = false;
// ONNX-Runtime-Dateien (.wasm/.mjs) ebenfalls lokal statt vom CDN laden
env.backends.onnx.wasm.wasmPaths = new URL('./lib/', self.location.href).href;
env.useBrowserCache = true; // Modell wird nach dem ersten Download im Browser gespeichert

let asr = null;
let loadedModel = null;
let loadedDevice = null;

async function hasWebGPU() {
  try {
    if (!self.navigator || !navigator.gpu) return false;
    const adapter = await navigator.gpu.requestAdapter();
    return !!adapter;
  } catch {
    return false;
  }
}

function progressCallback(p) {
  if (p.status === 'progress' && p.total) {
    self.postMessage({ type: 'progress', file: p.file, loaded: p.loaded, total: p.total });
  }
}

async function getPipeline(model, preferGPU) {
  if (asr && loadedModel === model) return asr;
  if (asr && asr.dispose) { try { await asr.dispose(); } catch {} }
  asr = null;

  const useGPU = preferGPU && await hasWebGPU();
  self.postMessage({ type: 'status', text: 'Modell wird geladen …' });

  if (useGPU) {
    try {
      asr = await pipeline('automatic-speech-recognition', model, {
        device: 'webgpu',
        dtype: { encoder_model: 'fp32', decoder_model_merged: 'q4' },
        progress_callback: progressCallback
      });
      loadedDevice = 'GPU';
    } catch (e) {
      asr = null; // Fallback auf CPU
    }
  }
  if (!asr) {
    asr = await pipeline('automatic-speech-recognition', model, {
      device: 'wasm',
      dtype: 'q8',
      progress_callback: progressCallback
    });
    loadedDevice = 'CPU';
  }
  loadedModel = model;
  return asr;
}

self.onmessage = async (e) => {
  const { type, audio, model, language, preferGPU } = e.data;
  if (type !== 'transcribe') return;
  try {
    const t0 = performance.now();
    const p = await getPipeline(model, preferGPU);
    self.postMessage({ type: 'status', text: `Transkribiere (${loadedDevice}) …` });

    const opts = { task: 'transcribe', chunk_length_s: 30, stride_length_s: 5 };
    if (language && language !== 'auto') opts.language = language;

    const out = await p(audio, opts);
    const text = (Array.isArray(out) ? out.map((o) => o.text).join(' ') : out.text || '').trim();
    self.postMessage({ type: 'result', text, seconds: (performance.now() - t0) / 1000, device: loadedDevice });
  } catch (err) {
    self.postMessage({ type: 'error', message: String(err && err.message || err) });
  }
};
