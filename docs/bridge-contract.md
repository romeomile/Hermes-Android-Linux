# Hermes-mobile (v0.5.0) — native bridge + shell contract

Specification extracted read-only from the `HermesMobile_merged_v0.5.0` tree. Every claim below is
grounded in those files; line numbers refer to them.

Sources of truth:

| File | Size | Role |
|---|---|---|
| `www/index.html` | 3,183 B | splash page; `<html lang="zh">`; loads one script |
| `www/hermes-mobile-bridge.js` | 219,045 B / 4,036 lines | the entire mobile shell (UI + logic), plain ES5-ish JS, no build step |
| `android/app/src/main/java/com/romirmile/hermesmobile/MainActivity.java` | 1,804 lines | WebView host, `@JavascriptInterface` bridge, injected CSS/JS |
| `android/app/src/main/java/com/romirmile/hermesmobile/AndroidAdbManager.java` | — | bundled ADB client used by 4 bridge methods |
| `android/app/src/main/python/hermes_mobile_backend.py` | 232 lines | Chaquopy entry points the bridge calls into |
| `capacitor.config.ts` | 617 B | Capacitor host config |
| `android/app/src/main/assets/public/**` | — | byte-identical copy of `www/` (verified: `diff -rq` clean) |

Two-layer architecture (the single most important structural fact):

1. **Shell layer** — `https://localhost/` = `www/index.html` + `hermes-mobile-bridge.js`. The shell
   builds its **own complete mobile UI in JS** (drawer, chat, settings, ADB page, …). It does **not**
   auto-navigate to the dashboard SPA.
2. **Dashboard layer** — the Hermes web dashboard SPA served from `http://127.0.0.1:9129`. The shell
   navigates into it **only on demand** (models page, per-feature "open in dashboard" links, terminal
   mode). The bridge script is not loaded there; `MainActivity` injects its CSS/JS hooks into every
   page, including dashboard pages.

Correction to the brief: the shell does not boot into `/chat?...`; see §3. Also, the bridge exposes
**24** `@JavascriptInterface` methods, not 26 (24 annotations — verified by direct count).

---

## 1. Bridge method catalog

Registration (MainActivity.java:326): `webView.addJavascriptInterface(new HermesAndroidBridge(), "HermesAndroid")`.
`HermesAndroidBridge` is a **non-static inner class** of `MainActivity`; the constant
`EMBEDDED_AGENT_PORT = 9129` (line 64). All methods are called **synchronously** from the page's JS
thread; blocking ones park on a `CountDownLatch` with the timeout passed from JS.

Legend: *JS caller* = where `hermes-mobile-bridge.js` invokes it. "JS wrapper only" = a JS helper
exists but nothing in the 0.5.0 shell calls it (dead code in this build).

