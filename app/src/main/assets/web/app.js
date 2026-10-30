"use strict";

const $ = (id) => document.getElementById(id);
let TOKEN = localStorage.getItem("pr_token") || "";
let statusTimer = null;
let qr = null;
let curPath = "/";

// SMS chat state
let smsThreadsData = []; // [{ address, messages: [{address,date,read,body,dir}] }]
let currentPeer = null;  // selected conversation's address, or null = composing a new one

// ---- api -------------------------------------------------------------------

async function api(path, method = "GET", body = null) {
  const opts = { method, headers: {} };
  if (TOKEN) opts.headers["X-Auth"] = TOKEN;
  if (body) { opts.headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(body); }
  const res = await fetch(path, opts);
  if (res.status === 401) { showLogin(); throw new Error("unauthorized"); }
  return res.json();
}

// ---- auth ------------------------------------------------------------------

async function login() {
  $("loginErr").textContent = "";
  try {
    const res = await fetch("/api/login", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ password: $("loginPw").value }),
    });
    const j = await res.json();
    if (j.ok && j.token) { TOKEN = j.token; localStorage.setItem("pr_token", TOKEN); showApp(); }
    else $("loginErr").textContent = j.error || "Login failed";
  } catch (e) { $("loginErr").textContent = "Network error"; }
}

function logout() {
  TOKEN = ""; localStorage.removeItem("pr_token");
  if (statusTimer) clearInterval(statusTimer); statusTimer = null;
  showLogin();
}

function showLogin() { $("appView").classList.add("hidden"); $("loginView").classList.remove("hidden"); }
function showApp() {
  $("loginView").classList.add("hidden"); $("appView").classList.remove("hidden");
  loadStatus(); loadConfig(); loadClients();
  if (!statusTimer) statusTimer = setInterval(() => { loadStatus(); loadClients(); }, 5000);
}

// ---- tabs ------------------------------------------------------------------

function switchTab(name) {
  document.querySelectorAll(".tab").forEach((s) => s.classList.toggle("hidden", s.dataset.tab !== name));
  document.querySelectorAll(".tabbtn, .navitem").forEach((b) => b.classList.toggle("active", b.dataset.go === name));
  if (name === "data") { loadDataUsage(); loadSpeed(); loadSchedules(); }
  if (name === "sms") { loadSubs(); loadSms(); }
  if (name === "files") loadFiles(curPath);
}

// ---- desktop side navigation (hamburger) -----------------------------------
// Default is fully expanded ("fully expanded"); the hamburger lets the user collapse
// it to an icon-only rail, remembered across visits. Phones always use the
// bottom tab bar instead (see the @media breakpoint in style.css).

function applyNavCollapsed(collapsed) {
  $("shell").classList.toggle("nav-collapsed", collapsed);
}
function initSideNav() {
  const collapsed = localStorage.getItem("pr_nav_collapsed") === "1";
  applyNavCollapsed(collapsed);
  $("hamburgerBtn").onclick = () => {
    const next = !$("shell").classList.contains("nav-collapsed");
    applyNavCollapsed(next);
    localStorage.setItem("pr_nav_collapsed", next ? "1" : "0");
  };
}

// ---- light/dark theme (global, persisted) ----------------------------------
// The <html data-theme> attribute is set synchronously by an inline script in
// <head> (before first paint) to avoid a flash of the wrong theme; this just
// keeps the toggle button's icon and the browser-chrome color in sync and
// wires the click handler.

const SUN_ICON = '<circle cx="12" cy="12" r="5"></circle><line x1="12" y1="1" x2="12" y2="3"></line><line x1="12" y1="21" x2="12" y2="23"></line><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line><line x1="1" y1="12" x2="3" y2="12"></line><line x1="21" y1="12" x2="23" y2="12"></line><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line>';
const MOON_ICON = '<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path>';

