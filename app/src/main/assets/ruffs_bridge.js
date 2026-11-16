/*
 * ruffs_bridge.js — puente entre la interfaz web de RUFFS y Android.
 *
 * Se inyecta al inicio de cada página (antes que los scripts de RUFFS) y rellena
 * las APIs del navegador que el WebView de Android no trae o bloquea en http://:
 *   - micrófono (getUserMedia + MediaRecorder) → grabación nativa
 *   - SpeechRecognition / webkitSpeechRecognition → SpeechRecognizer de Android
 *   - speechSynthesis / SpeechSynthesisUtterance → TextToSpeech de Android
 *   - Notification → notificaciones de Android
 *   - navigator.clipboard, navigator.share, navigator.vibrate
 *   - descargas de blob:/data: (la CSP de RUFFS, connect-src 'self', impide
 *     leer blob: con XHR, así que guardamos el Blob al crearlo)
 *
 * Comunicación: JS → Android con window.RuffsNative.metodo(...);
 *               Android → JS con window.__ruffs.emit(canal, datos) / resolve(id, ok, datos).
 */
(function () {
  'use strict';
  if (window.__ruffs) return;
  var N = window.RuffsNative;
  if (!N) return;

  var R = (window.__ruffs = { version: 1, seq: 0, cbs: {}, listeners: {} });

  function cid() { R.seq += 1; return 'r' + R.seq + '_' + Date.now().toString(36); }
  function pending() {
    var id = cid();
    var p = new Promise(function (res, rej) { R.cbs[id] = { res: res, rej: rej }; });
    return { id: id, promise: p };
  }
  R.resolve = function (id, ok, data) {
    var c = R.cbs[id];
    if (!c) return;
    delete R.cbs[id];
    if (ok) c.res(data); else c.rej(data);
  };
  R.on = function (ch, fn) { (R.listeners[ch] = R.listeners[ch] || []).push(fn); };
  R.emit = function (ch, data) {
    var l = R.listeners[ch] || [];
    for (var i = 0; i < l.length; i++) { try { l[i](data); } catch (e) { console.error('[RUFFS app]', e); } }
  };

  // ── Utilidad de eventos (onX + addEventListener) ─────────────────────────────
  function Emitter() {}
  Emitter.prototype.addEventListener = function (t, f) {
    if (!this.__l) this.__l = {};
    (this.__l[t] = this.__l[t] || []).push(f);
  };
  Emitter.prototype.removeEventListener = function (t, f) {
    if (!this.__l || !this.__l[t]) return;
    this.__l[t] = this.__l[t].filter(function (x) { return x !== f; });
  };
  Emitter.prototype.dispatchEvent = function (ev) { fire(this, ev.type, ev); return true; };
  function fire(obj, type, ev) {
    ev = ev || {};
    try { ev.type = type; } catch (e) {}
    try { if (!ev.target) ev.target = obj; } catch (e) {}
    try { if (!ev.currentTarget) ev.currentTarget = obj; } catch (e) {}
    var h = obj['on' + type];
    if (typeof h === 'function') { try { h.call(obj, ev); } catch (e) { console.error(e); } }
    var l = obj.__l && obj.__l[type];
    if (l) l.slice().forEach(function (f) { try { f.call(obj, ev); } catch (e) { console.error(e); } });
  }
  function inherit(C) { C.prototype = Object.create(Emitter.prototype); C.prototype.constructor = C; }
  function define(obj, name, value) {
    try { Object.defineProperty(obj, name, { value: value, configurable: true, writable: true }); }
    catch (e) { try { obj[name] = value; } catch (e2) {} }
  }
  function domError(msg, name) {
    try { return new DOMException(msg, name); } catch (e) { var er = new Error(msg); er.name = name; return er; }
  }

  // ── Contexto seguro ──────────────────────────────────────────────────────────
  // En http:// (LAN / Tailscale) el WebView no es "contexto seguro" y RUFFS
  // rechaza el micrófono y el portapapeles. Aquí los da la app de forma nativa.
  var insecure = !window.isSecureContext;
  if (insecure) {
    try { Object.defineProperty(window, 'isSecureContext', { get: function () { return true; }, configurable: true }); } catch (e) {}
  }

  // ── Portapapeles ─────────────────────────────────────────────────────────────
  var clipboard = {
    writeText: function (t) { N.copyText(String(t == null ? '' : t)); return Promise.resolve(); },
    readText: function () { var p = pending(); N.readClipboard(p.id); return p.promise; },
    write: function (items) {
      items = items || [];
      for (var i = 0; i < items.length; i++) {
        var it = items[i];
        if (it && it.types && it.types.indexOf('text/plain') >= 0) {
          return it.getType('text/plain').then(function (b) { return b.text(); }).then(function (t) { N.copyText(t); });
        }
      }
      return Promise.reject(domError('Solo se puede copiar texto', 'NotAllowedError'));
    },
    read: function () {
      return clipboard.readText().then(function (t) {
        var blob = new Blob([t], { type: 'text/plain' });
        return [{ types: ['text/plain'], getType: function () { return Promise.resolve(blob); } }];
      });
    },
    addEventListener: function () {}, removeEventListener: function () {}
  };
  define(navigator, 'clipboard', clipboard);

  // ── Vibración y compartir ────────────────────────────────────────────────────
  define(navigator, 'vibrate', function (p) {
    var arr = Array.isArray(p) ? p : [p];
    N.vibrate(JSON.stringify(arr.map(function (x) { return Math.max(0, x | 0); })));
    return true;
  });
  define(navigator, 'canShare', function (d) {
    // Los archivos se guardan en Descargas (Android.processBlob) en vez de compartirse.
    return !!d && !(d.files && d.files.length) && !!(d.text || d.url || d.title);
  });
  define(navigator, 'share', function (d) {
    d = d || {};
    if (d.files && d.files.length) return Promise.reject(domError('Compartir archivos no está disponible', 'NotAllowedError'));
    N.share(String(d.title || ''), String(d.text || ''), String(d.url || ''));
    return Promise.resolve();
  });

  // ── Notificaciones ───────────────────────────────────────────────────────────
  var notifs = {};
  function RuffsNotification(title, opts) {
    opts = opts || {};
    this.title = String(title == null ? '' : title);
    this.body = String(opts.body || '');
    this.tag = String(opts.tag || '');
    this.icon = opts.icon || '';
    this.data = opts.data;
    this.onclick = this.onshow = this.onclose = this.onerror = null;
    this.__id = cid();
    notifs[this.__id] = this;
    var self = this;
    if (N.notificationPermission() !== 'granted') {
      setTimeout(function () { fire(self, 'error', {}); }, 0);
      return;
    }
    N.notify(this.__id, this.title, this.body, this.tag);
    setTimeout(function () { fire(self, 'show', {}); }, 0);
  }
  inherit(RuffsNotification);
  RuffsNotification.prototype.close = function () {
    N.cancelNotification(this.__id, this.tag);
    delete notifs[this.__id];
    fire(this, 'close', {});
  };
  Object.defineProperty(RuffsNotification, 'permission', { get: function () { return N.notificationPermission(); } });
  RuffsNotification.maxActions = 0;
  RuffsNotification.requestPermission = function (cb) {
    var p = pending();
    N.requestNotificationPermission(p.id);
    return p.promise.then(function (r) { if (typeof cb === 'function') cb(r); return r; });
  };
  define(window, 'Notification', RuffsNotification);
  R.on('notificationclick', function (id) {
    var n = notifs[id];
    if (!n) return;
    fire(n, 'click', { preventDefault: function () {} });
  });

  // ── Síntesis de voz (TTS) ────────────────────────────────────────────────────
  function Utterance(text) {
    this.text = text == null ? '' : String(text);
    this.lang = ''; this.voice = null; this.volume = 1; this.rate = 1; this.pitch = 1;
    this.onstart = this.onend = this.onerror = this.onpause = this.onresume = this.onboundary = this.onmark = null;
  }
  inherit(Utterance);
  var voices = [];
  var speaking = {};
  function loadVoices() {
    try {
      voices = JSON.parse(N.ttsVoices() || '[]').map(function (v) {
        return { name: v.name, lang: v.lang, voiceURI: v.id, default: !!v.default, localService: !!v.local };
      });
    } catch (e) { voices = []; }
  }
  function Synth() { this.onvoiceschanged = null; }
  inherit(Synth);
  var synth = new Synth();
  Object.defineProperty(synth, 'speaking', { get: function () { return Object.keys(speaking).length > 0; } });
  Object.defineProperty(synth, 'pending', { get: function () { return Object.keys(speaking).length > 1; } });
  Object.defineProperty(synth, 'paused', { get: function () { return false; } });
  synth.getVoices = function () { return voices.slice(); };
  synth.speak = function (u) {
    if (!u) return;
    var id = cid();
    speaking[id] = u;
    var voiceId = u.voice && u.voice.voiceURI ? u.voice.voiceURI : '';
    var lang = u.lang || (u.voice && u.voice.lang) || '';
    N.ttsSpeak(id, String(u.text || ''), String(lang), String(voiceId),
      Number(u.rate) || 1, Number(u.pitch) || 1, u.volume == null ? 1 : Number(u.volume));
  };
  synth.cancel = function () { N.ttsCancel(); };
  synth.pause = function () { N.ttsCancel(); };
  synth.resume = function () {};
  R.on('tts', function (d) {
    var u = speaking[d.id];
    if (!u) return;
    if (d.ev === 'start') { fire(u, 'start', { utterance: u, charIndex: 0, elapsedTime: 0 }); return; }
    delete speaking[d.id];
    if (d.ev === 'error') fire(u, 'error', { utterance: u, error: d.error || 'synthesis-failed' });
    else fire(u, 'end', { utterance: u, charIndex: (u.text || '').length, elapsedTime: 0 });
  });
  R.on('voiceschanged', function () { loadVoices(); fire(synth, 'voiceschanged', {}); });
  loadVoices();
  define(window, 'speechSynthesis', synth);
  define(window, 'SpeechSynthesisUtterance', Utterance);

  // ── Reconocimiento de voz ────────────────────────────────────────────────────
  var recognitions = {};
  function Recognition() {
    this.lang = ''; this.continuous = false; this.interimResults = false; this.maxAlternatives = 1; this.grammars = null;
    this.onstart = this.onend = this.onerror = this.onresult = this.onnomatch = null;
    this.onaudiostart = this.onaudioend = this.onspeechstart = this.onspeechend = this.onsoundstart = this.onsoundend = null;
    this.__id = null; this.__results = [];
  }
  inherit(Recognition);
  Recognition.prototype.start = function () {
    if (this.__id) throw domError('recognition has already started', 'InvalidStateError');
    this.__id = cid();
    this.__results = [];
    recognitions[this.__id] = this;
    N.srStart(this.__id, String(this.lang || ''), !!this.continuous, !!this.interimResults, (this.maxAlternatives | 0) || 1);
  };
  Recognition.prototype.stop = function () { if (this.__id) N.srStop(this.__id); };
  Recognition.prototype.abort = function () { if (this.__id) N.srAbort(this.__id); };
  function makeList(arr) { arr.item = function (i) { return this[i]; }; return arr; }
  R.on('sr', function (d) {
    var r = recognitions[d.id];
    if (!r) return;
    switch (d.ev) {
      case 'start': fire(r, 'start', {}); fire(r, 'audiostart', {}); break;
      case 'speechstart': fire(r, 'soundstart', {}); fire(r, 'speechstart', {}); break;
      case 'speechend': fire(r, 'speechend', {}); fire(r, 'soundend', {}); break;
      case 'result': {
        var res = r.__results;
        var idx = res.length && !res[res.length - 1].isFinal ? res.length - 1 : res.length;
        var alts = makeList((d.alts || []).map(function (a) { return { transcript: a.t, confidence: a.c }; }));
        alts.isFinal = !!d.final;
        res[idx] = alts;
        fire(r, 'result', { resultIndex: idx, results: makeList(res.slice()) });
        break;
      }
      case 'nomatch': fire(r, 'nomatch', {}); break;
      case 'error': fire(r, 'error', { error: d.error, message: d.message || '' }); break;
      case 'end':
        delete recognitions[d.id];
        r.__id = null;
        fire(r, 'audioend', {});
        fire(r, 'end', {});
        break;
    }
  });
  define(window, 'SpeechRecognition', Recognition);
  define(window, 'webkitSpeechRecognition', Recognition);

  // ── Micrófono: getUserMedia + MediaRecorder ──────────────────────────────────
  // Solo si el WebView no los ofrece (http://). Con HTTPS se usan los reales.
  if (!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)) {
    var FakeTrack = function () { this.kind = 'audio'; this.enabled = true; this.muted = false; this.readyState = 'live'; this.label = 'Micrófono'; this.id = cid(); };
    inherit(FakeTrack);
    FakeTrack.prototype.stop = function () { this.readyState = 'ended'; };
    FakeTrack.prototype.getSettings = function () { return { deviceId: 'default', channelCount: 1 }; };
    var FakeStream = function () { this.__ruffsFake = true; this.id = cid(); this.active = true; this.__tracks = [new FakeTrack()]; };
    inherit(FakeStream);
    FakeStream.prototype.getTracks = function () { return this.__tracks.slice(); };
    FakeStream.prototype.getAudioTracks = function () { return this.__tracks.slice(); };
    FakeStream.prototype.getVideoTracks = function () { return []; };
    var md = navigator.mediaDevices || {};
    md.getUserMedia = function (c) {
      c = c || {};
      if (c.video) return Promise.reject(domError('La cámara en vivo necesita HTTPS', 'NotSupportedError'));
      if (!c.audio) return Promise.reject(domError('Nada que capturar', 'TypeError'));
      var p = pending();
      N.requestMic(p.id);
      return p.promise.then(function () { return new FakeStream(); }, function () {
        throw domError('Permiso de micrófono denegado', 'NotAllowedError');
      });
    };
    md.enumerateDevices = function () {
      return Promise.resolve([{ kind: 'audioinput', deviceId: 'default', label: 'Micrófono', groupId: 'default' }]);
    };
    if (!md.addEventListener) { md.addEventListener = function () {}; md.removeEventListener = function () {}; }
    define(navigator, 'mediaDevices', md);
  }

  var NativeMediaRecorder = window.MediaRecorder;
  var recorders = {};
  function RuffsMediaRecorder(stream, opts) {
    if (!(stream && stream.__ruffsFake)) {
      if (NativeMediaRecorder) return new NativeMediaRecorder(stream, opts);
      throw domError('MediaRecorder no disponible', 'NotSupportedError');
    }
    this.stream = stream;
    this.mimeType = 'audio/webm';
    this.state = 'inactive';
    this.audioBitsPerSecond = 64000;
    this.ondataavailable = this.onstop = this.onstart = this.onerror = this.onpause = this.onresume = null;
    this.__id = null;
  }
  inherit(RuffsMediaRecorder);
  RuffsMediaRecorder.isTypeSupported = function (t) {
    if (/^audio\//i.test(t || '')) return true;
    return NativeMediaRecorder ? NativeMediaRecorder.isTypeSupported(t) : false;
  };
  RuffsMediaRecorder.prototype.start = function () {
    if (this.state !== 'inactive') throw domError('Ya está grabando', 'InvalidStateError');
    this.__id = cid();
    recorders[this.__id] = this;
    this.state = 'recording';
    N.recStart(this.__id);
  };
  RuffsMediaRecorder.prototype.stop = function () {
    if (this.state === 'inactive') return;
    this.state = 'inactive';
    N.recStop(this.__id);
  };
  RuffsMediaRecorder.prototype.pause = function () {
    if (this.state !== 'recording') return;
    this.state = 'paused'; N.recPause(this.__id); fire(this, 'pause', {});
  };
  RuffsMediaRecorder.prototype.resume = function () {
    if (this.state !== 'paused') return;
    this.state = 'recording'; N.recResume(this.__id); fire(this, 'resume', {});
  };
  RuffsMediaRecorder.prototype.requestData = function () {};
  function b64ToBlob(b64, type) {
    var bin = atob(b64 || '');
    var arr = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
    return new Blob([arr], { type: type || 'application/octet-stream' });
  }
  R.on('rec', function (d) {
    var r = recorders[d.id];
    if (!r) return;
    if (d.ev === 'start') { fire(r, 'start', {}); return; }
    if (d.ev === 'data') {
      r.mimeType = d.mime || r.mimeType;
      fire(r, 'dataavailable', { data: b64ToBlob(d.b64, r.mimeType), timecode: Date.now() });
      return;
    }
    if (d.ev === 'error') {
      r.state = 'inactive';
      fire(r, 'error', { error: domError(d.message || 'Error al grabar', 'UnknownError') });
    }
    if (d.ev === 'stop' || d.ev === 'error') {
      delete recorders[d.id];
      r.state = 'inactive';
      fire(r, 'stop', {});
    }
  });
  define(window, 'MediaRecorder', RuffsMediaRecorder);

  // ── Descargas ────────────────────────────────────────────────────────────────
  var blobs = new Map();
  var origCreate = URL.createObjectURL;
  var origRevoke = URL.revokeObjectURL;
  URL.createObjectURL = function (obj) {
    var u = origCreate.apply(URL, arguments);
    try { if (obj instanceof Blob) blobs.set(u, obj); } catch (e) {}
    return u;
  };
  URL.revokeObjectURL = function (u) {
    // Guardamos el Blob un rato más: RUFFS revoca la URL al segundo de hacer click.
    setTimeout(function () { blobs.delete(u); }, 120000);
    return origRevoke.apply(URL, arguments);
  };

  var CHUNK = 3 * 1024 * 1024; // múltiplo de 3: cada trozo se codifica en base64 por separado
  function sendBlob(blob, name, mode) {
    var id = cid();
    var mime = blob.type || 'application/octet-stream';
    N.fileBegin(id, String(name || 'descarga'), mime, blob.size || 0, mode || 'save');
    var off = 0;
    function next() {
      if (off >= blob.size) { N.fileEnd(id); return; }
      var part = blob.slice(off, off + CHUNK);
      off += CHUNK;
      var fr = new FileReader();
      fr.onload = function () {
        var s = fr.result;
        N.fileChunk(id, s.slice(s.indexOf(',') + 1));
        next();
      };
      fr.onerror = function () { N.fileAbort(id, String(fr.error)); };
      fr.readAsDataURL(part);
    }
    next();
  }
  R.saveBlob = sendBlob;

  function dataUrlToBlob(url) {
    var comma = url.indexOf(',');
    var head = url.slice(5, comma);
    var mime = head.split(';')[0] || 'application/octet-stream';
    var data = url.slice(comma + 1);
    if (/;base64/i.test(head)) return b64ToBlob(data, mime);
    return new Blob([decodeURIComponent(data)], { type: mime });
  }
  function fileNameFor(a, href) {
    var n = a && a.getAttribute && a.getAttribute('download');
    if (n) return n;
    try {
      var last = new URL(href, location.href).pathname.split('/').pop();
      return decodeURIComponent(last) || 'descarga';
    } catch (e) { return 'descarga'; }
  }
  function sameOrigin(href) {
    try { return new URL(href, location.href).origin === location.origin; } catch (e) { return false; }
  }
  function handleLink(a) {
    var href = a.href;
    if (!href) return false;
    if (href.indexOf('blob:') === 0) {
      var b = blobs.get(href);
      if (!b) return false;
      sendBlob(b, fileNameFor(a, href), a.hasAttribute('download') ? 'save' : 'open');
      return true;
    }
    if (href.indexOf('data:') === 0) {
      sendBlob(dataUrlToBlob(href), fileNameFor(a, 'archivo'), 'save');
      return true;
    }
    if (a.hasAttribute('download') && sameOrigin(href)) {
      N.downloadUrl(href, a.getAttribute('download') || '');
      return true;
    }
    return false;
  }
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    try { if (handleLink(this)) return; } catch (e) { console.error('[RUFFS app] descarga', e); }
    return origClick.apply(this, arguments);
  };
  document.addEventListener('click', function (e) {
    var t = e.target;
    var a = t && t.closest ? t.closest('a[href]') : null;
    if (!a) return;
    if (!a.hasAttribute('download') && !/^(blob|data):/i.test(a.href)) return;
    try {
      if (handleLink(a)) { e.preventDefault(); e.stopImmediatePropagation(); }
    } catch (err) { console.error('[RUFFS app] descarga', err); }
  }, true);

  // window.open(blobURL) → abrir el archivo con el visor de Android
  var origOpen = window.open;
  window.open = function (url) {
    try {
      if (url) {
        var abs = new URL(String(url), location.href).href;
        if (abs.indexOf('blob:') === 0 && blobs.has(abs)) {
          sendBlob(blobs.get(abs), fileNameFor(null, abs), 'open');
          return null;
        }
      }
    } catch (e) {}
    return origOpen.apply(window, arguments);
  };
  // Para el DownloadListener nativo cuando llega una URL blob: que no pasó por un <a>
  R.saveBlobUrl = function (url, name) {
    var b = blobs.get(url);
    if (!b) return false;
    sendBlob(b, name || 'descarga', 'save');
    return true;
  };

  // ── Color de la barra de estado (sigue al tema de RUFFS) ─────────────────────
  var lastColor = null;
  var lastAccents = null;
  function reportTheme() {
    var m = document.querySelector('meta[name="theme-color"]');
    var c = m && m.getAttribute('content');
    if (!c && document.body) c = getComputedStyle(document.body).backgroundColor;
    if (c && c !== lastColor) { lastColor = c; N.themeColor(String(c)); }
    // Colores de acento del tema (también los usa el círculo de recarga)
    var rs = getComputedStyle(document.documentElement);
    var a1 = rs.getPropertyValue('--red').trim();
    var a2 = rs.getPropertyValue('--accent-2').trim();
    var key = a1 + '|' + a2;
    if (a1 && key !== lastAccents) { lastAccents = key; N.themeAccents(a1, a2 || a1); }
  }
  function watchTheme() {
    reportTheme();
    var mo = new MutationObserver(function () { reportTheme(); });
    mo.observe(document.head, { subtree: true, childList: true, attributes: true, attributeFilter: ['content'] });
    if (document.body) mo.observe(document.body, { attributes: true, attributeFilter: ['class', 'style', 'data-theme'] });
    mo.observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'style', 'data-theme'] });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', watchTheme);
  else watchTheme();

  // ── Tirar para recargar ──────────────────────────────────────────────────────
  // Android decide si el gesto es una recarga cuando el dedo ya se movió unos
  // píxeles. Antes de eso, aquí se le dice:
  //   - si se puede tirar (lo que hay bajo el dedo está arriba del todo, no es
  //     arrastrable, no es un campo de texto ni está dentro de un modal);
  //   - si RUFFS empezó a arrastrar algo (chips/burbujas flotantes, ventanas,
  //     listas ordenables, deslizadores): entonces la recarga no se activa y el
  //     arrastre no se interrumpe (en la app de Odysseus sí se rompía).
  var touching = false;
  var dragging = false;
  var lastAllowed = null;
  function setDragging(v) { if (v !== dragging) { dragging = v; N.setDragging(v); } }
  function setAllowed(v) { if (v !== lastAllowed) { lastAllowed = v; N.setCanRefresh(v); } }

  var DRAG_SEL = [
    '[draggable="true"]', '.minimized-dock-chip', '.drag-handle', '.draggable', '.dragging',
    '.touch-dragging', '[data-drag]', '[data-draggable]', '[class*="resize-handle"]',
    'input[type="range"]', 'canvas', '[class*="slider"]', '[class*="grip"]',
  ].join(',');
  var MODAL_SEL = '.modal:not(.hidden),[role="dialog"],[aria-modal="true"]';

  function blocksVerticalPan(el) {
    var ta = getComputedStyle(el).touchAction;
    return !!ta && ta !== 'auto' && ta !== 'manipulation' && ta.indexOf('pan-y') < 0 && ta.indexOf('pan-down') < 0;
  }

  function pullAllowedAt(target) {
    if (dragging) return false;
    try { if (String(window.getSelection() || '')) return false; } catch (e) {}
    var se = document.scrollingElement;
    if (se && se.scrollTop > 2) return false;
    var el = target && target.nodeType === 1 ? target : target && target.parentElement;
    for (; el && el.nodeType === 1 && el !== document.documentElement; el = el.parentElement) {
      if (el.scrollTop > 2 && el.scrollHeight > el.clientHeight + 2) return false;
      if (el.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName)) return false;
      if (el.matches(DRAG_SEL) || el.matches(MODAL_SEL)) return false;
      if (blocksVerticalPan(el)) return false;
    }
    return true;
  }

  // pointerdown llega antes que touchstart, y en fase de captura sobre window
  // corre antes que cualquier manejador de RUFFS.
  window.addEventListener('pointerdown', function (e) {
    if (e.pointerType !== 'touch' || !e.isPrimary) return;
    touching = true;
    setDragging(false);
    setAllowed(pullAllowedAt(e.target));
  }, { capture: true, passive: true });
  function endTouch(e) {
    if (e.pointerType && e.pointerType !== 'touch') return;
    touching = false;
    setDragging(false);
  }
  window.addEventListener('pointerup', endTouch, { capture: true, passive: true });
  window.addEventListener('pointercancel', endTouch, { capture: true, passive: true });
  window.addEventListener('touchend', endTouch, { capture: true, passive: true });
  window.addEventListener('touchcancel', endTouch, { capture: true, passive: true });

  // RUFFS empieza un arrastre añadiendo un "move" durante el toque…
  var origAddListener = EventTarget.prototype.addEventListener;
  EventTarget.prototype.addEventListener = function (type) {
    if (touching && (type === 'pointermove' || type === 'touchmove')) setDragging(true);
    return origAddListener.apply(this, arguments);
  };
  // …o capturando el puntero…
  if (Element.prototype.setPointerCapture) {
    var origCapture = Element.prototype.setPointerCapture;
    Element.prototype.setPointerCapture = function () {
      if (touching) setDragging(true);
      return origCapture.apply(this, arguments);
    };
  }
  // …o bloqueando el desplazamiento del navegador durante el movimiento.
  window.addEventListener('touchmove', function (e) {
    if (e.defaultPrevented) setDragging(true);
  }, { passive: true });

  // Antes de recargar: no cortar una respuesta en curso y guardar lo escrito.
  var DRAFT_KEY = '__ruffs_app_draft';
  R.beforeRefresh = function () {
    var busy = !!document.querySelector('[data-mode="streaming"],.agent-thread.streaming');
    if (!busy) {
      try {
        var m = document.getElementById('message');
        if (m && m.value && m.value.trim()) sessionStorage.setItem(DRAFT_KEY, m.value);
        else sessionStorage.removeItem(DRAFT_KEY);
      } catch (e) {}
    }
    return { busy: busy };
  };
  // Después de recargar: devolver lo escrito a la caja de mensaje.
  (function restoreDraft() {
    var draft;
    try { draft = sessionStorage.getItem(DRAFT_KEY); } catch (e) { return; }
    if (!draft) return;
    var tries = 0;
    (function attempt() {
      var m = document.getElementById('message');
      if (m && document.readyState === 'complete') {
        setTimeout(function () {
          try { sessionStorage.removeItem(DRAFT_KEY); } catch (e) {}
          if (!m.value) {
            m.value = draft;
            m.dispatchEvent(new Event('input', { bubbles: true }));
          }
        }, 400);
        return;
      }
      if (++tries < 60) setTimeout(attempt, 250);
      else try { sessionStorage.removeItem(DRAFT_KEY); } catch (e) {} // pantalla sin caja de mensaje: descartar
    })();
  })();

  // ── Botón Atrás: cerrar primero modales/menús de RUFFS ───────────────────────
  // RUFFS cierra modales, menús y selecciones con Escape en fase de *captura*
  // sobre document (ui.js, keyboard-shortcuts.js) y llama a preventDefault().
  // Pero Escape también es el atajo "cancelar respuesta", que escucha en fase de
  // *burbuja*. Por eso cortamos el evento justo después de la captura en <html>:
  // si nadie lo consumió, no llega al atajo y no se interrumpe la generación.
  R.back = function () {
    var root = document.documentElement;
    if (!root) return false;
    var ev;
    try {
      ev = new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true, cancelable: true });
    } catch (e) { return false; }
    var guard = function (e) { if (e === ev) e.stopImmediatePropagation(); };
    root.addEventListener('keydown', guard, true);
    try { (document.body || root).dispatchEvent(ev); }
    finally { root.removeEventListener('keydown', guard, true); }
    return ev.defaultPrevented;
  };

  R.isApp = true;
  try { document.documentElement.classList.add('ruffs-android-app'); } catch (e) {}
})();
