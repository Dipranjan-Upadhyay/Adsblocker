// AdShield: hide "Sponsored" posts on Facebook and Instagram websites.
// Best effort: Meta changes and obfuscates its page structure often.
(function () {
  'use strict';
  if (window.__adshieldSponsored) return;
  window.__adshieldSponsored = true;

  var LABELS = ['sponsored', 'gesponsert', 'sponsorisé', 'patrocinado', 'patrocinada',
                'sponsorizzato', 'gesponsord', 'sponsorlu', 'реклама', 'प्रायोजित'];

  function isLabel(text) {
    if (!text) return false;
    text = text.trim().toLowerCase();
    return text.length > 0 && text.length <= 20 && LABELS.indexOf(text) >= 0;
  }

  function hidePost(el) {
    var post = el.closest('article, [role="article"], [data-pagelet^="FeedUnit"]');
    if (!post) {
      // Climb to the first tall block that sits in a list of siblings (a feed item).
      var n = el;
      for (var i = 0; i < 25 && n.parentElement; i++) {
        var p = n.parentElement;
        if (p.childElementCount >= 3 && n.offsetHeight > 200) { post = n; break; }
        n = p;
      }
    }
    if (post && post !== document.body && post !== document.documentElement && !post.__adshield) {
      post.__adshield = true;
      post.style.setProperty('display', 'none', 'important');
    }
  }

  function scan() {
    scheduled = false;
    if (!document.body) return;
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    var node;
    while ((node = walker.nextNode())) {
      if (isLabel(node.nodeValue) && node.parentElement) hidePost(node.parentElement);
    }
    var labelled = document.querySelectorAll('[aria-label]');
    for (var i = 0; i < labelled.length; i++) {
      if (isLabel(labelled[i].getAttribute('aria-label'))) hidePost(labelled[i]);
    }
  }

  var scheduled = false;
  function schedule() {
    if (!scheduled) { scheduled = true; setTimeout(scan, 400); }
  }

  new MutationObserver(schedule).observe(document, { childList: true, subtree: true });
  schedule();
})();