| # | Method (line) | Signature | Returns | Native behaviour | JS caller |
|---|---|---|---|---|---|
| 1 | `getAndroidAdbStatus` (735) | `()` | `String` JSON | `AndroidAdbManager.status()`: spawns bundled `libadb_exec.so devices -l`, parses serial/state | Phone-control page, adb connect/test flows |
| 2 | `pairAndroidAdb` (740) | `(String host, int port, String pairingCode)` | `String` JSON | `adb pair host:port` with the 6-digit code written to stdin | Phone-control "pair" button |
| 3 | `connectAndroidAdb` (745) | `(String host, int port)` | `String` JSON | `adb connect host:port`; sets `connected` from output | Phone-control "connect" button |
| 4 | `runAndroidAdbShell` (750) | `(String serial, String command)` | `String` JSON | `adb -s <serial> shell <cmd>`, 60 s timeout | Phone-control "test control" (`getprop ro.product.model`) |
| 5 | `openDeveloperOptions` (755) | `()` | `boolean` | `startActivity(ACTION_APPLICATION_DEVELOPMENT_SETTINGS)` | Phone-control page |
| 6 | `openWirelessDebuggingSettings` (760) | `()` | `boolean` | `android.settings.WIRELESS_DEBUGGING_SETTINGS`; on Xiaomi/Redmi/Poco first tries a `SubSettings` + `WirelessDebuggingFragment` intent (title string is Chinese); falls back to developer options | Phone-control page |
| 7 | `isEmbeddedAgentAvailable` (812) | `()` | `boolean` | Calls Chaquopy `hermes_mobile_backend.initialize(...)` and returns `available && ok` | JS wrapper only (never called in 0.5.0) |
| 8 | `getEmbeddedAgentStatus` (825) | `()` | `String` JSON | Chaquopy `status()` | none in this build |
| 9 | `startEmbeddedAgent` (830) | `()` | `String` JSON | Chaquopy `start(filesDir, 9129)`: prepares dirs, spawns the Hermes FastAPI/web server on `127.0.0.1:9129` in a daemon thread, waits up to 20 s for the port | **First bridge call of the boot gate** |
| 10 | `returnToMobileHome` (835) | `()` | `boolean` | `webView.loadUrl("https://localhost/")` on the UI thread | Native-injected terminal "return to mobile UI" button |
| 11 | `getDashboardSessionToken` (844) | `()` | `String` (raw token, `""` on failure) | GETs `http://127.0.0.1:9129/?locale=zh` and string-parses `window.__HERMES_SESSION_TOKEN__="…"` — **no caching, re-fetched on every call** | Directly (download URL), and internally by #12 |
| 12 | `dashboardRequest` (875) | `(String method, String path, String bodyJson, boolean requireToken, int timeoutMs)` | `String` JSON | `HttpURLConnection` to `http://127.0.0.1:9129` + path; **rejects any path not starting with `/api/`**; default path `/api/status`; adds `X-Hermes-Session-Token` when `requireToken` (obtained via #11); JSON body for non-GET; default timeout 15 s | `requestDashboardJson()` — the only data path for dashboard REST |
| 13 | `runEmbeddedAgentCommand` (939) | `(String commandJson)` | `String` JSON | Chaquopy `run_command({"command": …})`; supports `start|run|serve`, `diagnose|doctor`, `status|version` | Settings "status check" and models "test" (both send `diagnose`) |
| 14 | `callOpenAiChat` (949) | `(String requestJson, int timeoutMs)` | `String` JSON | POSTs `{baseUrl}/chat/completions` with `Authorization: Bearer <apiKey>` | none (JS `callMobileChatCompletion` throws instead) |
| 15 | `getMobileChatConfig` (1023) | `()` | `String` JSON | Reads `<filesDir>/mobile_chat_config.json` | none |
| 16 | `saveMobileChatConfig` (1049) | `(String configJson)` | `boolean` | Writes `{apiKey,baseUrl,model}` to `<filesDir>/mobile_chat_config.json` | none (JS `saveMobileChatConfig` is a local `localStorage` writer, not the bridge) |
| 17 | `requestMicrophoneAccess` (1068) | `(int timeoutMs)` | `boolean` | Blocks on `requestPermissions(RECORD_AUDIO)` and the `onRequestPermissionsResult` latch | `window.hermesDesktop.requestMicrophoneAccess` mock |
| 18 | `showSoftKeyboard` (1073) | `()` | `boolean` (always `true`) | Posts to UI thread: `webView.requestFocus()` + `InputMethodManager.showSoftInput` | Composer focus helper |
| 19 | `speakText` (1089) | `(String optionsJson, int timeoutMs)` | `String` JSON | Android `TextToSpeech`; option `{text, language}`, default language `zh-CN` | TTS toggle / assistant replies |
| 20 | `stopSpeaking` (1112) | `()` | `boolean` | `TextToSpeech.stop()` on UI thread | TTS off toggle |
| 21 | `openExternalUrl` (1126) | `(String url)` | `boolean` | `ACTION_VIEW` intent, 2 s latch | Downloads, community links, "open dashboard in browser" |
| 22 | `recordSpeechText` (1153) | `(String optionsJson, int timeoutMs)` | `String` JSON | `SpeechRecognizer` (falls back to `RecognizerIntent` activity); defaults: language `zh-CN`, prompt Chinese | none — the `window.hermesDesktop` mock explicitly deletes `recordSpeechText`, so voice input is unwired in 0.5.0 |
| 23 | `selectPaths` (1172) | `(String optionsJson, int timeoutMs)` | `String` JSON | `ACTION_OPEN_DOCUMENT` (multi-select allowed) → copies each URI into public `Download/HermesMobileUploads/` and returns absolute paths | `window.hermesDesktop.selectPaths` mock (composer attach) |
| 24 | `readFileDataUrl` (1191) | `(String path)` | `String` JSON | Reads the file, returns a base64 `data:` URL (MIME guessed from the name) | `window.hermesDesktop.readFileDataUrl` mock |

Non-bridge public static helper: `MainActivity.speakTextFromPython(text, language, timeoutMs)` — lets
the embedded Python call native TTS.

Bridge methods the shipped shell actually reaches (17 of 24): `startEmbeddedAgent`,
`getAndroidAdbStatus`, `pairAndroidAdb`, `connectAndroidAdb`, `runAndroidAdbShell`,
`openDeveloperOptions`, `openWirelessDebuggingSettings`, `runEmbeddedAgentCommand`,
`dashboardRequest`, `getDashboardSessionToken`, `openExternalUrl`, `readFileDataUrl`,
`requestMicrophoneAccess`, `selectPaths`, `showSoftKeyboard`, `speakText`, `stopSpeaking`
(plus `returnToMobileHome`, called only from native-injected JS). Unreached in 0.5.0:
`isEmbeddedAgentAvailable`, `getEmbeddedAgentStatus`, `callOpenAiChat`, `getMobileChatConfig`,
`saveMobileChatConfig`, `recordSpeechText`.

### Representative return payloads

```jsonc
// startEmbeddedAgent (success; port/url from hermes_mobile_backend.status())
{"ok":true,"available":true,"running":true,"port":9129,"url":"http://127.0.0.1:9129",
 "uptime_seconds":1.234,"thread_alive":true,"files_dir":"<app-private-files-dir>",
 "error":"","api_status":{ /* verbatim /api/status body */ }}

// failure path (embeddedErrorJson / initialize failure) — the shell keys off `running`
{"ok":false,"available":false,"running":false,"port":9129,"error":"<import/traceback message>"}

// dashboardRequest
{"ok":true,"status":200,"body":"{\"version\":\"...\"}","json":{"version":"..."}}
{"ok":false,"status":0,"error":"Dashboard session token unavailable"}

// getDashboardSessionToken  → raw string, e.g. "a1b2…"  ("" when the page or marker is absent)

// callOpenAiChat                     {"ok":true,"status":200,"body":"{...}"}
// getMobileChatConfig                {"apiKey":"","baseUrl":"","model":""}
// speakText / stopSpeaking           {"ok":true,"error":""}  /  true
// recordSpeechText                   {"ok":true,"text":"…"}   {"ok":false,"error":"…","text":""}
// selectPaths                        {"ok":true,"paths":["/storage/emulated/0/Download/HermesMobileUploads/img-1.png"]}
// readFileDataUrl                    {"ok":true,"dataUrl":"data:image/png;base64,iVBORw0…"}
//                                      error → {"ok":false,"error":"File not found","dataUrl":""}
// getAndroidAdbStatus / adb methods  {"ok":true,"exitCode":0,"timedOut":false,"output":"…",
//                                      "devices":[{"serial":"…","state":"device","details":"…"}],
//                                      "connected":true}
```

---

## 2. Boot sequence (literal order)

Page load → splash → boot gate → **JS-built shell** (not the dashboard).

1. `https://localhost/index.html` renders the splash: centred "H" mark, `Hermes`, the Chinese line
   "starting" (line 123), an indeterminate progress bar. `<script src="./hermes-mobile-bridge.js">`
   is in `<head>` (blocking, line 116). Viewport meta: `width=device-width, initial-scale=1.0,
   maximum-scale=1.0, viewport-fit=cover, user-scalable=no`. CSS defines
   `--hermes-safe-top/right/bottom/left` from `env(safe-area-inset-*)`.
2. The bridge IIFE returns immediately if `window.hermesDesktop` already exists (line 2) — that is how
   the same file is inert when loaded into dashboard pages.
3. On `DOMContentLoaded`: `startAndroidBootGate()`, `installAndroidComposerClickFallbacks()`,
   `installCommunityMenuObserver()`, `installMobileThemeToggle()` (last ~20 lines of the file).
4. `startAndroidBootGate()` (line ~3225):
   a. `ensureAndroidBootOverlay()` injects a **second** overlay (CSS in `<head>`, markup in `<body>`)
      with title "Hermes is starting" and body "Connecting to the local service…", Retry / Later
      buttons, a `<pre>` log area and a version footer (`Hermes Mobile v2026.07.03`).
   b. Redraws it as "Starting the local Hermes service…".
   c. **First bridge call:** `startEmbeddedAgent()` → native #9 → `http://127.0.0.1:9129` comes up.
   d. If `!startResult.running` → error overlay ("the local Hermes service did not start"), status
      items backend/port/mode, Retry button (re-runs the gate), and the gate returns.
   e. On success: `rememberEmbeddedDashboard(startResult)` sets
      `embeddedDashboardBaseUrl = startResult.url || "http://127.0.0.1:9129"` and
      `embeddedTerminalUrl = base + "/chat?fresh=1&channel=android-mobile-terminal&locale=" + locale`.
   f. Overlay becomes "The local Hermes service is running, opening the workspace…" with status
      items (backend / port / `startResult.api_status.version`), then `setTimeout(…, 250)` calls
      `showEmbeddedLiteMode("Hermes is ready")`.
5. `showEmbeddedLiteMode()` (line ~1019) replaces the overlay content with the **shell UI**: progress
   card ("Hermes is preparing" / "Checking the local service, model config and mobile assets"),
   drawer (`hamburger` button, "Take over phone", "New conversation", session search, session list,
   settings gear), header with theme toggle, chat view (composer, attach, TTS toggle, send), and the
   feature pages. `document.getElementById("hermes-mobile-shell")` guards against double-init.
6. Steady state = the JS shell. Navigation into the dashboard SPA happens **only** through
   `openDashboardPath(path)` → `window.location.href = base + path`:

| Trigger | Literal URL (base = `http://127.0.0.1:9129`, from `startResult.url`) |
|---|---|
| models page / "open in dashboard" for models | `http://127.0.0.1:9129/models?locale=zh` — **locale hard-coded to `zh`** |
| Terminal mode (drawer) | `http://127.0.0.1:9129/chat?fresh=1&channel=android-mobile-terminal&locale=<zh\|en>` |
| `feature-open-dashboard` per view | `files`, `cron`, `settings/messaging`, `settings/remote`, `settings/memory`, `settings/skills`, `profiles`, `settings/tools`, `settings/mcp`, `usage`, `settings/appearance` — each `?locale=<zh\|en>`, default `/?locale=<zh\|en>` |
| resource download (opened externally) | `<base>/api/files/download?path=<p>&token=<session token>` |
| native "return to mobile UI" | `https://localhost/` (bridge #10, or `window.location.href`) |

`<zh|en>` comes from `mobileDashboardLocale()`: `localStorage["hermes.mobile.locale"] ||
localStorage["hermes.desktop.locale"]`, **defaulting to `zh`**.

Bridge methods called before any navigation: `startEmbeddedAgent` (only). `dashboardRequest` /
`getDashboardSessionToken` are used after the shell UI exists. `openExternalUrl` is used for
downloads and community links.

Android back-key handling (MainActivity): if the current URL contains `127.0.0.1:9129` or
`localhost:9129` → `webView.goBack()`, or `https://localhost/` when there is no history. Otherwise it
evaluates `window.__hermesHandleMobileBack()` in the page and falls through to the system back
behaviour when that returns falsy.

---

## 3. WebView setup (native)

`configureWebViewForPhoneScreen()` (line ~313) — Capacitor's `BridgeActivity` has already created the
WebView; only these toggles are set explicitly:

```java
settings.setUseWideViewPort(false);
settings.setLoadWithOverviewMode(false);
settings.setTextZoom(100);
settings.setSupportZoom(false);
settings.setBuiltInZoomControls(false);
settings.setDisplayZoomControls(false);
settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
webView.setInitialScale(0);
webView.addJavascriptInterface(new HermesAndroidBridge(), "HermesAndroid");
```

Not set in `MainActivity` (inherited from Capacitor 8 defaults, reproduced by
`capacitor.config.ts`): JavaScript enabled, DOM storage enabled, media playback without user gesture,
file access, `allowMixedContent: true`, `cleartext: true`, `hostname: "localhost"`,
`allowNavigation: ["127.0.0.1", "localhost"]`. **Our host must set all of these explicitly.**

- **Interface name:** `HermesAndroid`.
- **Assets / scheme:** served by Capacitor's local server at **`https://localhost/`** (Android scheme
  `https`, hostname `localhost`) — *not* `file://`. `returnToMobileHome()` hard-loads
  `https://localhost/`, and `allowNavigation` whitelists the two dashboard hosts. The shell relies on
  this origin: `window.location.href = "https://localhost/"` and `base = embeddedDashboardBaseUrl ||
  window.location.origin` in places.
- **WebChromeClient** override: `onPermissionRequest` grants `RESOURCE_AUDIO_CAPTURE` only when
  `RECORD_AUDIO` is already granted, otherwise denies.
- **WebViewClient:** subclass of Capacitor's `BridgeWebViewClient`; `shouldOverrideUrlLoading`
  punts any non-`http(s)` scheme to an `ACTION_VIEW` intent (`openExternalUri`), everything else stays
  in the WebView.
- **Splash:** `AppTheme.NoActionBarLaunch` (`Theme.SplashScreen`, `@drawable/splash`); WebView fills a
  `CoordinatorLayout` (`res/layout/activity_main.xml`).
- **Activity:** portrait-locked (`SCREEN_ORIENTATION_PORTRAIT`), `launchMode="singleTask"`,
  `configChanges` includes `locale`, `smallestScreenSize`, `density`.
- **Insets:** `setOnApplyWindowInsetsListener` on the WebView stores
  `systemBars | displayCutout` px values into `safeTopPx/…` and pushes them into the page (see §4).
- **Manifest permissions:** `INTERNET`, `RECORD_AUDIO`, `READ_EXTERNAL_STORAGE` (maxSdk 32),
  `WRITE_EXTERNAL_STORAGE` (maxSdk 28); `usesCleartextTraffic="true"`; a `FileProvider` with
  `external-path` + `cache-path` mappings.

---

## 4. Injected page-level CSS/JS (native side)

Injected on **every** `onPageFinished`, for both the shell and dashboard pages:
`injectHermesMobileTweaks(view)` (CSS + behaviour JS, `buildHermesMobileTweaksScript()`, lines ~468–730)
and `pushSafeAreaInsetsToPage(view)` (also re-pushed 120 ms after load).

### 4a. Safe-area plumbing (small, exact)

```js
window.__hermesAndroidSetInsets = function(top, right, bottom, left) {
  var dpr = window.devicePixelRatio || 1;
  var doc = document.documentElement;
  doc.style.setProperty('--hermes-safe-top',    Math.ceil((top    || 0) / dpr) + 'px');
  doc.style.setProperty('--hermes-safe-right',  Math.ceil((right  || 0) / dpr) + 'px');
  doc.style.setProperty('--hermes-safe-bottom', Math.ceil((bottom || 0) / dpr) + 'px');
  doc.style.setProperty('--hermes-safe-left',   Math.ceil((left   || 0) / dpr) + 'px');
};
```
Native then calls `window.__hermesAndroidSetInsets(<top>,<right>,<bottom>,<left>);` with raw px.

### 4b. Forced mobile layout (`desktop-mobile-layout-2026-07-03-13`)

A single `<style id="hermes-android-mobile-style">` that (a) adds
`html.hermes-android-app`, (b) locks the app to the visual viewport, (c) turns the dashboard's desktop
sidebar into a fixed overlay drawer. Trimmed essentials:

```css
html.hermes-android-app{--hermes-safe-*:0px;--hermes-mobile-bottom-gap:max(36px,calc(var(--hermes-safe-bottom,0px) + 18px));overscroll-behavior:none;background:#10141b;}
html.hermes-android-app body{position:fixed!important;inset:0!important;padding:var(--hermes-safe-top,0px) var(--hermes-safe-right,0px) var(--hermes-mobile-bottom-gap,10px) var(--hermes-safe-left,0px)!important;background:#10141b;-webkit-tap-highlight-color:transparent;}
html.hermes-android-app input,textarea,select{font-size:16px!important;}          /* stop iOS/Android zoom-on-focus */
@media(max-width:640px){
  html.hermes-android-app #root aside,[data-sidebar='sidebar'],[data-slot='sidebar']{display:none!important;}
  html.hermes-android-app #root [data-hermes-force-sidebar='true']{display:block!important;width:min(84vw,340px)!important;…}
  html.hermes-android-app #root [data-hermes-mobile-sidebar-shell='true']{pointer-events:none!important;position:fixed!important;z-index:2147483600!important;transform:translateX(calc(-100% - 16px))!important;transition:transform .18s ease-out!important;}
  html.hermes-android-app.hermes-mobile-sidebar-open #root [data-hermes-mobile-sidebar-shell='true']{pointer-events:auto!important;translate:0 0!important;transform:none!important;}
  html.hermes-android-app #root [class*='right-rail'],[class*='RightRail'],footer,[class*='statusbar'],[class*='Statusbar']{display:none!important;}  /* hide desktop chrome */
  html.hermes-android-app textarea{max-height:34dvh!important;}
  html.hermes-android-app [data-slot='composer-root'] button{min-width:44px!important;min-height:44px!important;…}   /* 44px touch targets */
}
html.hermes-android-terminal-mode body{padding-bottom:74px!important;}
html.hermes-android-terminal-mode .xterm,.xterm-screen,.xterm-viewport{padding-bottom:72px!important;}
```
Also injected: a rewritten `<meta name="viewport">` (`width=device-width, initial-scale=1,
maximum-scale=1, viewport-fit=cover, user-scalable=no`) and a `#hermes-mobile-menu-button` (a `☰`
FAB) plus a `#hermes-mobile-sidebar-scrim`.

### 4c. Terminal-mode chrome

If the URL matches `channel=android-mobile-terminal`, `html.hermes-android-terminal-mode` is added and
native injects a floating **"return to mobile UI"** button (`#hermes-terminal-return-button`, 48 px,
top-left) whose click calls `window.HermesAndroid.returnToMobileHome()` (fallback
`location.href = 'https://localhost/'`), plus a bottom shortcut bar `#hermes-terminal-shortcuts` with
`Esc Tab Ctrl-C Ctrl-D Ctrl-L ↑ ↓ ← →` synthesised as `KeyboardEvent` pairs on
`.xterm-helper-textarea`.

### 4d. Behavioural hooks (must be preserved for the injected CSS to work)

`window.__hermesAndroidToggleSidebar(open)` + `applyHermesMobileChromeTweaks()` tag the dashboard's
sidebar with `data-hermes-force-sidebar` / `data-hermes-mobile-sidebar-shell`, maintain
`hermes-mobile-sidebar-open` on `<html>`, auto-close the drawer after a nav click, and hide
bottom-anchored buttons whose label matches the gateway/agents/cron pattern. `syncHermesViewport()`
sets `--hermes-app-width/height` from `visualViewport`; observers re-run on `resize`,
`orientationchange` and DOM mutations.

**Localisation trap:** the injected JS matches *Chinese* button labels and `aria-label`s: a
"keep the drawer open" regex (Search / Shift+ / pinned / "session N"), a "hide this nav button" regex
(gateway / Gateway / proxy / Agents / schedule / Cron / `v0.x`), an attribute selector on a
Chinese "menu" aria-label, and Chinese values for the scrim's "close menu", the menu button's
"open menu" and the terminal return button's own text. None of it matches an English dashboard — a port must translate
these selectors/regexes, not just the user-visible copy. Also note the injected drawer CSS assumes the
shell/dashboard keep the class names `hermes-mobile-sidebar-open`, `hermes-android-app`,
`hermes-android-terminal-mode` and the attributes `data-slot="sidebar"`, `data-sidebar="sidebar"`.

---

## 5. Endpoints the shell itself calls

All REST calls go through the **native `dashboardRequest` proxy** when the bridge is present
(the `requestDashboardJson()` helper in `hermes-mobile-bridge.js`) — base URL is fixed to
`http://127.0.0.1:9129` by native code, the path is validated to start with `/api/`, and the token
header is attached natively. Only if
`window.HermesAndroid.dashboardRequest` is missing does it fall back to `fetch(base + path)` with
`headers["X-Hermes-Session-Token"]`.

| Path (method) | Via | Purpose |
|---|---|---|
| `/api/status` (GET, no token) | proxy | backend health / version (remote + integrations pages, `diagnose`) |
| `/api/model/info` (GET, no token) | proxy | current model + whether an API key is set |
| `/api/model/set` (POST, token) | proxy | save endpoint/model/key into the backend |
| `/api/files` (GET, token, `?path=`) | proxy | resource browser listing |
| `/api/files/download?path=&token=` (GET) | **direct** (`openExternalUrl`) | file download handed to the system |
| `/api/cron/jobs?profile=all` (GET, token) | proxy | scheduled jobs |
| `/api/cron/delivery-targets` (GET, token) | proxy | delivery targets |
| `/api/cron/blueprints` (GET, token) | proxy | automation templates |
| `/api/cron/jobs/{id}/trigger\|pause\|resume` (POST, token) | proxy | job control |
| `/api/messaging/platforms` (GET, token) | proxy | channel list/status |
| `/api/messaging/platforms/{p}` (PUT, token) | proxy | enable/disable a channel |
| `/api/messaging/platforms/{p}/test` (POST, token) | proxy | channel test |
| `/api/gateway/start\|stop` (POST, token) | proxy | start/stop the gateway |
| `/api/gateway/restart` (POST, token) | proxy | restart after channel changes |
| `/api/pairing` (GET, token) | proxy | pending pairing requests |
| `/api/profiles` (GET, token) | proxy | profile list |
| `/api/profiles/active` (GET, token, 5 s) | proxy | active profile |
| `/api/profiles/{id}/soul`-style path (GET, token) | proxy | persona / `SOUL.md` content |
| `/api/memory` (GET, token) | proxy | memory files |
| `/api/memory/providers` (GET, token) | proxy | third-party memory providers |
| `/api/skills` (GET, token) | proxy | installed skills |
| `/api/skills/hub/sources` (GET, token) | proxy | skills-hub sources |
| `/api/tools/toolsets` (GET, token) | proxy | toolset status |
| `/api/mcp/servers` (GET, token) | proxy | configured MCP servers |
| `/api/mcp/catalog` (GET, token) | proxy | MCP catalog |
| `/api/analytics/usage?days=30` (GET, token) | proxy | token/session usage |
| `/api/dashboard/themes` (GET, token) | proxy | theme list |
| `/api/dashboard/theme` (PUT, token) | proxy | set theme |
| `/api/dashboard/font` (GET/PUT, token) | proxy | font setting |
| `/api/sessions/{id}` (DELETE, token) | proxy | delete a backend session |
| `/api/audio/speak` (POST, token) | proxy | server-side TTS fallback when native TTS is absent |
| `/api/config` (GET) | **intercepted in JS** — returns `{display:{language:"zh"}}` without a request | language page |
| `/api/ws?token=<t>` | **direct WebSocket from the page** | gateway JSON-RPC (chat) |
| `/api/pty?fresh=1&channel=android-mobile-graph&cols=96&rows=32&token=<t>` | built by `buildPtyWebSocketUrl()` | **defined but never called in 0.5.0** (dead) |

Gateway RPC envelope over `/api/ws` is JSON-RPC 2.0:
`{"id":"mobile-<ts>-<rand>","jsonrpc":"2.0","method":"<m>","params":{…}}`.
Methods used: **`session.create`**, **`session.resume`** (`{session_id, cols:80}`),
**`prompt.submit`** (`{session_id, text}`), **`voice.tts`** (`{text}`, 5 s timeout). Chat streaming is
assembled from the socket's event frames (thinking / tool / answer sections, with a
`<<<HERMES_ANSWER>>>`-style delimiter splitter for non-streaming output).

