# Seamless — MacBook ↔ Android, the Apple-Continuity way

Make an Android phone feel like an iPhone next to your MacBook:

| You do this… | …and this happens | Clicks |
|---|---|---|
| Copy anything on the Mac (⌘C) | It's already on the phone's clipboard | **0** (automatic) |
| Select text on the phone → **Send to Mac** (text-selection menu) | It's on the Mac clipboard, ready for ⌘V | **1** |
| Copied something on the phone already → Quick Settings tile **Clipboard → Mac** | It's on the Mac clipboard | **2** (pull down, tap) |
| Click the Seamless icon in Chrome (or **⌘⇧Y**) | The tab opens on the phone | **1** |
| Same, while watching **YouTube** | The video opens in the phone's YouTube app **at the same second**, and pauses on the Mac | **1** |
| On the phone: **Share → Send to Mac** on any link | It opens in the Mac's browser | **2** |
| Same, from the **YouTube app** | Mac opens the video **at the second you were at**, phone pauses | **2** |
| Right-click a link / selected text in Chrome | "Send link to phone" / "Copy … to phone" | **2** |

Everything runs **locally on your Wi-Fi** — no account, no cloud, no third-party server. All traffic between
the phone and the Mac is end-to-end encrypted with AES-256-GCM using a key that is exchanged once via a QR code.

```
 ┌──────────── MacBook ────────────┐                 ┌──────── Android phone ────────┐
 │ Chrome extension ──HTTP (local)─┐│                 │  Seamless app                  │
 │                                 ▼│  encrypted WS   │   • background link service    │
 │ clipboard ◀──▶  seamless agent ◀─┼────── Wi-Fi ────┼─▶ • share sheet "Send to Mac"   │
 │ browser   ◀──  (Python, launchd) │  (Bonjour find) │   • Quick Settings tile         │
 └──────────────────────────────────┘                 │   • YouTube position (MediaSession)
                                                      └────────────────────────────────┘
```

## Setup (≈5 minutes)

### 1. Mac agent
```bash
git clone <this repo> && cd Seamless-MacBook-Android/mac-agent
./install.sh
```
This installs the agent in `~/.seamless`, starts it at every login, and prints a **pairing QR code**
and a **Chrome extension token**. (Re-print them any time with
`~/.seamless/venv/bin/python ~/.seamless/seamless_agent.py pair`.)
If macOS asks whether Python may accept incoming network connections, click **Allow**.

Prefer to run it manually? `pip3 install -r requirements.txt && python3 seamless_agent.py run`.

### 2. Android app
Build and install (needs Android Studio or the Android SDK, and USB debugging on the phone):
```bash
cd android
./gradlew installDebug          # or: ./gradlew assembleDebug → app/build/outputs/apk/debug/app-debug.apk
```
Open **Seamless** → **Pair with Mac (scan QR)** → scan the code from step 1. Then tap through the setup checklist:

* **Display over other apps** – lets links from the Mac open instantly (otherwise you get a "tap to open" notification).
* **Notification access** – lets the app read the YouTube app's playback position for exact-second handoff.
* **Battery: unrestricted** – keeps the connection alive in the background.
* Optional: pull down Quick Settings → edit (✏️) → drag **Clipboard → Mac** into your top row.

### 3. Chrome extension
1. Open `chrome://extensions`, enable **Developer mode**, click **Load unpacked**, select the `chrome-extension` folder.
2. Paste the token from step 1 into the options page that opens, then **Save & test**.
3. Pin the extension to the toolbar. Shortcuts can be changed at `chrome://extensions/shortcuts`.

## How "exact-second" YouTube handoff works

* **Mac → phone:** the extension reads `currentTime` from the page's `<video>`, pauses it, and sends
  `https://www.youtube.com/watch?v=<id>&t=<seconds>s`. Android opens that in the YouTube app at that time.
* **Phone → Mac:** the YouTube app's share link has no timestamp, so the app asks Android's MediaSession API
  (via notification access) for the YouTube app's current position, pauses it, and adds `&t=` before sending.

## Why some things take one tap instead of zero (Android limits)

Android 10+ only lets the **foreground app** read the clipboard — no app (KDE Connect, Samsung, Microsoft's,
or this one) can silently sync phone → Mac. Seamless gets as close as Android allows: a one-tap
**Send to Mac** in the text-selection menu, a Quick Settings tile, and an action on its notification. The
other direction (Mac → phone) *is* fully automatic, because writing to the clipboard is allowed.

Similarly, Android blocks apps from opening screens from the background unless you grant
**Display over other apps**; with it, links from the Mac open on the phone immediately.

## Security notes

* Phone ↔ Mac frames are AES-256-GCM encrypted; a device without the key from the QR code can't connect,
  read or inject anything (the agent drops it with code 4001). The transport itself is plain `ws://` on the LAN,
  which is why the app sets `usesCleartextTraffic` — the payload is already encrypted.
* The Chrome extension talks to the agent only on `127.0.0.1` and must present the local token.
* Password-looking strings copied on the Mac are marked *sensitive* on Android 13+ so they don't show in the clipboard preview.
* To revoke everything: `python3 seamless_agent.py reset`, then re-pair.

## Works across networks?

Out of the box it needs both devices on the same Wi-Fi (Bonjour finds the Mac again if its IP changes).
For anywhere-access, install [Tailscale](https://tailscale.com) on both and pair using the Mac's Tailscale IP
(edit `h=` in the pairing code).

## Configuration

`~/.config/seamless/config.json` on the Mac:

| key | default | meaning |
|---|---|---|
| `auto_clipboard_to_phone` | `true` | push every Mac copy to the phone |
| `open_urls_from_phone` | `true` | open links from the phone in the browser (otherwise just copy them) |
| `browser` | `""` | app to open links with, e.g. `"Google Chrome"` (empty = default browser) |
| `port` | `47823` | agent port |

## Project layout

```
mac-agent/         Python agent (clipboard sync, local API, Bonjour, launchd installer)
chrome-extension/  Manifest V3 extension (toolbar button, shortcuts, context menus, YouTube timestamps)
android/           Kotlin app (foreground link service, share target, QS tile, MediaSession reader)
```

## Roadmap ideas
* Images/files in the clipboard; drag-and-drop file transfer
* Phone notifications mirrored on the Mac; reply to SMS from the Mac
* Menu-bar app instead of a background Python process
* Auto-pause the Mac video when you pick up the phone
