import { youtubeVideoId, youtubeLinkAt } from "./youtube.js";

const DEFAULTS = { port: 47823, token: "", pauseAfterSend: true };

async function settings() {
  return { ...DEFAULTS, ...(await chrome.storage.sync.get(Object.keys(DEFAULTS))) };
}

async function callAgent(path, body) {
  const s = await settings();
  if (!s.token) {
    chrome.runtime.openOptionsPage();
    throw new Error("Set the agent token first");
  }
  const res = await fetch(`http://127.0.0.1:${s.port}${path}`, {
    method: body ? "POST" : "GET",
    headers: { "Content-Type": "application/json", "X-Seamless-Token": s.token },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) throw new Error(`Agent error ${res.status}`);
  const json = await res.json();
  if (body && !json.ok) throw new Error("Phone is not connected");
  return json;
}

async function flash(tabId, ok, message) {
  await chrome.action.setBadgeBackgroundColor({ color: ok ? "#1a7f37" : "#cf222e" });
  await chrome.action.setBadgeText({ text: ok ? "✓" : "!", tabId });
  setTimeout(() => chrome.action.setBadgeText({ text: "", tabId }), 2000);
  if (!ok) {
    chrome.notifications.create({ type: "basic", iconUrl: "icon128.png", title: "Seamless", message });
  }
}

/** Read the playing position of the page's main <video> (and optionally pause it). */
async function grabVideoTime(tabId, pause) {
  try {
    const [{ result }] = await chrome.scripting.executeScript({
      target: { tabId },
      args: [pause],
      func: (pause) => {
        const videos = [...document.querySelectorAll("video")].filter((v) => v.duration > 0);
        const v = videos.sort((a, b) => b.clientWidth * b.clientHeight - a.clientWidth * a.clientHeight)[0];
        if (!v) return null;
        if (pause) v.pause();
        return v.currentTime;
      },
    });
    return result;
  } catch {
    return null; // e.g. chrome:// pages
  }
}

async function sendTab(tab) {
  try {
    let url = tab.url;
    if (youtubeVideoId(url)) {
      const s = await settings();
      const t = await grabVideoTime(tab.id, s.pauseAfterSend);
      url = youtubeLinkAt(url, t ?? 0);
    }
    await callAgent("/api/send", { type: "url", url, title: tab.title });
    await flash(tab.id, true);
  } catch (e) {
    await flash(tab.id, false, e.message);
  }
}

async function sendPayload(tabId, payload) {
  try {
    await callAgent("/api/send", payload);
    await flash(tabId, true);
  } catch (e) {
    await flash(tabId, false, e.message);
  }
}

chrome.action.onClicked.addListener(sendTab);

chrome.commands.onCommand.addListener(async (command, tab) => {
  tab = tab || (await chrome.tabs.query({ active: true, currentWindow: true }))[0];
  if (command === "send-tab") return sendTab(tab);
  if (command === "send-clipboard") return sendPayload(tab?.id, { type: "mac_clipboard" });
});

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({ id: "page", title: "Send page to phone", contexts: ["page", "video"] });
    chrome.contextMenus.create({ id: "link", title: "Send link to phone", contexts: ["link"] });
    chrome.contextMenus.create({ id: "text", title: "Copy “%s” to phone", contexts: ["selection"] });
  });
  settings().then((s) => { if (!s.token) chrome.runtime.openOptionsPage(); });
});

chrome.contextMenus.onClicked.addListener((info, tab) => {
  if (info.menuItemId === "page") return sendTab(tab);
  if (info.menuItemId === "link") return sendPayload(tab.id, { type: "url", url: info.linkUrl });
  if (info.menuItemId === "text") return sendPayload(tab.id, { type: "text", text: info.selectionText });
});