`window.hermesDesktop` (a 70-member **mock of the Electron preload API**, installed at the end of the
bridge file) is what the shell code calls instead of raw endpoints for host features:
`api`, `selectPaths`, `readFileDataUrl`, `requestMicrophoneAccess`, `readDir`, `writeClipboard`,
`saveClipboardImage`, `openExternal`, `updates`, `themes`, `petOverlay`, `terminal`, `settings`,
`profile`, `getConnection`, `getBootProgress`, `onBootProgress`, `notify`, `onBackendExit`, … Most
return resolved stubs; `api` unconditionally rejects except for `/api/config`; `recordSpeechText` is
explicitly deleted; three members are real (they delegate to the native bridge: `selectPaths`,
`readFileDataUrl`, `requestMicrophoneAccess`). This object is also the **presence marker** that stops
the bridge script from booting a second time inside dashboard pages.

---

## 6. Dashboard session token

- **Method:** `HermesAndroid.getDashboardSessionToken()` → `MainActivity$HermesAndroidBridge.getDashboardSessionToken()` (line 844).
- **Mechanism:** it does **not** hold or mint a token. Every call performs a fresh
  `GET http://127.0.0.1:9129/?locale=zh` with 5 s connect/read timeouts, string-searches the HTML for
  the marker `window.__HERMES_SESSION_TOKEN__="`, and returns the substring up to the next `"`.
  On any failure it logs and returns `""`.
