// AdShield: YouTube ad removal (same idea as Brave / uBlock Origin "json-prune").
// Runs before YouTube's own scripts. Removes the ad schedule from the player data
// so video ads are never requested; a fallback skips any ad that still appears.
(function () {
  'use strict';
  if (window.__adshieldYT) return;
  window.__adshieldYT = true;

  var AD_KEYS = ['adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams'];

  function prune(o) {
    try {
      if (!o || typeof o !== 'object') return o;
      if (Array.isArray(o)) { o.forEach(prune); return o; }
      for (var i = 0; i < AD_KEYS.length; i++) if (AD_KEYS[i] in o) delete o[AD_KEYS[i]];
      if (o.playerResponse) prune(o.playerResponse);
    } catch (e) {}
    return o;
  }

  function disguise(fn, name) {
    try { fn.toString = function () { return 'function ' + name + '() { [native code] }'; }; } catch (e) {}
    return fn;
  }

  // 1. Every JSON YouTube parses (initial page data and /youtubei/v1/player responses).
  var origParse = JSON.parse;
  JSON.parse = disguise(function () { return prune(origParse.apply(this, arguments)); }, 'parse');

  var origJson = Response.prototype.json;
  Response.prototype.json = disguise(function () {
    return origJson.apply(this, arguments).then(prune);
  }, 'json');

  // 2. Player data embedded directly in the page.
  ['ytInitialPlayerResponse', 'playerResponse'].forEach(function (name) {
    var value = window[name];
    try {
      Object.defineProperty(window, name, {
        configurable: true, enumerable: true,
        get: function () { return value; },
        set: function (v) { value = prune(v); }
      });
    } catch (e) {}
  });

  // 3. Fallback: if an ad still plays, mute it, jump to its end and press Skip.
  var mutedByUs = false;
  setInterval(function () {
    var player = document.querySelector('.ad-showing, .ad-interrupting');
    var video = document.querySelector('video');
    if (player && video) {
      if (!video.muted) { video.muted = true; mutedByUs = true; }
      if (isFinite(video.duration) && video.duration > 0) video.currentTime = video.duration;
      var skip = document.querySelector(
        '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-container button');
      if (skip) skip.click();
    } else if (mutedByUs && video) {
      video.muted = false;
      mutedByUs = false;
    }
    var close = document.querySelector('.ytp-ad-overlay-close-button');
    if (close) close.click();
  }, 300);
})();
