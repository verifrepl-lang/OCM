/*
 * Injected by the Android shell into the OpenCode web UI. It adds the few things a phone needs that a desktop browser
 * gets for free: saving files to the Download folder, matching the system bars to the theme, telling the app when the
 * terminal has focus (so the extra keys row appears) and a clipboard fallback. It does nothing outside the app.
 */
(function () {
  "use strict";

  var native = window.OpenCodeAndroid;

  if (!native || window.__ocAndroidBridge) return;

  window.__ocAndroidBridge = true;

  // File downloads
  var blobs = new Map();
  var createObjectURL = URL.createObjectURL;
  var revokeObjectURL = URL.revokeObjectURL;

  URL.createObjectURL = function (object) {
    var url = createObjectURL.call(URL, object);

    try {
      if (object instanceof Blob) {
        // A page that never revokes its URLs would otherwise pin every blob until the renderer dies (a renderer
        // kill mid-chat reads to the person as "the agent thought forever, then an error").
        if (blobs.size > 24) blobs.delete(blobs.keys().next().value);

        blobs.set(url, object);
      }
    } catch (e) {}

    return url;
  };

  // The save may still be reading the blob when the page revokes its URL, so forget it a little later.
  URL.revokeObjectURL = function (url) {
    setTimeout(function () {
      blobs.delete(url);
    }, 60000);

    return revokeObjectURL.call(URL, url);
  };

  var CHUNK = 192 * 1024; // a multiple of 3, so every chunk encodes to base64 without padding

  function base64(bytes) {
    var out = "";

    for (var i = 0; i < bytes.length; i += 0x8000) {
      out += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    }

    return btoa(out);
  }

  function fileName(anchor, url) {
    var given = anchor && anchor.getAttribute && anchor.getAttribute("download");

    if (given) return given;

    try {
      var last = new URL(url).pathname.split("/").filter(Boolean).pop();

      if (last) return decodeURIComponent(last);
    } catch (e) {}

    return "download";
  }

  async function saveBlob(blob, name) {
    var id = native.saveBegin(name, blob.type || "application/octet-stream");

    if (!id) return false;

    try {
      for (var offset = 0; offset < blob.size; offset += CHUNK) {
        var buffer = await blob.slice(offset, offset + CHUNK).arrayBuffer();

        if (!native.saveChunk(id, base64(new Uint8Array(buffer)))) {
          native.saveAbort(id);

          return false;
        }
      }

      native.saveEnd(id);

      return true;
    } catch (e) {
      native.saveAbort(id);

      return false;
    }
  }

  function saveUrl(url, name) {
    var known = blobs.get(url);
    var source = known ? Promise.resolve(known) : fetch(url).then(function (response) { return response.blob(); });

    return source.then(function (blob) {
      return saveBlob(blob, name);
    });
  }

  // Returns true when the anchor was a blob or data download that the app took over.
  function takeOver(anchor) {
    var href = anchor && anchor.href;

    if (typeof href !== "string") return false;
    if (href.indexOf("blob:") !== 0 && href.indexOf("data:") !== 0) return false;

    saveUrl(href, fileName(anchor, href));

    return true;
  }

  var anchorClick = HTMLAnchorElement.prototype.click;

  HTMLAnchorElement.prototype.click = function () {
    if (takeOver(this)) return;

    return anchorClick.apply(this, arguments);
  };

  var anchorDispatch = HTMLAnchorElement.prototype.dispatchEvent;

  HTMLAnchorElement.prototype.dispatchEvent = function (event) {
    if (event && event.type === "click" && takeOver(this)) return true;

    return anchorDispatch.call(this, event);
  };

  document.addEventListener(
    "click",
    function (event) {
      var target = event.target;
      var anchor = target && target.closest ? target.closest("a[href]") : null;

      if (anchor && takeOver(anchor)) {
        event.preventDefault();
        event.stopPropagation();
      }
    },
    true,
  );

  window.__ocSaveUrl = saveUrl;

  // System bars follow the theme
  function parseColor(value) {
    var match = /rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)(?:[,\s/]+([\d.]+))?/.exec(value || "");

    if (!match) return null;

    var alpha = match[4] === undefined ? 1 : parseFloat(match[4]);

    if (alpha < 0.5) return null;

    return [parseInt(match[1], 10), parseInt(match[2], 10), parseInt(match[3], 10)];
  }

  function pageColor() {
    var candidates = [document.querySelector("#root"), document.body, document.documentElement];

    for (var i = 0; i < candidates.length; i++) {
      if (!candidates[i]) continue;

      var color = parseColor(getComputedStyle(candidates[i]).backgroundColor);

      if (color) return color;
    }

    return null;
  }

  var lastBars = "";

  function syncBars() {
    var color = pageColor();

    if (!color) return;

    var hex = "#" + color.map(function (v) { return ("0" + v.toString(16)).slice(-2); }).join("");
    var luminance = (0.299 * color[0] + 0.587 * color[1] + 0.114 * color[2]) / 255;
    var key = hex + (luminance < 0.5);

    if (key === lastBars) return;

    lastBars = key;
    native.setBars(luminance < 0.5, hex);
  }

  var barTimer = 0;

  function scheduleBars() {
    clearTimeout(barTimer);
    barTimer = setTimeout(syncBars, 120);
  }

  new MutationObserver(scheduleBars).observe(document.documentElement, { attributes: true, attributeFilter: ["class", "style", "data-theme", "data-color-scheme"] });
  window.addEventListener("load", scheduleBars);
  document.addEventListener("DOMContentLoaded", scheduleBars);

  if (window.matchMedia) {
    var scheme = window.matchMedia("(prefers-color-scheme: dark)");

    if (scheme.addEventListener) scheme.addEventListener("change", scheduleBars);
  }

  setInterval(syncBars, 2000);
  scheduleBars();

  // Terminal focus
  var terminalFocused = false;

  function inTerminal(element) {
    if (!element || !element.closest) return false;

    var typing = element.tagName === "TEXTAREA" || element.tagName === "INPUT" || element.isContentEditable;

    return typing && !!element.closest('[data-component="terminal-panel"]');
  }

  function reportFocus() {
    var now = inTerminal(document.activeElement);

    if (now !== terminalFocused) {
      terminalFocused = now;
      native.terminalFocus(now);
    }
  }

  document.addEventListener("focusin", reportFocus, true);
  document.addEventListener("focusout", function () { setTimeout(reportFocus, 50); }, true);

  // Clipboard fallback
  if (navigator.clipboard && navigator.clipboard.writeText) {
    var writeText = navigator.clipboard.writeText.bind(navigator.clipboard);

    navigator.clipboard.writeText = function (text) {
      return writeText(text).catch(function () {
        native.copyText(String(text));
      });
    };
  }

  // Composer layout on phones: a long model name used to push the model-variant (thinking) control out of the
  // scrollable controls row, so it was clipped exactly where the send button sits. Cap the wide triggers instead.
  var composerStyle = document.createElement("style");

  composerStyle.textContent =
    '@media (max-width: 767px) { [data-slot="composer-controls"] [class*="max-w-[220px]"] { max-width: 148px !important; } }';

  (document.head || document.documentElement).appendChild(composerStyle);
  // Limit and rate-limit answers come from the provider and are shown exactly as received: this script never
  // rewrites an error. Under the error block it only adds a short hint (data-oc-hint) that names the real cause
  // and offers the app's own sign-in. The scan skips the hint itself, so the hint can never feed back into it.
  // The wording mirrors what OpenCode Zen actually sends (verified live, October 2026): "FreeUsageLimitError" on
  // the world-shared anonymous bucket, "RegionError - not available in your country", plain rate limits, and the
  // personal-account limits.
  var QUOTA = /(rate[\s_-]?limit(?:ed)?|too\s+many\s+requests|(?:status|code|http)\s*:?\s*429\b|insufficient[_ ]?quota|quota\s*(?:exceeded|exhausted|has been exceeded|will run out|is exhausted)|exceeded\s+your\s+current\s+quota|exceeded\s*(?:your|the|its)?\s*(?:usage|quota|free)|plan\s+and\s+billing|(?:free|usage|request)\s*(?:quota|limit)|usage[\s_-]?limit|free[\s_-]?(?:usage|tier)|freeusagelimit|gousagelimit|creditlimit|(?:credit|credits?|tokens?)\s*(?:exhausted|used up)|(?:^|[^\u0400-\u04ff])\u043a\u0432\u043e\u0442[\u0400-\u04ff]*\s*(?:\u0438\u0441\u0447\u0435\u0440\u043f\u0430\u043d[\u0400-\u04ff]*|\u043f\u0440\u0435\u0432\u044b\u0448\u0435\u043d[\u0400-\u04ff]*|\u0437\u0430\u043a\u043e\u043d\u0447\u0438\u043b[\u0400-\u04ff]*)|(?:^|[^\u0400-\u04ff])\u043b\u0438\u043c\u0438\u0442[\u0400-\u04ff]*\s*(?:\u0438\u0441\u0447\u0435\u0440\u043f\u0430\u043d[\u0400-\u04ff]*|\u043f\u0440\u0435\u0432\u044b\u0448\u0435\u043d[\u0400-\u04ff]*|\u0437\u0430\u043a\u043e\u043d\u0447\u0438\u043b[\u0400-\u04ff]*)|(?:\u0438\u0441\u0447\u0435\u0440\u043f\u0430\u043d[\u0400-\u04ff]*|\u0437\u0430\u043a\u043e\u043d\u0447\u0438\u043b[\u0400-\u04ff]*)\s*(?:\u043b\u0438\u043c\u0438\u0442[\u0400-\u04ff]*|\u043a\u0432\u043e\u0442[\u0400-\u04ff]*))/i;
  var REGION = /(regionerror|not\s+available\s+in\s+your\s+country|\u043d\u0435\u0434\u043e\u0441\u0442\u0443\u043f\u043d\u0430\s+\u0432\s+\u0432\u0430\u0448\u0435\u0439\s+\u0441\u0442\u0440\u0430\u043d\u0435)/i;
  var quotaHandled = new WeakSet();

  // A spent shared per-IP bucket is what this app's WARP tunnel is for: rotating the tunnel gives the next request
  // a fresh Cloudflare egress address. Fired at most once per cooldown so a page full of identical errors cannot
  // hammer the re-registration.
  var lastRotate = 0;

  function maybeRotateWarp() {
    var now = Date.now();

    if (now - lastRotate < 180000) return;

    lastRotate = now;

    try {
      if (typeof native !== "undefined" && native.rotateWarp) native.rotateWarp();
    } catch (e) {}
  }

  // The sign-in offer only makes sense when this profile has no Console account yet; the native side knows the
  // state and the page cannot see it. Older app builds without the method read as "no account".
  function hasAccount() {
    try {
      return !!(native.hasAccount && native.hasAccount());
    } catch (e) {
      return false;
    }
  }

  // `personal` once the profile runs its own Console account, `anonymous` while its requests fall into the
  // world-shared free bucket. Older app builds without the method read as "anonymous".
  function guestState() {
    try {
      return (native.guestState && native.guestState()) || "anonymous";
    } catch (e) {
      return "anonymous";
    }
  }

  // The email the active profile is signed in with, as the native side knows it. Empty when there is none (or the
  // app build predates the method), so every caller must treat it as decorative, never as a gate.
  function accountLabel() {
    try {
      return String((native.accountLabel && native.accountLabel()) || "");
    } catch (e) {
      return "";
    }
  }

  function makeSignInButton(isRussian) {
    var button = document.createElement("button");

    button.type = "button";
    button.setAttribute("data-oc-signin", "1");
    button.textContent = isRussian ? "\u041f\u043e\u0434\u043a\u043b\u044e\u0447\u0438\u0442\u044c \u0430\u043a\u043a\u0430\u0443\u043d\u0442 \u0438\u043b\u0438 \u043a\u043b\u044e\u0447" : "Connect account or key";
    button.style.cssText =
      "display:inline-block;margin:10px 0 2px;padding:10px 22px;border:0;border-radius:12px;" +
      "background:#161616;color:#fafafa;font-size:14px;font-weight:600;font-family:inherit;cursor:pointer";

    button.addEventListener(
      "click",
      function (event) {
        event.preventDefault();
        event.stopPropagation();

        try {
          native.openLogin();
        } catch (e) {}
      },
      true,
    );

    return button;
  }

  // Only error surfaces qualify: a text mentioning a limit in a terminal line, a command echo or the chat history is
  // none of our business, and rewriting it would corrupt what the person sees.
  function inErrorArea(node) {
    var host = node.parentElement;

    if (!host || !host.closest) return false;

    if (host.closest("[data-oc-hint]")) return false;

    if (host.closest('[data-component="terminal-panel"], pre, code, [class*="terminal"]')) return false;

    return !!host.closest('[role="alert"], [class*="error"], [class*="Error"], [data-error], [class*="danger"]');
  }

  // The hint names the real cause. Verified against the live gateway (October 2026): the anonymous free capacity
  // is ONE bucket shared by every OpenCode user in the world - not per IP, not per install - so without an account
  // the honest answer is "the shared bucket is spent; a personal account is the only way to a quota of your own".
  function makeHint(errorText) {
    var isRussian = (document.documentElement.lang || navigator.language || "").toLowerCase().indexOf("ru") === 0;
    var text = errorText || "";
    var personal = hasAccount() || guestState() === "personal";
    var box = document.createElement("div");
    var line = document.createElement("div");
    var body;

    if (REGION.test(text)) {
      body = isRussian
        ? "\u041c\u043e\u0434\u0435\u043b\u044c \u043d\u0435 \u043e\u0431\u0441\u043b\u0443\u0436\u0438\u0432\u0430\u0435\u0442\u0441\u044f \u0432 \u0432\u0430\u0448\u0435\u0439 \u0441\u0442\u0440\u0430\u043d\u0435 - \u044d\u0442\u043e \u043e\u0433\u0440\u0430\u043d\u0438\u0447\u0435\u043d\u0438\u0435 \u0441\u0435\u0440\u0432\u0435\u0440\u0430, \u0430\u043a\u043a\u0430\u0443\u043d\u0442 \u0435\u0433\u043e \u043d\u0435 \u0441\u043d\u0438\u043c\u0430\u0435\u0442. \u0412\u044b\u0431\u0435\u0440\u0438\u0442\u0435 \u0434\u0440\u0443\u0433\u0443\u044e \u043c\u043e\u0434\u0435\u043b\u044c."
        : "The model is not served in your country - a server-side restriction no account removes. Pick another model.";
    } else if (/freeusagelimit|free\s+usage|free[\s_-]?(?:usage|tier)|insufficient[_ ]?quota|quota\s*(?:exceeded|exhausted|is exhausted|has been exceeded)|usage[\s_-]?limit|(?:credit|credits?|tokens?)\s*(?:exhausted|used up)/i.test(text)) {
      // The same quota answer means different things for the two guest states: the world-shared bucket for an
      // anonymous profile, the personal allowance for one that runs its own account.
      body = personal
        ? (isRussian
            ? "\u042d\u0442\u043e \u043b\u0438\u043c\u0438\u0442 \u0432\u0430\u0448\u0435\u0433\u043e \u043b\u0438\u0447\u043d\u043e\u0433\u043e \u0430\u043a\u043a\u0430\u0443\u043d\u0442\u0430, \u0430 \u043d\u0435 \u043e\u0431\u0449\u0438\u0439. \u041f\u043e\u0434\u043e\u0436\u0434\u0438\u0442\u0435 \u0441\u0431\u0440\u043e\u0441\u0430, \u0432\u044b\u0431\u0435\u0440\u0438\u0442\u0435 \u0434\u0440\u0443\u0433\u0443\u044e \u043c\u043e\u0434\u0435\u043b\u044c \u0438\u043b\u0438 \u043f\u043e\u043f\u043e\u043b\u043d\u0438\u0442\u0435 \u043f\u043b\u0430\u043d \u0432 OpenCode."
            : "This is your personal account's limit, not the shared one. Wait for the reset, pick another model, or top up your OpenCode plan.")
        : (isRussian
            ? "\u0411\u0435\u0437 \u0441\u0432\u043e\u0435\u0433\u043e \u043a\u043b\u044e\u0447\u0430 \u0431\u0435\u0441\u043f\u043b\u0430\u0442\u043d\u044b\u0435 \u043c\u043e\u0434\u0435\u043b\u0438 - \u043e\u0431\u0449\u0438\u0439 \u043b\u0438\u043c\u0438\u0442 \u043d\u0430 \u0432\u0441\u0435\u0445 \u043f\u043e\u043b\u044c\u0437\u043e\u0432\u0430\u0442\u0435\u043b\u0435\u0439 OpenCode \u0432 \u043c\u0438\u0440\u0435, \u0438 \u043e\u043d \u043e\u0431\u044b\u0447\u043d\u043e \u0443\u0436\u0435 \u0438\u0441\u0447\u0435\u0440\u043f\u0430\u043d. \u0421\u0432\u043e\u0439 \u043b\u0438\u043c\u0438\u0442 \u0434\u0430\u0451\u0442 \u043b\u0438\u0447\u043d\u044b\u0439 \u0430\u043a\u043a\u0430\u0443\u043d\u0442 \u0438\u043b\u0438 \u043f\u0435\u0440\u0441\u043e\u043d\u0430\u043b\u044c\u043d\u044b\u0439 API-\u043a\u043b\u044e\u0447: \u043f\u043e\u0434\u043a\u043b\u044e\u0447\u0438\u0442\u0435 \u043b\u044e\u0431\u043e\u0439 \u043a\u043d\u043e\u043f\u043a\u043e\u0439 \u043d\u0438\u0436\u0435. \u0412\u0445\u043e\u0434 \u0447\u0435\u0440\u0435\u0437 Google \u043d\u0430 \u0441\u0430\u043c\u043e\u043c \u0441\u0430\u0439\u0442\u0435 \u043d\u0435 \u043f\u043e\u0434\u043a\u043b\u044e\u0447\u0430\u0435\u0442 \u0430\u043a\u043a\u0430\u0443\u043d\u0442 \u043a \u043f\u0440\u0438\u043b\u043e\u0436\u0435\u043d\u0438\u044e - \u043d\u0430\u0436\u043c\u0438\u0442\u0435 \u043a\u043d\u043e\u043f\u043a\u0443 \u043d\u0438\u0436\u0435 \u0438 \u0432\u043e\u0439\u0434\u0438\u0442\u0435 \u0437\u0434\u0435\u0441\u044c."
            : "Without a key of your own the free models share one limit across all OpenCode users worldwide, and it is usually spent already. A personal account or a personal API key brings a limit of your own: connect either with the button below. Signing in with Google on the website does not connect the account to the app - tap the button below and sign in here.");
    } else {
      body = isRussian
        ? "\u0421\u043b\u0438\u0448\u043a\u043e\u043c \u043c\u043d\u043e\u0433\u043e \u0437\u0430\u043f\u0440\u043e\u0441\u043e\u0432 \u043f\u043e\u0434\u0440\u044f\u0434. \u041f\u043e\u0434\u043e\u0436\u0434\u0438\u0442\u0435 \u043d\u0435\u043c\u043d\u043e\u0433\u043e \u0438 \u043f\u043e\u043f\u0440\u043e\u0431\u0443\u0439\u0442\u0435 \u0441\u043d\u043e\u0432\u0430, \u043b\u0438\u0431\u043e \u0432\u044b\u0431\u0435\u0440\u0438\u0442\u0435 \u0434\u0440\u0443\u0433\u0443\u044e \u043c\u043e\u0434\u0435\u043b\u044c."
        : "Too many requests in a row. Wait a little and try again, or pick another model.";
    }

    box.setAttribute("data-oc-hint", "1");
    box.style.cssText =
      "margin:8px 0 2px;padding:10px 12px;border-radius:12px;background:rgba(127,127,127,.14);" +
      "font-size:13px;line-height:1.4";
    line.textContent = body;
    box.appendChild(line);

    // A profile on its own account has nothing to connect: offering the sign-in again would only confuse.
    if (!personal) box.appendChild(makeSignInButton(isRussian));

    return box;
  }

  function offerSignIn(node) {
    var text = node.nodeValue || "";

    if (!text || text.length > 400 || !QUOTA.test(text)) return;
    if (!inErrorArea(node)) return;

    var parent = node.parentElement;

    if (!parent || quotaHandled.has(parent)) return;

    quotaHandled.add(parent);

    maybeRotateWarp();

    // One hint per error block, and never a second one after a re-render that left ours in place.
    var next = parent.nextElementSibling;

    if (next && next.hasAttribute("data-oc-hint")) return;

    // Inserted next to the error block, never inside it: React owns the children and would drop the hint on the
    // next render. The original text stays untouched. The error text itself decides which hint applies.
    try {
      parent.insertAdjacentElement("afterend", makeHint(parent.textContent || ""));
    } catch (e) {}
  }

  // A hint outlives its error when the retry succeeds or the chat moves on; drop it as soon as the message above
  // it no longer reports a limit, and let that message earn a new hint if the limit comes back.
  function pruneHints() {
    try {
      var hints = document.querySelectorAll("[data-oc-hint]");

      for (var i = 0; i < hints.length; i++) {
        var prev = hints[i].previousElementSibling;

        if (prev && QUOTA.test(prev.textContent || "")) continue;

        if (prev) quotaHandled.delete(prev);

        hints[i].remove();
      }
    } catch (e) {}
  }

  function scanQuota(root, guard) {
    if (!root) return;

    try {
      var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
      var current = walker.currentNode;
      var left = guard || 8000;

      while (current && left > 0) {
        left--;
        offerSignIn(current);
        current = walker.nextNode();
      }
    } catch (e) {}
  }

  // The page itself never says which account (if any) the server spends quota from, and the mistake of linking
  // Google only on the website looks exactly like a working sign-in until a limit error explains otherwise. Show
  // the connected account once per page load, for a few seconds, with the email the app knows.
  function showAccountChip() {
    if (document.querySelector("[data-oc-account]")) return;

    var label = accountLabel();

    if (!label) return;

    var isRussian = (document.documentElement.lang || navigator.language || "").toLowerCase().indexOf("ru") === 0;
    var chip = document.createElement("div");

    chip.setAttribute("data-oc-account", "1");
    chip.style.cssText =
      "position:fixed;top:12px;left:50%;transform:translateX(-50%);z-index:2147483000;max-width:78%;" +
      "padding:8px 14px;border-radius:12px;background:rgba(22,22,22,.92);color:#fafafa;font-size:12.5px;" +
      "font-family:inherit;box-shadow:0 2px 10px rgba(0,0,0,.25);opacity:0;transition:opacity .4s;" +
      "white-space:nowrap;overflow:hidden;text-overflow:ellipsis;cursor:pointer";
    chip.textContent = isRussian ? "\u041e\u0442\u043a\u0440\u044b\u0442 \u0430\u043a\u043a\u0430\u0443\u043d\u0442: " + label : "Signed in as: " + label;
    chip.addEventListener(
      "click",
      function () {
        chip.remove();
      },
      true,
    );

    (document.body || document.documentElement).appendChild(chip);
    requestAnimationFrame(function () {
      chip.style.opacity = "1";
    });

    setTimeout(function () {
      chip.style.opacity = "0";

      setTimeout(function () {
        chip.remove();
      }, 450);
    }, 8000);
  }

  setTimeout(showAccountChip, 1200);

  // Nodes touched by mutations: scanned directly, so an error far down a long chat is seen at once instead of
  // being cut off by the full-scan guard.
  var touched = new Set();

  var quotaTimer = 0;
  var quotaQueuedAt = 0;

  function scheduleQuota() {
    if (!quotaQueuedAt) quotaQueuedAt = Date.now();

    clearTimeout(quotaTimer);

    // Debounce the full scan, but never starve it: a streaming reply mutates constantly, and a plain debounce
    // would postpone the scan until the stream ends. Cap the wait at one second.
    var wait = Date.now() - quotaQueuedAt >= 1000 ? 0 : 300;

    quotaTimer = setTimeout(function () {
      quotaQueuedAt = 0;

      pruneHints();

      var nodes = touched;

      touched = new Set();
      nodes.forEach(function (node) {
        scanQuota(node, 1200);
      });
      scanQuota(document.body, 8000);
    }, wait);
  }

  new MutationObserver(function (mutations) {
    for (var i = 0; i < mutations.length && i < 200; i++) {
      var mutation = mutations[i];

      if (mutation.target) touched.add(mutation.target);

      for (var j = 0; j < mutation.addedNodes.length; j++) touched.add(mutation.addedNodes[j]);
    }

    scheduleQuota();
  }).observe(document.body || document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true,
  });
  scheduleQuota();

})();