- **Expiry/refresh:** none. There is no cache, no timestamp, no backoff — the token is re-scraped per
  call (and `dashboardRequest` calls it again for every token-requiring request, so one dashboard
  fetch per API call). Refresh is implicit: a rotated token is picked up by the next scrape. A missing
  token turns into `IllegalStateException("Dashboard session token unavailable")` (proxy) or the JS
  error "cannot connect to the local session".
- The JS also reads the token directly for the two places native can't help: the `/api/files/download`
  URL and the WebSocket URLs.
- Query-parameter note: this scrape hard-codes `?locale=zh`; the dashboard therefore renders its
  Chinese variant to mint the token even when the user picked English.

---

## 7. Chinese strings and localisation

There is **no i18n layer anywhere in the shell**. Counts (extracted mechanically; the doc is
Chinese-free by design, so the table gives the English that must replace each string plus its source
location):

| Scope | Unique Chinese segments | Notes |
|---|---|---|
| `www/index.html` | 1 | line 123 — the splash subtitle "starting"; also `<html lang="zh">` (line 2) |
| `MainActivity.java` | 26 | comments, error strings, injected-script labels and two label regexes — all listed below |
| `AndroidAdbManager.java` | 0 | fully English |
| `www/hermes-mobile-bridge.js` | 444 | ~248 are short UI labels; the rest are inline HTML/template glue and regexes. Every user-visible string in the shell is Chinese |