function applyTheme(theme) {
  document.documentElement.setAttribute("data-theme", theme);
  localStorage.setItem("pr_theme", theme);
  const meta = $("themeColorMeta");
  if (meta) meta.setAttribute("content", theme === "light" ? "#f4f6f9" : "#0f1419");
  const icon = $("themeToggle").querySelector(".icon");
  // Icon shows the mode you'll switch TO (sun while dark, moon while light).
  icon.innerHTML = theme === "light" ? MOON_ICON : SUN_ICON;
}
function initTheme() {
  const current = document.documentElement.getAttribute("data-theme") || "dark";
  applyTheme(current);
  $("themeToggle").onclick = () => {
    applyTheme(document.documentElement.getAttribute("data-theme") === "light" ? "dark" : "light");
  };
}

// ---- helpers ---------------------------------------------------------------

function fmtBytes(n) {
  n = Number(n) || 0;
  const u = ["B", "KB", "MB", "GB", "TB"]; let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return n.toFixed(i === 0 ? 0 : (n >= 100 ? 0 : 1)) + " " + u[i];
}
function fmtDuration(s) {
  s = Number(s) || 0; if (s <= 0) return "—";
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  if (h > 0) return `${h}h ${m}m`; if (m > 0) return `${m}m ${sec}s`; return `${sec}s`;
}
function fmtDate(ms) {
  if (!ms) return ""; const d = new Date(Number(ms));
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (m) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[m]));
}
let toastTimer = null;
function toast(msg) {
  const t = $("toast"); t.textContent = msg; t.classList.add("show");
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove("show"), 2200);
}

// ---- home: status / clients ------------------------------------------------

async function loadStatus() {
  try {
    const s = await api("/api/status");
    const active = !!s.active;
    $("statePill").textContent = active ? "Running" : "Off";
    $("statePill").className = "pill " + (active ? "on" : "off");
    $("sState").textContent = active ? "On" : "Off";
    $("sClients").textContent = s.clientCount ?? 0;
    $("sUptime").textContent = fmtDuration(s.uptimeSeconds);
    // wlan1 tx = clients download, wlan1 rx = clients upload
    $("sDown").textContent = fmtBytes(s.txBytes);
    $("sUp").textContent = fmtBytes(s.rxBytes);
    const sw = $("hotspotSw");
    if (sw.dataset.busy !== "1") sw.checked = active;
  } catch (e) {}
  try {
    const d = await api("/api/data-usage");
    $("sRemain").textContent = d.remainingBytes >= 0 ? fmtBytes(d.remainingBytes) : "Not set";
  } catch (e) {}
}

async function loadClients() {
  try {
    const j = await api("/api/clients");
    const list = j.clients || [], el = $("clients");
    if (!list.length) { el.innerHTML = '<div class="muted">No devices</div>'; return; }
    el.innerHTML = "";
    for (const c of list) {
      const name = c.hostname && c.hostname.length ? c.hostname : (c.ip || c.mac);
      const sub = [c.ip, c.mac, c.state].filter(Boolean).join(" · ");
      const row = document.createElement("div");
      row.className = "row-item";
      row.innerHTML = `<span class="dot ${c.online ? "" : "off"}"></span>
        <div class="grow"><div class="t1">${escapeHtml(name)}</div><div class="t2">${escapeHtml(sub)}</div></div>`;
      const btn = document.createElement("button");
      btn.className = "iconbtn " + (c.blocked ? "" : "danger");
      btn.textContent = c.blocked ? "Unblock" : "Block";
      btn.onclick = () => blockClient(c.mac, !c.blocked);
      row.appendChild(btn);
      el.appendChild(row);
    }
  } catch (e) {}
}

async function blockClient(mac, block) {
  toast(block ? "Blocking…" : "Unblocking…");
  try { const r = await api("/api/clients/block", "POST", { mac, block }); toast(r.ok ? "Updated" : "Failed"); setTimeout(loadClients, 1200); }
  catch (e) { toast("Operation failed"); }
}

