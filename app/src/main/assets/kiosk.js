/*
 * window.kiosk: the page-side API of the ED22 Kiosk app.
 * Injected only into https://ed22.org and https://*.ed22.org pages.
 */
(function () {
  'use strict';
  if (window.kiosk || !window.KioskNative) return;

  var nativeObject = window.KioskNative;
  var nextId = 1;
  var pending = {};
  var listeners = {};
  var sensorCallbacks = {};

  nativeObject.onmessage = function (event) {
    var msg;
    try { msg = JSON.parse(event.data); } catch (e) { return; }

    if (msg.id != null && pending[msg.id]) {
      var p = pending[msg.id];
      delete pending[msg.id];
      if (msg.error) p.reject(new Error(msg.error)); else p.resolve(msg.result);
      return;
    }
    if (!msg.event) return;
    if (msg.event === 'sensor') {
      var cb = sensorCallbacks[msg.data.watchId];
      if (cb) { try { cb(msg.data); } catch (e) { console.error(e); } }
      return;
    }
    (listeners[msg.event] || []).slice().forEach(function (fn) {
      try { fn(msg.data); } catch (e) { console.error(e); }
    });
    // Legacy window.Android NFC callback: nfcScanResult(url, or the tag id when there is no URL).
    if (msg.event === 'nfc' && typeof window.nfcScanResult === 'function') {
      try { window.nfcScanResult(msg.data.url || msg.data.id); } catch (e) { console.error(e); }
    }
  };

  function call(method, args) {
    return new Promise(function (resolve, reject) {
      var id = nextId++;
      pending[id] = { resolve: resolve, reject: reject };
      nativeObject.postMessage(JSON.stringify({ id: id, method: method, args: args || {} }));
    });
  }

  function method(name) {
    return function (args) { return call(name, args); };
  }

  var kiosk = {
    isKiosk: true,
    version: null,
    deviceId: null,

    /** Low-level access to any native method: kiosk.call('getBattery'). */
    call: call,

    /** Subscribe to pushed events: 'battery', 'network', 'nfc', 'speech', 'focus', 'screen'. */
    on: function (event, fn) { (listeners[event] = listeners[event] || []).push(fn); },
    off: function (event, fn) {
      var list = listeners[event];
      if (!list) return;
      var i = list.indexOf(fn);
      if (i >= 0) list.splice(i, 1);
    },

    // System information
    getBattery: method('getBattery'),
    getNetwork: method('getNetwork'),
    getWifi: method('getWifi'),
    getDevice: method('getDevice'),
    getStorage: method('getStorage'),
    getMemory: method('getMemory'),
    getLocation: function (options) { return call('getLocation', options); },
    getScreen: method('getScreen'),
    isInFocus: method('isInFocus'),

    // NFC tag reading (scans arrive as 'nfc' events while enabled)
    nfc: {
      status: method('nfcStatus'),
      enable: method('nfcEnable'),
      disable: method('nfcDisable')
    },

    // Native key/value storage that survives clearing web data (string values)
    storage: {
      get: function (key) { return call('storageGet', { key: String(key) }); },
      set: function (key, value) { return call('storageSet', { key: String(key), value: String(value) }); },
      remove: function (key) { return call('storageRemove', { key: String(key) }); },
      keys: method('storageKeys')
    },

    // Permissions: 'camera', 'microphone', 'location', 'phone', 'activity'
    permissions: {
      query: method('getPermissions'),
      request: function (names) { return call('requestPermissions', { permissions: names }); }
    },

    // Sensors
    sensors: {
      list: method('sensorList'),
      watch: function (type, callback, options) {
        var rateMs = (options && options.rateMs) || 100;
        return call('sensorWatch', { type: type, rateMs: rateMs }).then(function (id) {
          sensorCallbacks[id] = callback;
          return id;
        });
      },
      clear: function (watchId) {
        delete sensorCallbacks[watchId];
        return call('sensorClear', { watchId: watchId });
      }
    },

    // Device controls
    vibrate: function (msOrPattern) {
      return call('vibrate', Array.isArray(msOrPattern) ? { pattern: msOrPattern } : { ms: msOrPattern || 200 });
    },
    setBrightness: function (level) { return call('setBrightness', { level: level }); },
    getVolume: method('getVolume'),
    setVolume: function (level, stream) { return call('setVolume', { level: level, stream: stream || 'music' }); },
    setKeepScreenOn: function (on) { return call('setKeepScreenOn', { on: on !== false }); },
    setTorch: function (on) { return call('setTorch', { on: on !== false }); },
    setOrientation: function (orientation) { return call('setOrientation', { orientation: orientation }); },
    toast: function (text) { return call('toast', { text: String(text) }); },
    speak: function (text) { return call('speak', { text: String(text) }); },
    stopSpeaking: method('stopSpeaking'),
    cancelSpeak: function (id) { return call('cancelSpeak', { id: String(id) }); },
    reload: method('reload'),
    goHome: method('goHome'),
    reboot: method('reboot')
  };

  kiosk.ready = call('hello').then(function (info) {
    kiosk.version = info.version;
    kiosk.deviceId = info.deviceId;
    window.dispatchEvent(new CustomEvent('kioskready', { detail: info }));
    return kiosk;
  });

  Object.defineProperty(window, 'kiosk', { value: kiosk, writable: false, configurable: false });

  // Pages written for the legacy bridge only define nfcScanResult; turn NFC on for them.
  window.addEventListener('load', function () {
    if (typeof window.nfcScanResult === 'function') kiosk.nfc.enable();
  });
})();