Reproduce the full list at any time with:
`grep -n -P '\p{Han}' www/hermes-mobile-bridge.js` (4,036-line file; expected ≈444 distinct segments).

### 7a. `MainActivity.java` — 26 unique strings across 28 lines (line → English)

| Line | Kind | English |
|---|---|---|
| 114–115 | comment | Android native bridge boundary: starts the APK-embedded Python/Hermes runtime; the real Agent code lives in `src/main/python` and no business logic belongs here. |
| 366–367 | comment | Keep the local Hermes page opening inside the app WebView, and apply the Android wrapper's mobile-screen adaptation after the page loads. |
| 506 | injected CSS selector | `...button[aria-label*='<menu>']...` — hides the menu button in terminal mode (attribute value is Chinese) |
| 530–531 | injected JS | terminal-mode button text + `aria-label`: "Return to mobile UI" |
| 597 | injected JS | scrim `aria-label`: "Close menu" |
| 604 | injected JS | menu-button `aria-label`: "Open menu" |
| 616 | injected JS regex | keep drawer open when the label matches: Search / Shift+ / pinned / "session N" |
| 623 | injected JS regex | hide bottom nav button when the label matches: gateway / Gateway / proxy / Agents / schedule / Cron / `v0.x` |
| 783 | Intent extra | "Wireless debugging" (Settings `SubSettings` fragment title) |
| 959 | validation | enter the API endpoint first |
| 962 | validation | the API endpoint must start with `http://` or `https://` |
| 965 | validation | enter the API key first |
| 968 | validation | enter the model name first |
| 971 | validation | messages must not be empty |
| 1003 | error | API request failed: HTTP `<code>` |
| 1250 | TTS error | nothing to speak |
| 1263, 1338 | TTS error | speech playback unavailable |
| 1275 | TTS error | speech playback failed to start |
| 1286 | TTS error | speech playback start timed out |
| 1320 | TTS error | speech playback initialisation failed |
| 1332 | TTS error | speech playback initialisation timed out |
| 1364 | speech default | SpeechRecognizer prompt: "dictation" |
| 1608 | UI | file chooser title: "Select files" |
| 1710 | log | failed to copy the selected file: |

