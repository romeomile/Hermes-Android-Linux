/*
 * Page-level tweaks the Android host evaluates on every onPageFinished.
 *
 * Port of MainActivity.buildHermesMobileTweaksScript() / pushSafeAreaInsetsToPage() from the
 * Hermes-mobile reference app, with two adaptations for this app:
 *
 *   1. every label the injected code matches (drawer auto-close, hidden nav buttons, the menu
 *      button's aria-label) is matched in ENGLISH — the reference matched Chinese labels, which
 *      would never match an English dashboard;
 *   2. the "return to mobile UI" fallback loads the shell again through the native
 *      returnToMobileHome() bridge method (the reference hard-coded https://localhost/, an
 *      address that only exists inside Capacitor).
 *
 * The host replaces __HERMES_SHELL_URL__ with the WebViewAssetLoader URL of www/index.html before
 * evaluating this file.
 */
(function () {
  try {
    var HERMES_SHELL_URL = "__HERMES_SHELL_URL__";

    // English is forced on every page: the dashboard SPA reads its display language from storage.
    try {
      window.localStorage.setItem("hermes.desktop.locale", "en");
      window.localStorage.setItem("hermes.mobile.locale", "en");
      window.localStorage.setItem("hermes-locale", "en");
    } catch (storageError) {
      // storage can be unavailable on a sandboxed page; the query parameter still carries English
    }
    document.documentElement.lang = "en";

    var style = document.getElementById("hermes-android-mobile-style");
    if (!style) {
      style = document.createElement("style");
      style.id = "hermes-android-mobile-style";
      document.head.appendChild(style);
    }
    style.setAttribute("data-version", "desktop-mobile-layout-2026-07-03-13");
    style.textContent = [
      "html.hermes-android-app{--hermes-safe-top:0px;--hermes-safe-right:0px;--hermes-safe-bottom:0px;--hermes-safe-left:0px;--hermes-mobile-bottom-gap:max(36px,calc(var(--hermes-safe-bottom,0px) + 18px));overscroll-behavior:none;background:#10141b;}",
      "html.hermes-android-app,html.hermes-android-app body{width:var(--hermes-app-width,100vw)!important;height:var(--hermes-app-height,100dvh)!important;max-width:100vw!important;max-height:100dvh!important;overflow:hidden!important;}",
      "html.hermes-android-app body{position:fixed!important;inset:0!important;margin:0!important;padding:var(--hermes-safe-top,0px) var(--hermes-safe-right,0px) var(--hermes-mobile-bottom-gap,10px) var(--hermes-safe-left,0px)!important;box-sizing:border-box!important;background:#10141b;-webkit-tap-highlight-color:transparent;}",
      "html.hermes-android-app #root{width:100%!important;height:calc(var(--hermes-app-height,100dvh) - var(--hermes-safe-top,0px) - var(--hermes-mobile-bottom-gap,10px))!important;max-width:100vw!important;overflow:hidden!important;}",
      "html.hermes-android-app #root>div{height:100%!important;min-height:0!important;max-height:100%!important;}",
      "html.hermes-android-app input,html.hermes-android-app textarea,html.hermes-android-app select{font-size:16px!important;}",
      "html.hermes-android-app button{touch-action:manipulation;}",
      "@media(max-width:640px){",
      "  html.hermes-android-app{font-size:14px;}",
      "  html.hermes-android-app body{padding-bottom:var(--hermes-mobile-bottom-gap)!important;}",
      "  html.hermes-android-app #root aside,html.hermes-android-app #root [data-sidebar='sidebar'],html.hermes-android-app #root [data-slot='sidebar']{display:none!important;}",
      "  html.hermes-android-app #root [data-hermes-force-sidebar='true']{display:block!important;position:relative!important;z-index:2147483600!important;width:min(84vw,340px)!important;max-width:min(84vw,340px)!important;min-width:0!important;height:100%!important;min-height:0!important;overflow:auto!important;transform:none!important;opacity:1!important;visibility:visible!important;background:var(--background,#fff)!important;}",
      "  html.hermes-android-app #root [data-hermes-mobile-sidebar-shell='true']{pointer-events:none!important;position:fixed!important;z-index:2147483600!important;top:var(--hermes-safe-top,0px)!important;bottom:var(--hermes-mobile-bottom-gap)!important;left:0!important;width:min(84vw,340px)!important;max-width:min(84vw,340px)!important;height:auto!important;min-width:0!important;overflow:visible!important;transform:translateX(calc(-100% - 16px))!important;transition:transform .18s ease-out!important;}",
      "  html.hermes-android-app.hermes-mobile-sidebar-open #root [data-hermes-mobile-sidebar-shell='true']{--tw-translate-x:0px!important;--tw-translate-y:0px!important;pointer-events:auto!important;translate:0 0!important;transform:none!important;}",
      "  html.hermes-android-app.hermes-mobile-sidebar-open #root aside,html.hermes-android-app.hermes-mobile-sidebar-open #root [data-sidebar='sidebar'],html.hermes-android-app.hermes-mobile-sidebar-open #root [data-slot='sidebar'],html.hermes-android-app.hermes-mobile-sidebar-open #root [data-hermes-force-sidebar='true']{display:block!important;position:relative!important;z-index:2147483600!important;top:auto!important;bottom:auto!important;left:auto!important;width:min(84vw,340px)!important;max-width:min(84vw,340px)!important;min-width:0!important;height:100%!important;transform:none!important;}",
      "  html.hermes-android-app.hermes-mobile-sidebar-open #root [data-hermes-mobile-sidebar-shell='true'] *,html.hermes-android-app.hermes-mobile-sidebar-open #root [data-hermes-force-sidebar='true'] *{pointer-events:auto!important;}",
      "  html.hermes-android-app #hermes-mobile-menu-button{display:grid!important;}",
      "  html.hermes-android-app #hermes-mobile-sidebar-scrim{display:none;}",
      "  html.hermes-android-app.hermes-mobile-sidebar-open #hermes-mobile-sidebar-scrim{display:block;position:fixed;top:var(--hermes-safe-top,0px);right:0;bottom:var(--hermes-mobile-bottom-gap);left:min(84vw,340px);z-index:2147483599;background:rgba(15,23,42,.34);}",
      "  html.hermes-android-app #root main{width:100%!important;max-width:100%!important;margin-left:0!important;margin-right:0!important;}",
      "  html.hermes-android-app #root [class*='w-['][class*='--sidebar'],html.hermes-android-app #root [class*='right-rail'],html.hermes-android-app #root [class*='RightRail']{display:none!important;}",
      "  html.hermes-android-app #root footer,html.hermes-android-app #root [class*='statusbar'],html.hermes-android-app #root [class*='Statusbar']{display:none!important;}",
      "  html.hermes-android-app textarea{max-height:34dvh!important;}",
      "  html.hermes-android-app [data-slot='composer-attachments']{max-height:22dvh;overflow:auto;}",
      "  html.hermes-android-app button{min-height:38px;}",
      "  html.hermes-android-app [data-slot='composer-root'] button{min-width:44px!important;min-height:44px!important;width:44px!important;height:44px!important;padding:0!important;display:inline-grid!important;place-items:center!important;border-radius:10px!important;}",
      "  html.hermes-android-app [data-slot='composer-root'] button svg,html.hermes-android-app [data-slot='composer-root'] button .codicon{width:22px!important;height:22px!important;font-size:22px!important;line-height:22px!important;}",
      "  html.hermes-android-app [role='dialog']{max-width:calc(var(--hermes-app-width,100vw) - 16px)!important;}",
      "}",
      "html.hermes-android-terminal-mode #hermes-mobile-menu-button,html.hermes-android-terminal-mode button[aria-label*='menu'],html.hermes-android-terminal-mode button[aria-label*='Menu'],html.hermes-android-terminal-mode [data-testid*='sidebar']{display:none!important;}",
      "html.hermes-android-terminal-mode body{padding-bottom:74px!important;}html.hermes-android-terminal-mode .xterm,html.hermes-android-terminal-mode .xterm-screen,html.hermes-android-terminal-mode .xterm-viewport{padding-bottom:72px!important;box-sizing:border-box!important;}",
      "#hermes-terminal-return-button{position:fixed;left:max(12px,calc(var(--hermes-safe-left,0px) + 12px));top:max(78px,calc(var(--hermes-safe-top,0px) + 46px));z-index:2147483602;border:1px solid rgba(240,230,210,.42);border-radius:12px;background:rgba(6,19,17,.92);color:#fff4d6;padding:0 14px;min-width:124px;height:48px;font:800 15px/48px system-ui,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;box-shadow:0 12px 28px rgba(0,0,0,.34);backdrop-filter:blur(16px);-webkit-backdrop-filter:blur(16px);}",
      "#hermes-terminal-shortcuts{position:fixed;left:max(8px,calc(var(--hermes-safe-left,0px) + 8px));right:max(8px,calc(var(--hermes-safe-right,0px) + 8px));bottom:max(8px,calc(var(--hermes-safe-bottom,0px) + 8px));z-index:2147483602;display:flex;gap:6px;overflow-x:auto;padding:7px;border-radius:16px;background:rgba(2,11,10,.92);box-shadow:0 16px 42px rgba(0,0,0,.42);backdrop-filter:blur(16px);-webkit-backdrop-filter:blur(16px);}",
      "#hermes-terminal-shortcuts button{flex:0 0 auto;border:1px solid rgba(240,230,210,.28);border-radius:12px;background:rgba(255,255,255,.08);color:#fff4d6;min-width:48px;height:40px;padding:0 10px;font:800 13px/40px system-ui,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;}"
    ].join("\n");

    document.documentElement.classList.add("hermes-android-app");

    var viewport = document.querySelector('meta[name="viewport"]');
    if (!viewport) {
      viewport = document.createElement("meta");
      viewport.setAttribute("name", "viewport");
      document.head.appendChild(viewport);
    }
    viewport.setAttribute("content", "width=device-width, initial-scale=1, maximum-scale=1, viewport-fit=cover, user-scalable=no");

    function isTerminalMode() {
      return /[?&]channel=android-mobile-terminal(?:&|$)/.test(location.search) || /android-mobile-terminal/.test(location.href);
    }

    function returnToMobileHome() {
      if (window.HermesAndroid && typeof window.HermesAndroid.returnToMobileHome === "function") {
        window.HermesAndroid.returnToMobileHome();
        return;
      }
      window.location.href = HERMES_SHELL_URL;
    }

    function terminalFocusTarget() {
      var target = document.querySelector(".xterm-helper-textarea") || document.querySelector("textarea") || document.activeElement || document.body;
      try {
        target.focus({ preventScroll: true });
      } catch (error) {
        try {
          target.focus();
        } catch (innerError) {
          // focus is best effort
        }
      }
      return target || document.body;
    }

    function sendTerminalKey(key, options) {
      var target = terminalFocusTarget();
      var eventInit = Object.assign({ key: key, code: key, bubbles: true, cancelable: true }, options || {});
      target.dispatchEvent(new KeyboardEvent("keydown", eventInit));
      target.dispatchEvent(new KeyboardEvent("keyup", eventInit));
    }

    function installTerminalShortcuts() {
      if (document.getElementById("hermes-terminal-shortcuts")) {
        return;
      }
      var bar = document.createElement("div");
      bar.id = "hermes-terminal-shortcuts";
      var keys = [
        ["Esc", "Escape", {}],
        ["Tab", "Tab", {}],
        ["Ctrl C", "c", { ctrlKey: true }],
        ["Ctrl D", "d", { ctrlKey: true }],
        ["Ctrl L", "l", { ctrlKey: true }],
        ["Up", "ArrowUp", {}],
        ["Down", "ArrowDown", {}],
        ["Left", "ArrowLeft", {}],
        ["Right", "ArrowRight", {}]
      ];
      keys.forEach(function (item) {
        var shortcut = document.createElement("button");
        shortcut.type = "button";
        shortcut.textContent = item[0];
        shortcut.setAttribute("aria-label", item[0]);
        shortcut.addEventListener("click", function () {
          sendTerminalKey(item[1], item[2]);
        });
        bar.appendChild(shortcut);
      });
      document.body.appendChild(bar);
    }

    function installTerminalReturnButton() {
      var terminalMode = isTerminalMode();
      document.documentElement.classList.toggle("hermes-android-terminal-mode", terminalMode);
      var existing = document.getElementById("hermes-terminal-return-button");
      var shortcuts = document.getElementById("hermes-terminal-shortcuts");
      if (!terminalMode) {
        if (existing) {
          existing.remove();
        }
        if (shortcuts) {
          shortcuts.remove();
        }
        return;
      }
      if (existing) {
        installTerminalShortcuts();
        return;
      }
      var button = document.createElement("button");
      button.id = "hermes-terminal-return-button";
      button.type = "button";
      button.textContent = "Return to mobile UI";
      button.setAttribute("aria-label", "Return to mobile UI");
      button.addEventListener("click", returnToMobileHome);
      document.body.appendChild(button);
      installTerminalShortcuts();
    }

    function setImportant(node, prop, value) {
      if (node) {
        node.style.setProperty(prop, value, "important");
      }
    }

    function clearInlineSidebarPosition(node) {
      if (!node) {
        return;
      }
      ["position", "left", "right", "top", "bottom", "width", "max-width", "min-width", "height", "min-height", "transform", "translate", "margin", "display", "visibility", "opacity", "pointer-events", "z-index", "box-shadow", "background", "overflow"].forEach(function (prop) {
        node.style.removeProperty(prop);
      });
    }

    function forceHermesMobileSidebarPosition(open) {
      var shell = document.querySelector('#root [data-hermes-mobile-sidebar-shell="true"]');
      var sidebar = document.querySelector('#root [data-slot="sidebar"]');
      if (!open) {
        clearInlineSidebarPosition(shell);
        clearInlineSidebarPosition(sidebar);
        return;
      }
      setImportant(shell, "position", "fixed");
      setImportant(shell, "left", "0px");
      setImportant(shell, "right", "auto");
      setImportant(shell, "top", "var(--hermes-safe-top,0px)");
      setImportant(shell, "bottom", "var(--hermes-mobile-bottom-gap,0px)");
      setImportant(shell, "width", "min(84vw,340px)");
      setImportant(shell, "max-width", "min(84vw,340px)");
      setImportant(shell, "min-width", "min(84vw,340px)");
      setImportant(shell, "height", "auto");
      setImportant(shell, "--tw-translate-x", "0px");
      setImportant(shell, "--tw-translate-y", "0px");
      setImportant(shell, "--tw-translate-z", "0px");
      setImportant(shell, "transform", "none");
      setImportant(shell, "translate", "0 0");
      setImportant(shell, "margin", "0");
      setImportant(shell, "display", "block");
      setImportant(shell, "visibility", "visible");
      setImportant(shell, "opacity", "1");
      setImportant(shell, "pointer-events", "auto");
      setImportant(shell, "z-index", "2147483600");
      setImportant(shell, "overflow", "visible");
      setImportant(sidebar, "position", "relative");
      setImportant(sidebar, "left", "0px");
      setImportant(sidebar, "right", "auto");
      setImportant(sidebar, "top", "0px");
      setImportant(sidebar, "bottom", "auto");
      setImportant(sidebar, "width", "100%");
      setImportant(sidebar, "max-width", "100%");
      setImportant(sidebar, "min-width", "0");
      setImportant(sidebar, "height", "100%");
      setImportant(sidebar, "min-height", "0");
      setImportant(sidebar, "transform", "none");
      setImportant(sidebar, "translate", "none");
      setImportant(sidebar, "margin", "0");
      setImportant(sidebar, "display", "block");
      setImportant(sidebar, "visibility", "visible");
      setImportant(sidebar, "opacity", "1");
      setImportant(sidebar, "pointer-events", "auto");
      setImportant(sidebar, "z-index", "2147483600");
      setImportant(sidebar, "background", "var(--background,#fff)");
      setImportant(sidebar, "box-shadow", "0 18px 48px rgba(15,23,42,.24)");
      setImportant(sidebar, "overflow", "auto");
    }

    function applyHermesMobileChromeTweaks() {
      if (window.innerWidth > 640) {
        return;
      }
      document.querySelectorAll('#root [data-hermes-mobile-sidebar-shell="true"]').forEach(function (node) {
        node.removeAttribute("data-hermes-mobile-sidebar-shell");
      });
      document.querySelectorAll('#root [data-hermes-force-sidebar="true"]').forEach(function (node) {
        node.removeAttribute("data-hermes-force-sidebar");
      });
      var desktopSidebar = document.querySelector('#root [data-slot="sidebar"]');
      if (desktopSidebar) {
        desktopSidebar.setAttribute("data-hermes-force-sidebar", "true");
        var shell = desktopSidebar.parentElement;
        for (var s = 0; s < 5 && shell; s += 1) {
          var cls = String(shell.className || "");
          if (/group\/reveal|translate-x|absolute/.test(cls)) {
            break;
          }
          shell = shell.parentElement;
        }
        if (shell) {
          shell.setAttribute("data-hermes-mobile-sidebar-shell", "true");
        }
      }
      if (!document.getElementById("hermes-mobile-menu-button")) {
        var scrim = document.createElement("button");
        scrim.id = "hermes-mobile-sidebar-scrim";
        scrim.type = "button";
        scrim.setAttribute("aria-label", "Close menu");
        scrim.addEventListener("click", function () {
          if (window.__hermesAndroidToggleSidebar) {
            window.__hermesAndroidToggleSidebar(false);
          } else {
            document.documentElement.classList.remove("hermes-mobile-sidebar-open");
          }
        });
        document.body.appendChild(scrim);
        var menuButton = document.createElement("button");
        menuButton.id = "hermes-mobile-menu-button";
        menuButton.type = "button";
        menuButton.textContent = "\u2630";
        menuButton.setAttribute("aria-label", "Open menu");
        menuButton.style.cssText = "display:none;position:fixed;left:8px;top:max(42px,calc(var(--hermes-safe-top,0px) + 6px));z-index:2147483601;width:30px;height:30px;min-height:30px!important;max-height:30px;padding:0;border:0;border-radius:7px;background:rgba(255,255,255,.42);color:#111827;font-size:18px;line-height:1;box-shadow:none;backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);";
        menuButton.addEventListener("click", function () {
          var open = !document.documentElement.classList.contains("hermes-mobile-sidebar-open");
          if (window.__hermesAndroidToggleSidebar) {
            window.__hermesAndroidToggleSidebar(open);
          } else {
            document.documentElement.classList.toggle("hermes-mobile-sidebar-open", open);
          }
        });
        document.body.appendChild(menuButton);
      }
      var activeSidebar = document.querySelector('#root [data-hermes-force-sidebar="true"]');
      if (activeSidebar && !activeSidebar.__hermesAndroidAutoCloseInstalled) {
        activeSidebar.__hermesAndroidAutoCloseInstalled = true;
        activeSidebar.addEventListener("click", function (event) {
          var target = event.target && event.target.closest ? event.target.closest('button,a,[role="button"],[data-radix-collection-item]') : null;
          if (!target) {
            return;
          }
          // Keep the drawer open for search, keyboard hints, pinned items and "session N" rows
          // (the reference matched the Chinese labels here).
          var label = (target.innerText || target.textContent || target.getAttribute("aria-label") || "").trim();
          if (/search|shift\+|pinned|session\s*\d*$/i.test(label)) {
            return;
          }
          window.setTimeout(function () {
            if (window.__hermesAndroidToggleSidebar) {
              window.__hermesAndroidToggleSidebar(false);
            }
          }, 180);
        }, true);
      }
      var buttons = Array.prototype.slice.call(document.querySelectorAll("button"));
      buttons.forEach(function (button) {
        // Hide the desktop-only bottom nav buttons (gateway / agents / cron / version chips).
        var label = (button.innerText || button.getAttribute("aria-label") || "").trim();
        if (!/(gateway|proxy|agents|schedule|cron|v0\.\d+)/i.test(label)) {
          return;
        }
        var node = button;
        for (var i = 0; i < 5 && node && node.parentElement; i += 1) {
          var rect = node.getBoundingClientRect();
          if (rect.top > window.innerHeight - 80 && rect.height <= 64) {
            node.style.display = "none";
            break;
          }
          node = node.parentElement;
        }
      });
    }

    window.__hermesAndroidToggleSidebar = function (open) {
      var doc = document.documentElement;
      var next = typeof open === "boolean" ? open : !doc.classList.contains("hermes-mobile-sidebar-open");
      doc.classList.toggle("hermes-mobile-sidebar-open", next);
      applyHermesMobileChromeTweaks();
      forceHermesMobileSidebarPosition(next);
    };

    window.__hermesAndroidSetInsets = function (top, right, bottom, left) {
      var dpr = window.devicePixelRatio || 1;
      var doc = document.documentElement;
      doc.style.setProperty("--hermes-safe-top", Math.ceil((top || 0) / dpr) + "px");
      doc.style.setProperty("--hermes-safe-right", Math.ceil((right || 0) / dpr) + "px");
      doc.style.setProperty("--hermes-safe-bottom", Math.ceil((bottom || 0) / dpr) + "px");
      doc.style.setProperty("--hermes-safe-left", Math.ceil((left || 0) / dpr) + "px");
    };

    function syncHermesViewport() {
      var doc = document.documentElement;
      var vv = window.visualViewport;
      var width = Math.max(1, Math.round((vv && vv.width) || window.innerWidth || doc.clientWidth || screen.width));
      var height = Math.max(1, Math.round((vv && vv.height) || window.innerHeight || doc.clientHeight || screen.height));
      doc.style.setProperty("--hermes-app-width", width + "px");
      doc.style.setProperty("--hermes-app-height", height + "px");
    }

    window.__hermesAndroidSyncViewport = syncHermesViewport;
    syncHermesViewport();
    applyHermesMobileChromeTweaks();
    installTerminalReturnButton();

    if (!window.__hermesAndroidViewportInstalled) {
      window.__hermesAndroidViewportInstalled = true;
      window.addEventListener("resize", syncHermesViewport, { passive: true });
      window.addEventListener("orientationchange", function () {
        window.setTimeout(syncHermesViewport, 120);
      }, { passive: true });
      new MutationObserver(function () {
        window.requestAnimationFrame(function () {
          applyHermesMobileChromeTweaks();
          forceHermesMobileSidebarPosition(document.documentElement.classList.contains("hermes-mobile-sidebar-open"));
        });
      }).observe(document.documentElement, { childList: true, subtree: true });
      if (window.visualViewport) {
        window.visualViewport.addEventListener("resize", syncHermesViewport, { passive: true });
        window.visualViewport.addEventListener("scroll", syncHermesViewport, { passive: true });
      }
    }
  } catch (error) {
    console.warn("Hermes Android mobile tweaks failed", error);
  }
})();