async function toggleHotspot(on) {
  const sw = $("hotspotSw"); sw.dataset.busy = "1"; sw.disabled = true;
  toast(on ? "Turning hotspot on…" : "Turning hotspot off…");
  try { const r = await api("/api/hotspot", "POST", { enabled: on }); toast(r.ok ? (on ? "Hotspot turned on" : "Hotspot turned off") : "Operation failed"); }
  catch (e) { toast("Operation failed"); }
  finally { sw.disabled = false; sw.dataset.busy = "0"; setTimeout(loadStatus, 800); }
}

async function rebootHotspot() {
  const b = $("rebootBtn"); b.disabled = true; toast("Restarting hotspot…");
  try { const r = await api("/api/reboot-hotspot", "POST", {}); toast(r.ok ? "Hotspot restarted" : "Failed"); }
  catch (e) { toast("Failed"); } finally { b.disabled = false; setTimeout(loadStatus, 1500); }
}

// ---- wifi ------------------------------------------------------------------

async function loadConfig() {
  try {
    const j = await api("/api/config"); const c = j.config || {};
    $("ssid").value = c.ssid || ""; $("passphrase").value = c.passphrase || "";
    $("security").value = String(c.security ?? 1); $("band").value = String(c.band ?? 1);
    $("maxClients").value = String(c.maxClients ?? 0); $("hidden").value = String(!!c.hidden);
    renderQr(c);
  } catch (e) {}
}
function renderQr(c) {
  const ssid = c.ssid || ""; if (!ssid) return;
  const sec = Number(c.security ?? 1);
  const auth = sec === 0 ? "nopass" : "WPA";
  const pass = sec === 0 ? "" : (c.passphrase || "");
  const esc = (s) => String(s).replace(/([\\;,:"])/g, "\\$1");
  const wifi = `WIFI:T:${auth};S:${esc(ssid)};P:${esc(pass)};H:${c.hidden ? "true" : "false"};;`;
  const box = $("qr"); box.innerHTML = "";
  qr = new QRCode(box, { text: wifi, width: 180, height: 180, correctLevel: QRCode.CorrectLevel.M });
}
async function saveConfig() {
  const b = $("saveCfgBtn"); b.disabled = true; b.textContent = "Applying…";
  const body = {
    ssid: $("ssid").value, passphrase: $("passphrase").value,
    security: Number($("security").value), band: Number($("band").value),
    hidden: $("hidden").value === "true", maxClients: Number($("maxClients").value) || 0,
  };
  try { const r = await api("/api/config", "POST", body); toast(r.ok ? "Saved and applied" : ("Failed: " + (r.error || ""))); await loadConfig(); setTimeout(loadStatus, 1500); }
  catch (e) { toast("Save failed"); } finally { b.disabled = false; b.textContent = "Save and apply"; }
}

// ---- data balance ----------------------------------------------------------

async function loadDataUsage() {
  try {
    const d = await api("/api/data-usage");
    $("dUsed").textContent = fmtBytes(d.usedBytes >= 0 ? d.usedBytes : 0);
    $("dRemain").textContent = d.remainingBytes >= 0 ? fmtBytes(d.remainingBytes) : "Not set";
    $("quotaGb").value = d.quotaMb ? (d.quotaMb / 1024).toString() : "";
    $("resetDay").value = d.resetDay || 1;
    const fill = $("dBarFill");
    if (d.quotaBytes > 0) {
      const pct = Math.min(100, (d.usedBytes / d.quotaBytes) * 100);
      fill.style.width = pct + "%";
      fill.className = "bar-fill" + (pct > 90 ? " danger" : pct > 70 ? " warn" : "");
    } else { fill.style.width = "0%"; }
    const upd = d.updatedAt ? ("Updated " + fmtDate(d.updatedAt)) : "Not refreshed yet";
    const cyc = d.cycleStart ? ("Cycle started " + fmtDate(d.cycleStart)) : "";
    $("dMeta").textContent = [cyc, upd].filter(Boolean).join(" · ");
  } catch (e) {}
}
async function savePlan() {
  const quotaMb = Math.round((parseFloat($("quotaGb").value) || 0) * 1024);
  const resetDay = parseInt($("resetDay").value) || 1;
  toast("Saving…");
  try { await api("/api/data-usage/config", "POST", { quotaMb, resetDay }); toast("Saved"); loadDataUsage(); loadStatus(); }
  catch (e) { toast("Failed"); }
}
async function refreshUsage() {
  toast("Refreshing…");
  try { await api("/api/data-usage/refresh", "POST", {}); toast("Refreshed"); loadDataUsage(); loadStatus(); }
  catch (e) { toast("Failed"); }
}

// ---- speed limit -----------------------------------------------------------

async function loadSpeed() {
  try { const s = await api("/api/speed-limit");
    $("downMbps").value = s.downKbps ? (s.downKbps / 1000) : 0;
    $("upMbps").value = s.upKbps ? (s.upKbps / 1000) : 0;
  } catch (e) {}
}
async function saveSpeed() {
  const downKbps = Math.round((parseFloat($("downMbps").value) || 0) * 1000);
  const upKbps = Math.round((parseFloat($("upMbps").value) || 0) * 1000);
  toast("Applying…");
  try { const r = await api("/api/speed-limit", "POST", { downKbps, upKbps }); toast(r.ok ? "Applied" : "Failed"); }
  catch (e) { toast("Failed"); }
}

// ---- schedules -------------------------------------------------------------

const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
async function loadSchedules() {
  try {
    const j = await api("/api/schedules"); const list = j.schedules || [], el = $("schedList");
    if (!list.length) { el.innerHTML = '<div class="muted">No schedules</div>'; return; }
    el.innerHTML = "";
    for (const s of list) {
      const t = String(s.hour).padStart(2, "0") + ":" + String(s.minute).padStart(2, "0");
      const days = (s.days && s.days.length) ? s.days.map((d) => DAYS[d - 1]).join(" ") : "Every day";
      const act = s.action === "on" ? "Turn hotspot on" : "Turn hotspot off";
      const row = document.createElement("div");
      row.className = "row-item";
      row.innerHTML = `<div class="grow"><div class="t1">${t} · ${act}</div><div class="t2">${days}</div></div>`;
      const sw = document.createElement("label"); sw.className = "switch";
      sw.innerHTML = `<input type="checkbox" ${s.enabled ? "checked" : ""}/><span class="slider"></span>`;
      sw.querySelector("input").onchange = (e) => api("/api/schedules/toggle", "POST", { id: s.id, enabled: e.target.checked });
      const del = document.createElement("button"); del.className = "iconbtn danger"; del.textContent = "Delete";
      del.onclick = async () => { await api("/api/schedules/delete", "POST", { id: s.id }); loadSchedules(); };
      row.appendChild(sw); row.appendChild(del); el.appendChild(row);
    }
  } catch (e) {}
}

function openSchedModal() {
  const sel = new Set();
  const bg = document.createElement("div"); bg.className = "modal-bg";
  bg.innerHTML = `<div class="modal">
    <h3>Add schedule</h3>
    <div class="row2">
      <label class="field"><span class="cap">Time</span><input id="mTime" type="time" value="08:00"/></label>
      <label class="field"><span class="cap">Action</span>
        <select id="mAct"><option value="on">Turn hotspot on</option><option value="off">Turn hotspot off</option></select></label>
    </div>
    <span class="cap">Repeat (none = every day)</span>
    <div class="days" id="mDays"></div>
    <div class="row2"><button class="btn secondary" id="mCancel">Cancel</button><button class="btn" id="mOk">Save</button></div>
  </div>`;
  document.body.appendChild(bg);
  const daysEl = bg.querySelector("#mDays");
  DAYS.forEach((d, i) => {
    const b = document.createElement("div"); b.className = "day"; b.textContent = d;
    b.onclick = () => { const n = i + 1; if (sel.has(n)) { sel.delete(n); b.classList.remove("on"); } else { sel.add(n); b.classList.add("on"); } };
    daysEl.appendChild(b);
  });
  bg.querySelector("#mCancel").onclick = () => bg.remove();
  bg.onclick = (e) => { if (e.target === bg) bg.remove(); };
  bg.querySelector("#mOk").onclick = async () => {
    const [h, m] = bg.querySelector("#mTime").value.split(":").map(Number);
    const body = { hour: h, minute: m, days: [...sel].sort(), action: bg.querySelector("#mAct").value, enabled: true };
    await api("/api/schedules", "POST", body); bg.remove(); loadSchedules();
  };
}

// ---- sms (chat-style: left = conversations, right = thread) ---------------

async function loadSubs() {
  try {
    const j = await api("/api/sms/subs"); const subs = j.subs || [], sel = $("smsSub");
    sel.innerHTML = "";
    if (!subs.length) { sel.innerHTML = '<option value="0">Default SIM</option>'; return; }
    for (const s of subs) {
      const o = document.createElement("option"); o.value = s.subId;
      o.textContent = `SIM ${s.slot + 1} ${s.name}` + (s.number ? ` (${s.number})` : "");
      sel.appendChild(o);
    }
  } catch (e) {}
}

function normalizeAddr(a) { return String(a || "").replace(/[\s\-()]/g, ""); }
function lastMsg(t) { return t.messages[t.messages.length - 1]; }

async function loadSms() {
  try {
    const [inboxRes, sentRes] = await Promise.all([
      api("/api/sms?box=inbox&limit=100"),
      api("/api/sms?box=sent&limit=100"),
    ]);
    const inbox = (inboxRes.messages || []).map((m) => ({ ...m, dir: "in" }));
    const sent = (sentRes.messages || []).map((m) => ({ ...m, dir: "out" }));

    const byKey = new Map();
    for (const m of inbox.concat(sent)) {
      const key = normalizeAddr(m.address);
      if (!byKey.has(key)) byKey.set(key, { address: m.address, messages: [] });
      byKey.get(key).messages.push(m);
    }
    const threads = [...byKey.values()];
    for (const t of threads) t.messages.sort((a, b) => a.date - b.date);
    threads.sort((a, b) => lastMsg(b).date - lastMsg(a).date);
    smsThreadsData = threads;
    renderThreadList();
    if (currentPeer) {
      const t = smsThreadsData.find((t) => normalizeAddr(t.address) === normalizeAddr(currentPeer));
      if (t) renderMessages(t);
    }
  } catch (e) {
    $("smsThreads").innerHTML = '<div class="muted" style="padding:16px">Failed to load</div>';
  }
}

// Measure the actual rendered line, including proportional fonts and emoji.
// Keep the original preview separately so growing the list can reveal more text.
const smsPreviewText = new WeakMap();
const smsPreviewSegmenter = typeof Intl.Segmenter === "function"
  ? new Intl.Segmenter(undefined, { granularity: "grapheme" }) : null;
function fitSmsPreview(el) {
  const full = smsPreviewText.get(el);
  if (full == null || !el.clientWidth) return; // Hidden tabs are measured when shown.
  el.textContent = full;
  if (el.scrollWidth <= el.clientWidth) return;
  const chars = smsPreviewSegmenter
    ? Array.from(smsPreviewSegmenter.segment(full), (s) => s.segment) : Array.from(full);
  let low = 0, high = chars.length;
  while (low < high) {
    const mid = Math.ceil((low + high) / 2);
    el.textContent = chars.slice(0, mid).join("") + "……";
    if (el.scrollWidth <= el.clientWidth) low = mid;
    else high = mid - 1;
  }
  el.textContent = chars.slice(0, low).join("") + "……";
}
const smsPreviewObserver = typeof ResizeObserver === "function"
  ? new ResizeObserver((entries) => entries.forEach(({ target }) => fitSmsPreview(target))) : null;
if (!smsPreviewObserver) {
  window.addEventListener("resize", () => document.querySelectorAll(".thread-item .snippet").forEach(fitSmsPreview));
}

function renderThreadList() {
  const el = $("smsThreads");
  if (smsPreviewObserver) smsPreviewObserver.disconnect();
  if (!smsThreadsData.length) { el.innerHTML = '<div class="muted" style="padding:16px">No messages</div>'; return; }
  el.innerHTML = "";
  for (const t of smsThreadsData) {
    const last = lastMsg(t);
    const row = document.createElement("button");
    row.className = "thread-item" + (currentPeer && normalizeAddr(t.address) === normalizeAddr(currentPeer) ? " active" : "");
    row.innerHTML = `
      <span class="avatar"><svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg></span>
      <span class="meta">
        <span class="addr">${escapeHtml(t.address)}</span>
        <span class="snippet">${last.dir === "out" ? "Me: " : ""}${escapeHtml(last.body)}</span>
      </span>
      <span class="time">${fmtDate(last.date)}</span>`;
    row.onclick = () => openThread(t.address);
    el.appendChild(row);
    const preview = row.querySelector(".snippet");
    smsPreviewText.set(preview, preview.textContent.replace(/\s+/g, " ").trim());
    fitSmsPreview(preview);
    if (smsPreviewObserver) smsPreviewObserver.observe(preview);
  }
}

function renderMessages(t) {
  $("smsPeerName").textContent = t.address;
  $("smsPeerSub").textContent = `${t.messages.length} messages`;
  const el = $("smsMessages");
  el.innerHTML = "";
  for (const m of t.messages) {
    const b = document.createElement("div");
    b.className = "msg " + (m.dir === "out" ? "out" : "in");
    b.innerHTML = `${escapeHtml(m.body)}<span class="time">${fmtDate(m.date)}</span>`;
    el.appendChild(b);
  }
  el.scrollTop = el.scrollHeight;
}

function openThread(address) {
  currentPeer = address;
  $("smsTo").value = address;
  $("smsToRow").classList.add("hidden");
  $("smsShell").classList.add("show-thread");
  const t = smsThreadsData.find((t) => normalizeAddr(t.address) === normalizeAddr(address));
  if (t) renderMessages(t);
  renderThreadList();
  $("smsBody").focus();
}

function openNewMessage() {
  currentPeer = null;
  $("smsPeerName").textContent = "New message";
  $("smsPeerSub").textContent = "";
  $("smsMessages").innerHTML = '<div class="sms-empty">Enter a number and message; a new conversation will be created after sending</div>';
  $("smsTo").value = "";
  $("smsToRow").classList.remove("hidden");
  $("smsShell").classList.add("show-thread");
  renderThreadList();
  $("smsTo").focus();
}

function backToList() {
  $("smsShell").classList.remove("show-thread");
}

async function sendSms() {
  const b = $("smsSendBtn"); b.disabled = true;
  const to = $("smsTo").value.trim();
  const body = $("smsBody").value;
  if (!to || !body.trim()) { toast("Enter a number and message"); b.disabled = false; return; }
  toast("Sending…");
  try {
    const r = await api("/api/sms/send", "POST", { subId: Number($("smsSub").value) || 0, to, body });
    if (r.ok) {
      toast("Sent");
      $("smsBody").value = "";
      currentPeer = to;
      $("smsToRow").classList.add("hidden");
      setTimeout(loadSms, 700);
    } else {
      toast("Failed: " + (r.error || ""));
    }
  } catch (e) { toast("Send failed"); }
  finally { b.disabled = false; }
}

// ---- files -----------------------------------------------------------------

async function loadFiles(path) {
  const el = $("fList"); el.innerHTML = '<div class="muted">Loading…</div>';
  try {
    const j = await api("/api/files?path=" + encodeURIComponent(path || "/"));
    if (!j.ok) { el.innerHTML = '<div class="muted">' + (j.error || "Failed to load") + "</div>"; return; }
    curPath = j.path || "/"; $("fPath").textContent = curPath;
    el.innerHTML = "";
    if (curPath !== "/") {
      const up = document.createElement("div"); up.className = "row-item";
      up.innerHTML = `<div class="grow"><div class="t1 fname dir">⬆ Parent directory</div></div>`;
      up.querySelector(".t1").onclick = () => loadFiles(parentPath(curPath));
      el.appendChild(up);
    }
    for (const f of j.entries || []) {
      const row = document.createElement("div"); row.className = "row-item";
      const icon = f.dir ? "📁" : "📄";
      const meta = f.dir ? fmtDate(f.mtime) : (fmtBytes(f.size) + " · " + fmtDate(f.mtime));
      row.innerHTML = `<div class="grow"><div class="t1 fname ${f.dir ? "dir" : ""}">${icon} ${escapeHtml(f.name)}</div><div class="t2">${meta}</div></div>`;
      const full = (curPath === "/" ? "" : curPath) + "/" + f.name;
      row.querySelector(".t1").onclick = () => { if (f.dir) loadFiles(full); else window.open("/api/files/download?token=" + TOKEN + "&path=" + encodeURIComponent(full), "_blank"); };
      const ren = document.createElement("button"); ren.className = "iconbtn"; ren.textContent = "Rename";
      ren.onclick = async () => { const nn = prompt("New name", f.name); if (nn && nn !== f.name) { await api("/api/files/rename", "POST", { path: full, newName: nn }); loadFiles(curPath); } };
      const del = document.createElement("button"); del.className = "iconbtn danger"; del.textContent = "Delete";
      del.onclick = async () => { if (confirm("Delete " + f.name + " ?")) { await api("/api/files/delete", "POST", { path: full }); loadFiles(curPath); } };
      row.appendChild(ren); row.appendChild(del); el.appendChild(row);
    }
  } catch (e) { el.innerHTML = '<div class="muted">Failed to load</div>'; }
}
function parentPath(p) { if (p === "/" || p === "") return "/"; const i = p.replace(/\/$/, "").lastIndexOf("/"); return i <= 0 ? "/" : p.substring(0, i); }

async function uploadFile(file) {
  const fd = new FormData(); fd.append("path", curPath); fd.append("file", file);
  toast("Uploading…");
  try {
    const res = await fetch("/api/files/upload", { method: "POST", headers: { "X-Auth": TOKEN }, body: fd });
    const j = await res.json(); toast(j.ok ? "Uploaded" : ("Failed: " + (j.error || ""))); loadFiles(curPath);
  } catch (e) { toast("Upload failed"); }
}

// ---- wire up ---------------------------------------------------------------

$("loginBtn").onclick = login;
$("loginPw").addEventListener("keydown", (e) => { if (e.key === "Enter") login(); });
$("logoutBtn").onclick = logout;
document.querySelectorAll(".tabbtn, .navitem").forEach((b) => (b.onclick = () => switchTab(b.dataset.go)));
initSideNav();
initTheme();

$("hotspotSw").addEventListener("change", (e) => toggleHotspot(e.target.checked));
$("rebootBtn").onclick = rebootHotspot;
$("refreshClients").onclick = loadClients;
$("saveCfgBtn").onclick = saveConfig;

$("savePlanBtn").onclick = savePlan;
$("refreshUsageBtn").onclick = refreshUsage;
$("saveSpeedBtn").onclick = saveSpeed;
$("addSchedBtn").onclick = openSchedModal;

$("smsNewBtn").onclick = openNewMessage;
$("smsBackBtn").onclick = backToList;
$("smsSendBtn").onclick = sendSms;
$("smsBody").addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendSms(); }
});

$("fMkdir").onclick = async () => { const n = prompt("Folder name"); if (n) { await api("/api/files/mkdir", "POST", { path: curPath, name: n }); loadFiles(curPath); } };
$("fUpload").onclick = () => $("fFile").click();
$("fFile").onchange = (e) => { if (e.target.files[0]) uploadFile(e.target.files[0]); e.target.value = ""; };

if (TOKEN) showApp(); else showLogin();