`res/values/strings.xml` is already English (`Hermes`); the launcher label is `Hermes`.

### 7b. `hermes-mobile-bridge.js` — required English copy, by screen

Boot overlay: Hermes is starting · Connecting to the local service… · Retry · Try later ·
Hermes Mobile (kicker) · Starting the local Hermes service… · The local Hermes service did not start.
Fix the backend problem first, then open the app. · Retry start · Backend · Port · Mode · Local ·
Service failed to start · The local Hermes service is running, opening the workspace… · Version ·
Hermes is ready · Hermes is preparing the interface, please retry shortly. · Reopen · Start failed ·
Running / Not running · Address: · Uptime: · N seconds · Service API: responding · Error: ·
No status was returned by the local service.

Progress card: Hermes is preparing · Checking the local service, model configuration and mobile assets.

Drawer: Hermes navigation drawer · Your mobile agent · Close navigation · Take over phone ·
Start a new conversation · Search sessions · Settings · Hermes Mobile · Local agent ·
Local agent / Mobile · Open navigation · Toggle night mode · Toggle day mode · Sessions ·
New session · Clear current · Empty session · Current · N messages · Open · Delete · No messages ·
No matching sessions · New session · Search results · Recent.

Chat/composer: What would you like Hermes to do today? · Organise my tasks for today and prioritise
them. / Organise today's tasks · Check whether my current model configuration is reasonable. /
Check model configuration · I want to use Hermes on my phone, give me a short workflow. / Generate
mobile workflow · Attach · Remove attachment · Input message · Send · Voice playback · Turn voice
playback on / off · Model settings required · Model setup needed · Open Settings → Model & API, pick a
preset, enter the API key and save, then send again · Reading / Streaming generation… · Thinking… ·
Thinking process · Done · Failed · Other · No more output yet. · Receiving background task results… ·
Speaking · Playback stopped · Nothing to speak · Voice playback failed · Playback failed · Audio
playback failed · This device does not support playback · Voice playback enabled · Local service
ready · None yet · Local service returned a malformed response · Local service request failed ·
Local service call failed · Local service connection failed · Local service connection closed ·
Local service response timed out · Local Hermes connection failed. Open the drawer (top-left) and use
terminal mode to continue. · Could not create a local Hermes session. · Could not connect to the local
Hermes. · Local Hermes response timed out. · Background task returned an error. · Local Hermes
returned an error. · Old session repaired · Repairing the old session · Could not restore the previous
session; sending stopped and no new session will be created automatically. Please retry shortly. ·
Local Hermes could not send the message. · The previous session is temporarily unreachable; the
binding was kept and no new session will be created. Please send again. · Graph chat is being wired to
the local Hermes Agent. For now use terminal mode from the left drawer to reach the full Agent.
(call-to-action in the chat view) · Could not connect to the local Hermes session, please retry
shortly or use terminal mode. (token/WS failure)

Settings navigation: Workspace (group: Resource centre, Scheduled automation) ·
Connections (group: Integrations, Remote instance) · Basic settings (group: Model & API, Language,
Appearance, Usage) · Agent capabilities (group: Memory, Artifacts, Skills Hub, Persona, Toolsets,
MCP) · Advanced (group: Sandboxes, Subagents, Command palette). Feature tile titles/subtitles:
Resource centre — produced resources such as images, audio and documents · Scheduled automation —
Cron, natural-language tasks, multi-channel delivery · Integrations — messaging platforms, gateway
configuration, status monitoring · Remote instance — OAuth, account/password, secure WebSocket ·
Model & API — local Hermes models, endpoints and key configuration · Language — language switching
and regional settings · Appearance — dark theme, font and density · Usage — token consumption and
/usage statistics · Memory — structured project memory and user preferences · Artifacts — Artifacts
pipeline and result management · Skills Hub — skill store, install, uninstall, security scan ·
Persona — edit SOUL.md persona and output style · Toolsets — web, terminal, files, code, vision,
image, TTS · MCP — browse, install and switch MCP servers · Sandboxes — local, Docker, SSH,
Singularity, Modal · Subagents — isolated sub-agents and parallel delegation · Command palette —
Cmd/Ctrl+K and global actions.

Feature page stubs (shown instead of a real page): Manage files, produced resources and downloads
managed by local Hermes. · View Cron jobs, delivery targets and automation blueprints. · View
messaging platform configuration, connection status and gateway state. · View remote connections,
pairing requests, profiles and backend health. · View structured memory, user preferences and
third-party memory providers. · View installed Skills, hub sources and recommended skills. · View the
current profile, persona file and output style. · View web, terminal, file, code, vision, image and
TTS toolset status. · View MCP servers, connection methods and catalog state. · View token, session,
model and tool usage for the last 30 days. · View the desktop dashboard theme, font and mobile dark
mode state. · Switch the display language of the mobile UI and the dashboard. · This entry is
categorised and reserved; real Hermes backend capabilities will be wired in later — for full
capability now use terminal mode from the top-left of the drawer. · Keep the ability wired to local
Hermes. · Feature entry · Page failed to render: · Back to settings · Refresh · Advanced settings ·
Loading…

