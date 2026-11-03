const $ = (id) => document.getElementById(id);
const s = await chrome.storage.sync.get({ token: "", port: 47823, pauseAfterSend: true });
$("token").value = s.token;
$("port").value = s.port;
$("pause").checked = s.pauseAfterSend;

$("save").addEventListener("click", async () => {
  const token = $("token").value.trim();
  const port = Number($("port").value) || 47823;
  await chrome.storage.sync.set({ token, port, pauseAfterSend: $("pause").checked });
  $("status").textContent = "Testing…";
  try {
    const res = await fetch(`http://127.0.0.1:${port}/api/status`, { headers: { "X-Seamless-Token": token } });
    if (!res.ok) throw new Error(res.status === 401 ? "wrong token" : `HTTP ${res.status}`);
    const j = await res.json();
    $("status").textContent = j.phones.length ? `✓ Connected to ${j.name}; phone: ${j.phones.join(", ")}` : `✓ Agent OK (${j.name}) — no phone connected yet`;
  } catch (e) {
    $("status").textContent = `✗ ${e.message}. Is the agent running?`;
  }
});
