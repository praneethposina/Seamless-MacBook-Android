#!/usr/bin/env python3
"""
Seamless agent for macOS.

The hub that connects your Mac, your Chrome browser and your Android phone:

  * Phone  <-> Mac : encrypted WebSocket on your local network (AES-256-GCM,
                     key shared once via a QR code during pairing).
  * Chrome  -> Mac : tiny HTTP API on 127.0.0.1 used by the Chrome extension.

Features
  * Universal clipboard: anything you copy on the Mac lands on the phone's
    clipboard automatically; anything the phone sends lands on the Mac's.
  * Handoff: URLs sent from the phone open in the Mac browser and vice versa
    (YouTube links carry the exact playback position).

Usage
  python3 seamless_agent.py pair     # show pairing QR code (run once)
  python3 seamless_agent.py run      # start the agent (pairing QR shown if not paired)
  python3 seamless_agent.py token    # print the token for the Chrome extension
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import json
import logging
import os
import platform
import secrets
import shutil
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path
from typing import Any
from urllib.parse import urlencode

from aiohttp import WSMsgType, web
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PROTOCOL_VERSION = 1
DEFAULT_PORT = 47823
SERVICE_TYPE = "_seamless._tcp.local."
HELLO_MAX_SKEW_SECONDS = 120
CLIPBOARD_POLL_SECONDS = 0.4
MAX_TEXT_BYTES = 1_000_000

CONFIG_DIR = Path(os.environ.get("SEAMLESS_CONFIG_DIR", Path.home() / ".config" / "seamless"))
CONFIG_FILE = CONFIG_DIR / "config.json"

log = logging.getLogger("seamless")


# --------------------------------------------------------------------------- #
# Config
# --------------------------------------------------------------------------- #

def b64u(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def b64u_decode(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def load_config() -> dict[str, Any]:
    if CONFIG_FILE.exists():
        cfg = json.loads(CONFIG_FILE.read_text())
    else:
        cfg = {}
    changed = False
    defaults = {
        "id": lambda: uuid.uuid4().hex[:12],
        "name": lambda: (platform.node().split(".")[0] or "Mac"),
        "port": lambda: DEFAULT_PORT,
        "key": lambda: b64u(AESGCM.generate_key(bit_length=256)),
        "local_token": lambda: secrets.token_urlsafe(24),
        "auto_clipboard_to_phone": lambda: True,
        "open_urls_from_phone": lambda: True,
        "browser": lambda: "",  # e.g. "Google Chrome"; empty = system default
    }
    for k, factory in defaults.items():
        if k not in cfg:
            cfg[k] = factory()
            changed = True
    if changed:
        save_config(cfg)
    return cfg


def save_config(cfg: dict[str, Any]) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    CONFIG_FILE.write_text(json.dumps(cfg, indent=2))
    try:
        os.chmod(CONFIG_FILE, 0o600)
    except OSError:
        pass


# --------------------------------------------------------------------------- #
# Crypto: every frame = base64url(nonce[12] || AES-GCM(ciphertext || tag))
# --------------------------------------------------------------------------- #

class Cipher:
    def __init__(self, key_b64: str):
        self._aes = AESGCM(b64u_decode(key_b64))

    def seal(self, msg: dict[str, Any]) -> str:
        nonce = os.urandom(12)
        ct = self._aes.encrypt(nonce, json.dumps(msg).encode(), None)
        return b64u(nonce + ct)

    def open(self, frame: str) -> dict[str, Any]:
        raw = b64u_decode(frame)
        return json.loads(self._aes.decrypt(raw[:12], raw[12:], None))


# --------------------------------------------------------------------------- #
# macOS integration (with harmless fallbacks so the agent can be tested elsewhere)
# --------------------------------------------------------------------------- #

IS_MAC = sys.platform == "darwin"


class Clipboard:
    """Reads/writes the Mac clipboard. Uses NSPasteboard.changeCount when PyObjC is
    available (cheap polling), otherwise pbpaste/pbcopy."""

    def __init__(self) -> None:
        self._pb = None
        self._fake = ""  # used on non-mac platforms (tests)
        if IS_MAC:
            try:
                from AppKit import NSPasteboard  # type: ignore

                self._pb = NSPasteboard.generalPasteboard()
            except Exception:  # PyObjC not installed
                self._pb = None
        self._last_seen = self.read()
        self._last_count = self._change_count()

    def _change_count(self) -> int | None:
        return int(self._pb.changeCount()) if self._pb is not None else None

    def read(self) -> str:
        if self._pb is not None:
            from AppKit import NSPasteboardTypeString  # type: ignore

            return self._pb.stringForType_(NSPasteboardTypeString) or ""
        if IS_MAC:
            return subprocess.run(["pbpaste"], capture_output=True, text=True).stdout
        return self._fake

    def write(self, text: str) -> None:
        if self._pb is not None:
            from AppKit import NSPasteboardTypeString  # type: ignore

            self._pb.clearContents()
            self._pb.setString_forType_(text, NSPasteboardTypeString)
        elif IS_MAC:
            subprocess.run(["pbcopy"], input=text, text=True)
        else:
            self._fake = text
        # Remember what we wrote so we don't echo it back to the phone.
        self._last_seen = text
        self._last_count = self._change_count()

    def poll_change(self) -> str | None:
        """Return new clipboard text if the user copied something new."""
        count = self._change_count()
        if count is not None and count == self._last_count:
            return None
        self._last_count = count
        text = self.read()
        if text and text != self._last_seen:
            self._last_seen = text
            return text
        return None


def notify(title: str, body: str) -> None:
    log.info("notify: %s — %s", title, body)
    if not IS_MAC:
        return
    if shutil.which("terminal-notifier"):
        subprocess.Popen(["terminal-notifier", "-title", title, "-message", body[:200], "-sender", "com.google.Chrome"])
        return
    script = f"display notification {json.dumps(body[:200])} with title {json.dumps(title)}"
    subprocess.Popen(["osascript", "-e", script])


def open_url(url: str, browser: str) -> None:
    log.info("opening %s", url)
    if not IS_MAC:
        return
    cmd = ["open"]
    if browser:
        cmd += ["-a", browser]
    subprocess.Popen(cmd + [url])


def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packets are sent
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


# --------------------------------------------------------------------------- #
# Pairing
# --------------------------------------------------------------------------- #

def pairing_uri(cfg: dict[str, Any]) -> str:
    q = urlencode({"h": lan_ip(), "p": cfg["port"], "k": cfg["key"], "n": cfg["name"], "id": cfg["id"], "v": PROTOCOL_VERSION})
    return f"seamless://pair?{q}"


def print_pairing(cfg: dict[str, Any]) -> None:
    uri = pairing_uri(cfg)
    print("\n  Pair your Android phone: open the Seamless app → 'Pair with Mac' → scan this code.\n")
    try:
        import qrcode  # type: ignore

        qr = qrcode.QRCode(border=1)
        qr.add_data(uri)
        qr.make(fit=True)
        qr.print_ascii(invert=True)
    except ImportError:
        print("  (install 'qrcode' to show a QR code)")
    print(f"\n  Or paste this into the app:\n  {uri}\n")


# --------------------------------------------------------------------------- #
# Agent
# --------------------------------------------------------------------------- #

class Agent:
    def __init__(self, cfg: dict[str, Any]):
        self.cfg = cfg
        self.cipher = Cipher(cfg["key"])
        self.clipboard = Clipboard()
        self.phones: dict[web.WebSocketResponse, str] = {}
        self._zeroconf = None

    # ---- outbound ---------------------------------------------------------
    async def send_to_phones(self, msg: dict[str, Any]) -> int:
        frame = self.cipher.seal(msg)
        sent = 0
        for ws in list(self.phones):
            try:
                await ws.send_str(frame)
                sent += 1
            except Exception as e:  # noqa: BLE001
                log.warning("send failed: %s", e)
        return sent

    # ---- inbound from phone -----------------------------------------------
    async def handle_phone_message(self, device: str, msg: dict[str, Any]) -> dict[str, Any] | None:
        kind = msg.get("type")
        if kind == "ping":
            return {"type": "pong"}
        if kind in ("clipboard", "text"):
            text = str(msg.get("text", ""))[:MAX_TEXT_BYTES]
            if text:
                self.clipboard.write(text)
                notify(f"Copied from {device}", text)
            return {"type": "ack", "id": msg.get("id")}
        if kind == "url":
            url = str(msg.get("url", ""))
            if url.startswith(("http://", "https://")):
                if self.cfg.get("open_urls_from_phone", True):
                    open_url(url, self.cfg.get("browser", ""))
                else:
                    self.clipboard.write(url)
                    notify(f"Link from {device}", url)
            return {"type": "ack", "id": msg.get("id")}
        log.debug("ignoring message type %r", kind)
        return None

    # ---- websocket endpoint for phones ------------------------------------
    async def ws_handler(self, request: web.Request) -> web.WebSocketResponse:
        ws = web.WebSocketResponse(heartbeat=25)
        await ws.prepare(request)
        device = None
        try:
            first = await asyncio.wait_for(ws.receive(), timeout=10)
            if first.type != WSMsgType.TEXT:
                raise ValueError("expected text hello")
            hello = self.cipher.open(first.data)  # raises if key is wrong
            if hello.get("type") != "hello" or abs(time.time() - float(hello.get("ts", 0))) > HELLO_MAX_SKEW_SECONDS:
                raise ValueError("bad hello")
            device = str(hello.get("device") or "Phone")[:64]
        except Exception as e:  # noqa: BLE001
            log.warning("rejected connection from %s: %s", request.remote, e)
            await ws.close(code=4001, message=b"unauthorized")
            return ws

        self.phones[ws] = device
        log.info("phone connected: %s (%s)", device, request.remote)
        await ws.send_str(self.cipher.seal({"type": "welcome", "name": self.cfg["name"], "id": self.cfg["id"], "v": PROTOCOL_VERSION}))
        notify("Seamless", f"{device} connected")
        try:
            async for m in ws:
                if m.type != WSMsgType.TEXT:
                    continue
                try:
                    msg = self.cipher.open(m.data)
                except Exception:  # noqa: BLE001
                    log.warning("dropping undecryptable frame from %s", device)
                    continue
                reply = await self.handle_phone_message(device, msg)
                if reply:
                    await ws.send_str(self.cipher.seal(reply))
        finally:
            self.phones.pop(ws, None)
            log.info("phone disconnected: %s", device)
        return ws

    # ---- local API for the Chrome extension -------------------------------
    def _check_local(self, request: web.Request) -> None:
        if request.remote not in ("127.0.0.1", "::1"):
            raise web.HTTPForbidden(text="local only")
        token = request.headers.get("X-Seamless-Token", "")
        if not secrets.compare_digest(token, self.cfg["local_token"]):
            raise web.HTTPUnauthorized(text="bad token")

    async def api_status(self, request: web.Request) -> web.Response:
        self._check_local(request)
        return web.json_response({"ok": True, "name": self.cfg["name"], "phones": sorted(self.phones.values())})

    async def api_send(self, request: web.Request) -> web.Response:
        self._check_local(request)
        body = await request.json()
        kind = body.get("type")
        if kind == "url":
            msg = {"type": "url", "url": str(body["url"]), "title": str(body.get("title", ""))[:300]}
        elif kind in ("text", "clipboard"):
            msg = {"type": "clipboard", "text": str(body["text"])[:MAX_TEXT_BYTES]}
        elif kind == "mac_clipboard":
            msg = {"type": "clipboard", "text": self.clipboard.read()}
        else:
            raise web.HTTPBadRequest(text="unknown type")
        sent = await self.send_to_phones(msg)
        return web.json_response({"ok": sent > 0, "delivered_to": sent})

    @web.middleware
    async def cors(self, request: web.Request, handler):  # type: ignore[no-untyped-def]
        origin = request.headers.get("Origin", "")
        allowed = origin.startswith("chrome-extension://")
        if request.method == "OPTIONS":
            resp: web.StreamResponse = web.Response()
        else:
            resp = await handler(request)
        if allowed:
            resp.headers["Access-Control-Allow-Origin"] = origin
            resp.headers["Access-Control-Allow-Headers"] = "Content-Type, X-Seamless-Token"
            resp.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
        return resp

    # ---- background tasks --------------------------------------------------
    async def clipboard_watcher(self) -> None:
        while True:
            await asyncio.sleep(CLIPBOARD_POLL_SECONDS)
            if not self.cfg.get("auto_clipboard_to_phone", True) or not self.phones:
                # still consume changes so we don't send stale content on connect
                self.clipboard.poll_change()
                continue
            try:
                text = self.clipboard.poll_change()
            except Exception as e:  # noqa: BLE001
                log.warning("clipboard read failed: %s", e)
                continue
            if text and len(text.encode()) <= MAX_TEXT_BYTES:
                await self.send_to_phones({"type": "clipboard", "text": text})
                log.info("clipboard → phone (%d chars)", len(text))

    async def advertise(self) -> None:
        try:
            from zeroconf import ServiceInfo
            from zeroconf.asyncio import AsyncZeroconf
        except ImportError:
            log.info("zeroconf not installed; phone will use the paired IP address only")
            return
        ip = lan_ip()
        info = ServiceInfo(
            SERVICE_TYPE,
            f"Seamless-{self.cfg['id']}.{SERVICE_TYPE}",
            addresses=[socket.inet_aton(ip)],
            port=int(self.cfg["port"]),
            properties={"id": self.cfg["id"], "name": self.cfg["name"], "v": str(PROTOCOL_VERSION)},
        )
        self._zeroconf = AsyncZeroconf()
        await self._zeroconf.async_register_service(info)
        log.info("advertising %s on %s:%s via Bonjour", info.name, ip, self.cfg["port"])

    def build_app(self) -> web.Application:
        app = web.Application(middlewares=[self.cors], client_max_size=2 * MAX_TEXT_BYTES)
        app.router.add_get("/ws", self.ws_handler)
        app.router.add_get("/api/status", self.api_status)
        app.router.add_post("/api/send", self.api_send)

        async def preflight(_: web.Request) -> web.Response:
            return web.Response()

        app.router.add_route("OPTIONS", "/api/{tail:.*}", preflight)

        async def on_startup(_: web.Application) -> None:
            app["clip_task"] = asyncio.create_task(self.clipboard_watcher())
            await self.advertise()

        async def on_cleanup(_: web.Application) -> None:
            app["clip_task"].cancel()
            if self._zeroconf is not None:
                await self._zeroconf.async_close()

        app.on_startup.append(on_startup)
        app.on_cleanup.append(on_cleanup)
        return app


def main() -> None:
    parser = argparse.ArgumentParser(description="Seamless Mac ↔ Android agent")
    parser.add_argument("command", nargs="?", default="run", choices=["run", "pair", "token", "reset"])
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    if args.command == "reset":
        if CONFIG_FILE.exists():
            CONFIG_FILE.unlink()
        print("Pairing reset. Run 'pair' again and re-pair your phone and Chrome extension.")
        return

    cfg = load_config()
    if args.command == "pair":
        print_pairing(cfg)
        print(f"  Chrome extension token: {cfg['local_token']}\n")
        return
    if args.command == "token":
        print(cfg["local_token"])
        return

    if not cfg.get("paired_once"):
        print_pairing(cfg)
        print(f"  Chrome extension token: {cfg['local_token']}\n")
        cfg["paired_once"] = True
        save_config(cfg)

    agent = Agent(cfg)
    web.run_app(agent.build_app(), host="0.0.0.0", port=int(cfg["port"]), print=lambda *_: log.info("listening on :%s", cfg["port"]))


if __name__ == "__main__":
    main()