Shared page widgets: Back · Refresh · Advanced settings · Loading local Hermes backend… · Read
failed: · Current location: · Parent folder · Root · Untitled · Folder · Open · Download ·
Loading resources… · No resources to display. · Delivery targets · Local only · Untitled job · No
schedule · Enabled · Paused · Next: · Run now · Resume · Loading jobs… · No scheduled jobs. ·
Automation templates · No template data · Unknown platform · Enabled / Disabled · Configured / Not
configured · Disable · Test · Loading connection state… · No messaging platform configured. ·
Backend status · Abnormal / Available · Pairing requests · N pending requests · No pending requests ·
Open remote settings · Current · Loading remote information… · No profiles found. · Current provider ·
Built-in memory · Built-in memory files · Project memory · User preferences · Memory provider ·
Loading memory state… · No third-party memory provider. · N enabled · N discovered · Disabled ·
In use · Loading Skills… · No installed Skills. · Hub sources · No hub source status ·
Recommended Skills · Loading Persona… · N configurable · N tools · Loading toolsets… · No toolset
data. · Configured · Catalog · N items · Installed · N items · Authentication · Loading MCP… ·
No MCP servers configured. · Catalog examples · Overview · Sessions · API calls · Loading usage… ·
No usage data. · Dashboard theme · Font · Mobile · Dark · Light · Apply · Loading appearance… ·
No theme data. · Follow theme · System sans · System serif · Monospace · Current language ·
Simplified Chinese · Loading ADB status · Phone connected · Phone not connected · ADB ready ·
Complete wireless-debugging pairing first, then enter the address and port. · Recommended: put
Hermes and Settings in split screen first, or open Settings in a small window; keep the pairing-code
window visible and return to Hermes to enter the port and 6-digit code.

Phone-control (ADB) page: Take over phone — let the local Hermes view and operate this Android phone
over wireless ADB. · Recheck · Test control · First-time use · Enable developer mode — open system
Settings → About phone and tap "Build/MIUI version" about 7 times until developer mode is enabled. ·
Open wireless debugging — Settings → More settings/System → Developer options → Wireless debugging,
then turn it on. · Pair with a pairing code — tap "Pair device with pairing code"; the code and port
may reset as soon as that window closes, is dismissed or reopened. · Open developer options · Open
wireless debugging · Step 1: pair · Pairing address and port · e.g. 192.168.1.8:37123 · 6-digit
pairing code · e.g. 123456 · Start pairing · Step 2: connect · Return to the wireless-debugging home
screen and copy the "IP address and port" — note this connect port is usually different from the
pairing port. · Connect address and port · e.g. 192.168.1.8:40765 · Connect phone · Usage — once
connected the Hermes Agent gains the `android_debug` tool: device info, screenshots, taps, typing,
launching apps and ADB shell. High-risk operations (deleting data, uninstalling apps, factory reset)
still require your confirmation. · Pairing / Connecting / Testing (busy labels) · Pairing failed ·
Pairing succeeded. Now return to the wireless-debugging home screen and enter the connect address and
port. · Enter the pairing port and 6-digit code shown in the pairing window. · Connection failed ·
Connection succeeded; Hermes can now take over the phone over ADB. · No connected device found —
complete pairing and connection first. · Control test failed · Control test succeeded, device model:
… · Could not open developer options directly; open them from system Settings. · Could not open
wireless debugging directly; open it from Developer options. · Language: Simplified Chinese.

Models page: Model & API · Model settings — models and keys are stored by the local Hermes service;
the mobile front-end only submits the configuration and does not store the API key in plaintext. ·
Model preset · Select preset · API endpoint · Model · API key · The key is stored in the local Hermes
service · Common parameters (read-only textarea): streaming, temperature, context length, system
prompt and other advanced parameters will keep using the same desktop-side settings; this version
only keeps the entry point. · Status check · Advanced settings · Save · Saving · Enter the API endpoint
and model name first · Enter the API key · Model/API settings saved. The key was written to the local
Hermes service; the front-end stores no plaintext. New sessions will use this configuration. ·
Saving model/API settings failed: · Model configuration: complete / incomplete · Current model: ·
No model settings · Back to chat.

Appearance/language pages: Dark theme, font and density · Theme applied · Theme update failed: ·
Font updated · Font update failed: · Dashboard theme updated · Light · Dark · Follow theme · System
sans · System serif · Monospace · Apply · Language · Simplified Chinese · English.

Community dialog (constants in the shell): Hermes community · Join the chat group and the AI mobile
community for new builds, setup guides, FAQ and mobile workflows. Group number: `436742652` ·
`#AI mobile community` — a QQ channel, channel id `pd51668397`. You are also welcome to follow along
to discuss phone AI workflows, mobile agents and practical tricks. · Enter community · Open QQ to
join the group · Open the QQ channel · Copy group number · Close · Copied · Copy failed · Tried to
open QQ. If nothing happened, copy the group number and search manually. · Could not open QQ; copy the
group number and search manually. · Tried to open the QQ channel. If nothing happened, search the
channel id in QQ. · Could not open the QQ channel; search the channel id in QQ. · Chat group ·
Community · Join `Hermes mobile` chat group — new builds, setup guides, FAQ and mobile usage tips.

Terminal mode: Terminal mode is for advanced operations. After entering, use the "return to mobile
UI" button at the top to go back. Continue? (confirm dialog) · Return to mobile UI (injected by native).

Model presets (labels shown in the picker; vendor names are already Latin — only the trailing Chinese
needs rewriting): "OpenAI official", "Zhipu GLM", "Qwen (Tongyi Qianwen)", "OpenRouter",
"Ollama / local llama3.1", "Custom". Default endpoint/model constants in the shell:
`DEFAULT_CHAT_ENDPOINT = "https://api.openai.com/v1"`, `DEFAULT_CHAT_MODEL = "gpt-5.5"`.
Channel list (mostly Latin): Telegram, Discord, Slack, WhatsApp, Signal, Feishu, DingTalk, WeCom,
WeChat (personal), QQ, Email, SMS, Matrix, Line, Teams, Notion, GitHub, Webhooks, RSS, Custom HTTP —
the four Chinese ones are Feishu / DingTalk / WeCom / WeChat.

Unsupported-on-Android notices (the "must stub" texts): The Android build currently uses
APK-embedded mobile capabilities. · The Android build cannot read that file directly yet. · The
Android build cannot read clipboard images yet. · The Android build cannot save images yet. · The
Android build does not support the theme market yet. · The Android build does not support the
built-in terminal yet. · Application directory.

### 7c. Localisation handling (complete)

- `lang="zh"` on the shell document; the shell has no runtime language switch for its own strings —
  the Language page only persists a preference.
- `mobileDashboardLocale()` reads `localStorage["hermes.mobile.locale"] || localStorage["hermes.desktop.locale"]`,
  **defaults to `zh`**, and only passes `en` through when the stored value is exactly `"en"`.
- The Language page writes three keys on change: `hermes.mobile.locale`, `hermes.desktop.locale`,
  `hermes-locale`.
- Locale reaches the backend only as a query parameter on dashboard URLs (`?locale=zh|en`), never as
  an HTTP header.
- Hard-coded `zh` in three places: the token scrape (`/?locale=zh`), the models link
  (`/models?locale=zh`), and the native token fetch; the terminal URL is correct-by-default because
  the locale helper falls back to `zh` anyway.
