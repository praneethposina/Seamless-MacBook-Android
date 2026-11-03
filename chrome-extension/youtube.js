// Pure helpers shared by the service worker and the tests.

const YT_HOSTS = new Set(["www.youtube.com", "youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be"]);

export function youtubeVideoId(rawUrl) {
  let u;
  try { u = new URL(rawUrl); } catch { return null; }
  if (!YT_HOSTS.has(u.hostname)) return null;
  if (u.hostname === "youtu.be") return u.pathname.slice(1).split("/")[0] || null;
  if (u.pathname === "/watch") return u.searchParams.get("v");
  const m = u.pathname.match(/^\/(shorts|live|embed)\/([\w-]{6,})/);
  return m ? m[2] : null;
}

/** Build a link that opens the YouTube app/site at `seconds`. */
export function youtubeLinkAt(rawUrl, seconds) {
  const id = youtubeVideoId(rawUrl);
  if (!id) return rawUrl;
  const src = new URL(rawUrl);
  const host = src.hostname === "music.youtube.com" ? "music.youtube.com" : "www.youtube.com";
  const out = new URL(`https://${host}/watch`);
  out.searchParams.set("v", id);
  const list = src.searchParams.get("list");
  if (list) out.searchParams.set("list", list);
  const t = Math.max(0, Math.floor(seconds || 0));
  if (t > 0) out.searchParams.set("t", `${t}s`);
  return out.toString();
}