- Native side: TTS initialises with `Locale.SIMPLIFIED_CHINESE` and falls back to the device locale;
  `speakText` defaults `language` to `zh-CN`; `recordSpeechText` defaults language `zh-CN` and prompt
  "dictation"; the Xiaomi wireless-debugging intent title is Chinese; Android's own strings.xml is
  English.
- Theme preference: `localStorage["hermes.mobile.theme"]`; other shell state keys:
  `hermes.mobile.chat.config`, `hermes.mobile.chat.history`, `hermes.mobile.chat.sessions.v1`,
  `hermes.mobile.ready.v1`, `hermes.mobile.tts.enabled`, `hermes.mobile.adb.host`, `hermes.mobile.adb.port`.

---

## 8. What a plain Android WebView + our own bridge would NOT provide

`must implement` = the shell breaks or degrades without it. `must stub` = the shell/backend calls it,
but our architecture satisfies it elsewhere (guest VM) or deliberately drops it.

**Host/WebView plumbing**

| Item | Verdict | Why |
|---|---|---|
| Local asset server at `https://localhost/` | **must implement** | Capacitor's local server; the shell hard-loads `https://localhost/` and uses `window.location.origin`. Use `WebViewAssetLoader` mapped to `https://localhost/`. |
| Capacitor `BridgeActivity` / `BridgeWebViewClient` / `BridgeWebChromeClient` | **must implement** | Activity base class, navigation policy and `onPermissionRequest` (mic) live there. |
| Capacitor default `WebSettings` (JS, DOM storage, media playback w/o gesture, file access, mixed content, `cleartext`, `allowNavigation` hosts) | **must implement** | Not set explicitly in `MainActivity`; without them the shell cannot store sessions or play audio. |
| Injected mobile-layout CSS/JS + safe-area insets (§4) | **must implement** | Drives the whole responsive/drawer behaviour on dashboard pages. Translate the Chinese label selectors. |
| `onActivityResult` / permission flow (file picker 9001, mic 9002, speech 9003) | **must implement** | Compose host needs `registerForActivityResult`. |
| `FileProvider` + `file_paths.xml` | must implement (if sharing) | Manifest-declared `{applicationId}.fileprovider`. |
| `android_debug` agent tool / wireless ADB workflow | **must implement or drop** | The ADB page is a headline feature; keep it host-side or move it into the guest. |

**Embedded runtime (our app replaces all of this)**

| Item | Verdict | Notes |
|---|---|---|
| Chaquopy `Python.start(new AndroidPlatform(this))`, `Python.getModule("hermes_mobile_backend")`, `PyObject.callAttr` | **must stub** | `ensureEmbeddedPython()`, `callEmbeddedBackend()`, `prepareHermesNodeRuntime()`. |
| `hermes_mobile_backend.initialize/start/status/run_command` | **must stub** | Bridge methods #7, #8, #9, #13 must instead start/query the in-VM Hermes backend; keep the same JSON keys (`ok`, `available`, `running`, `port`, `url`, `uptime_seconds`, `error`, `api_status`) so the JS needs no change. |
| Bundled Node/TUI runtime (`hermes-node/libnode_exec.so`, `hermes-tui/dist/entry.js`, `HERMES_NODE`, `HERMES_TUI_DIR`, `HERMES_SKIP_NODE_BOOTSTRAP`, `LD_LIBRARY_PATH`) plus the `.hermes-runtime-v3-ready` marker and asset-tree copy | **must stub** | The guest VM supplies the runtime; the host should not carry a second one. |
| Bundled LADB-derived ADB binary `libadb_exec.so` in `nativeLibraryDir` | **must stub** | If the guest VM has `adb`, prefer that; the licence notice also requires replacing it before any Play submission. |
| `speakTextFromPython` (Python → native TTS) | **must stub** | Only meaningful while Python runs in-process. |
| Port 9129 on `127.0.0.1` inside the app process | **must implement differently** | In our app the backend runs in the guest; either forward a host loopback port into the guest or teach the shell a configurable base URL. `dashboardRequest` hard-codes the port (`EMBEDDED_AGENT_PORT`) and rejects non-`/api/` paths. |
| `isEmbeddedAgentAvailable` / `getEmbeddedAgentStatus` / `callOpenAiChat` / `getMobileChatConfig` / `saveMobileChatConfig` | **must stub (unused today)** | Dead in 0.5.0; re-expose only if needed for API compatibility. Note `callOpenAiChat` would send the user's API key from the host process — do not port that path. |
| Android `TextToSpeech` + `SpeechRecognizer` + `RECORD_AUDIO` | **must implement** (host) | TTS tries native first, then `/api/audio/speak`. |
| `ACTION_OPEN_DOCUMENT` file picker copying into public `Download/HermesMobileUploads/` | **must implement / adapt** | Uses legacy `Environment.getExternalStoragePublicDirectory` — needs scoped-storage handling. |

**Nothing else is required:** the shell uses **no Capacitor JS plugins at all** (zero references to
`Capacitor`, `Plugins`, `registerPlugin`, `cordova` or `deviceReady` in `hermes-mobile-bridge.js`) and
no Termux/PRoot/Python paths. Its only external dependencies are `window.HermesAndroid`, `fetch`,
`WebSocket`, `localStorage` and `window.hermesDesktop` (which the bridge itself defines).

---

## 9. Facts checked against the brief

| Brief said | Code says |
|---|---|
| `www/index.html` is a ~3 KB splash (`lang="zh"`, "starting") loading only the bridge script | ✅ confirmed (3,183 B; `<script src="./hermes-mobile-bridge.js">` in `<head>`; splash text at line 123) |
| The shell navigates the WebView into the dashboard SPA at `http://127.0.0.1:9129` (`/chat?fresh=1&channel=android-mobile-terminal&locale=…`) | ❌ **not on boot.** It boots into its own JS shell; that exact URL is used only for terminal mode. The dashboard is opened on demand for `/models?locale=zh` and per-feature deep links |
| Transport: `X-Hermes-Session-Token`; `ws://127.0.0.1:9129/api/ws?token=…`; token also exposed as `window.__HERMES_SESSION_TOKEN__` | ✅ confirmed (`buildGatewayWebSocketUrl()`; native scrapes exactly that marker) — but note the WS is opened **directly by the page**, and REST goes through the native proxy |
| Bridge = `MainActivity.java`, `addJavascriptInterface(…, "HermesAndroid")`, 26 `@JavascriptInterface` occurrences | ✅ interface name and file; **24** annotated methods (lines 734–1190) |
| — | Extra: two more native layers matter as much as the bridge — the injected dashboard CSS/JS (§4) and the `window.hermesDesktop` mock (70 members) |
