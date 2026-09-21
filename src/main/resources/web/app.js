"use strict";

let STATE = null;
let LAST_JOB = null;
let LAST_OBJECTS = [];

const $ = (id) => document.getElementById(id);

function toast(msg, isErr) {
  const t = $("toast");
  t.textContent = msg;
  t.style.borderColor = isErr ? "var(--bad)" : "var(--line)";
  t.style.color = isErr ? "#ffb3b3" : "var(--ink)";
  t.style.display = "block";
  clearTimeout(toast._h);
  toast._h = setTimeout(() => (t.style.display = "none"), 4000);
}

async function api(path, method, body) {
  const opts = { method: method || "GET", headers: {} };
  if (body !== undefined) {
    opts.headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(body);
  }
  const res = await fetch(path, opts);
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) {
    throw new Error("bad JSON from server: " + text.slice(0, 200));
  }
  if (!res.ok) {
    const msg = data && data.error ? data.error.code + ": " + data.error.message : res.status;
    const err = new Error(msg);
    err.payload = data;
    throw err;
  }
  return data;
}

async function refresh() {
  try {
    STATE = await api("/api/state");
    render();
  } catch (e) { toast(String(e), true); }
}

function render() {
  $("regVersion").textContent = "registry v" + STATE.registryVersion;
  renderSelectors();
  renderPending();
  drawGraph();
  renderHistory();
}

function crsList() { return STATE.registry.crs.map((c) => c.id); }
function edges() { return STATE.registry.edges; }

function renderSelectors() {
  const ids = crsList();
  for (const [sel, prefer] of [
    ["edgeFrom", null], ["edgeTo", null],
    ["jobFrom", "WGS84"], ["jobTo", "GRID-B"]
  ]) {
    const el = $(sel);
    const prev = el.value;
    el.innerHTML = "";
    ids.forEach((id) => {
      const o = document.createElement("option");
      o.value = id; o.textContent = id;
      el.appendChild(o);
    });
    el.value = (prefer && ids.includes(prefer)) ? prefer
      : (ids.includes(prev) ? prev : ids[0] || "");
  }
}

function renderPending() {
  const box = $("pendingList");
  const pending = edges().filter((e) => e.state === "pending");
  if (!pending.length) { box.innerHTML = '<span class="muted">没有待确认边。</span>'; return; }
  box.innerHTML = "";
  pending.forEach((e) => {
    const d = document.createElement("div");
    d.className = "cand cand-no";
    const inc = e.inconsistency || {};
    d.innerHTML =
      '<b>' + esc(e.id) + '</b> ' + esc(e.sourceCrs) + ' → ' + esc(e.targetCrs) +
      ' <span class="tag pending">pending</span>' +
      '<div class="muted">抽样点 ' + fmtPoint(inc.samplePoint) +
      ' 与已有路径 ' + esc((inc.existingPath || []).join(">")) +
      ' 相差 maxAbsDiff=' + fmt(inc.maxAbsDiff) + '（容差 ' + fmt(inc.tolerance) + '）</div>' +
      '<div class="row"><div><input placeholder="确认理由（必填）" id="reason-' + esc(e.id) + '"></div>' +
      '<div style="flex:0 0 auto"><button onclick="confirmEdge(\'' + esc(e.id) + '\',\'active\')">确认启用</button>' +
      '<button onclick="confirmEdge(\'' + esc(e.id) + '\',\'disabled\')">确认停用</button></div></div>';
    box.appendChild(d);
  });
}

async function addCrs() {
  try {
    await api("/api/crs", "POST", {
      id: $("crsId").value.trim(),
      name: $("crsName").value.trim(),
      kind: $("crsKind").value,
      units: $("crsUnits").value.trim()
    });
    toast("CRS 已登记");
    await refresh();
  } catch (e) { toast(String(e), true); }
}

async function addEdge() {
  try {
    const matrix = $("edgeMatrix").value.split(",").map((s) => parseFloat(s.trim()));
    if (matrix.length !== 6 || matrix.some(isNaN)) throw new Error("矩阵需要 6 个数字");
    let coverage = null;
    const covText = $("edgeCoverage").value.trim();
    if (covText) coverage = JSON.parse(covText);
    const invSel = $("edgeInv").value;
    const body = {
      id: $("edgeId").value.trim(),
      sourceCrs: $("edgeFrom").value,
      targetCrs: $("edgeTo").value,
      transform: { type: "affine", matrix },
      accuracyX: parseFloat($("edgeAx").value),
      accuracyY: parseFloat($("edgeAy").value),
      invertible: invSel === "" ? null : invSel === "true",
      loopTolerance: parseFloat($("edgeTol").value)
    };
    if (coverage) body.coverage = coverage;
    const e = await api("/api/edges", "POST", body);
    if (e.state === "pending") toast("边已登记，但回路不一致：进入待确认状态");
    else toast("边已登记并参与默认路径");
    await refresh();
  } catch (e) { toast(String(e), true); }
}

async function confirmEdge(id, state) {
  const reasonEl = $("reason-" + id);
  const reason = reasonEl ? reasonEl.value : "";
  try {
    await api("/api/edges/" + encodeURIComponent(id) + "/confirm", "POST", { reason, state });
    toast("边 " + id + " → " + state + "（已形成新版本）");
    await refresh();
  } catch (e) { toast(String(e), true); }
}

async function reseed() {
  if (!confirm("将清空当前数据并恢复为内置演示数据，确定？")) return;
  try { await api("/api/reseed", "POST", {}); toast("已重置为演示数据"); await refresh(); }
  catch (e) { toast(String(e), true); }
}

// ------------------------------------------------------------- graph view

function nodePositions() {
  const ids = crsList();
  const cv = $("graphCanvas");
  const w = cv.width, h = cv.height;
  const cx = w / 2, cy = h / 2 + 6;
  const r = Math.min(w, h) / 2 - 64;
  const pos = {};
  ids.forEach((id, i) => {
    const ang = -Math.PI / 2 + (2 * Math.PI * i) / Math.max(ids.length, 1);
    pos[id] = { x: cx + r * Math.cos(ang), y: cy + r * Math.sin(ang) };
  });
  return pos;
}

function drawGraph(highlightPath) {
  const cv = $("graphCanvas");
  const ctx = cv.getContext("2d");
  ctx.clearRect(0, 0, cv.width, cv.height);
  if (!STATE) return;
  const pos = nodePositions();
  const edgeList = edges();

  ctx.font = "12px sans-serif";
  // edges
  edgeList.forEach((e) => {
    const p1 = pos[e.sourceCrs], p2 = pos[e.targetCrs];
    if (!p1 || !p2) return;
    const pending = e.state === "pending";
    const disabled = e.state === "disabled";
    let col = "rgba(62,207,142,0.9)";
    if (pending) col = "rgba(199,146,234,0.95)";
    if (disabled) col = "rgba(255,107,107,0.55)";
    drawArrow(ctx, p1, p2, col, pending || disabled ? [6, 5] : null);
    if (e.effectivelyInvertible) {
      drawArrow(ctx, p2, p1, col, [3, 5], true);
    }
    const mx = (p1.x + p2.x) / 2, my = (p1.y + p2.y) / 2;
    ctx.fillStyle = pending ? "#c792ea" : "#9fb4d8";
    ctx.fillText(e.id + (pending ? " ⚠" : ""), mx + 4, my - 4);
  });

  // highlighted selected path
  if (highlightPath && highlightPath.length) {
    ctx.strokeStyle = "#5aa9ff";
    ctx.lineWidth = 3;
    highlightPath.forEach((eid) => {
      const e = edgeList.find((x) => x.id === eid);
      if (!e) return;
      const a = pos[e.sourceCrs], b = pos[e.targetCrs];
      ctx.beginPath();
      ctx.arc(a.x, a.y, 20, 0, Math.PI * 2);
      ctx.stroke();
      ctx.beginPath();
      ctx.arc(b.x, b.y, 20, 0, Math.PI * 2);
      ctx.stroke();
    });
    ctx.lineWidth = 1;
  }

  // nodes
  Object.entries(pos).forEach(([id, p]) => {
    ctx.beginPath();
    ctx.arc(p.x, p.y, 16, 0, Math.PI * 2);
    ctx.fillStyle = "#1e2740";
    ctx.fill();
    ctx.strokeStyle = "#5aa9ff";
    ctx.stroke();
    ctx.fillStyle = "#e7ecf5";
    ctx.textAlign = "center";
    ctx.fillText(id, p.x, p.y + 32);
    const c = STATE.registry.crs.find((x) => x.id === id);
    ctx.fillStyle = "#93a0b8";
    ctx.font = "10px sans-serif";
    ctx.fillText(c ? c.kind : "", p.x, p.y + 45);
    ctx.font = "12px sans-serif";
    ctx.textAlign = "left";
  });
}

function drawArrow(ctx, a, b, color, dash, thin) {
  const dx = b.x - a.x, dy = b.y - a.y;
  const len = Math.hypot(dx, dy) || 1;
  const ux = dx / len, uy = dy / len;
  const gap = 18;
  const sx = a.x + ux * gap, sy = a.y + uy * gap;
  const tx = b.x - ux * gap, ty = b.y - uy * gap;
  ctx.save();
  ctx.strokeStyle = color;
  ctx.lineWidth = thin ? 1 : 2;
  if (dash) ctx.setLineDash(dash);
  ctx.beginPath();
  ctx.moveTo(sx, sy);
  ctx.lineTo(tx, ty);
  ctx.stroke();
  const ah = thin ? 6 : 9;
  ctx.beginPath();
  ctx.moveTo(tx, ty);
  ctx.lineTo(tx - ux * ah - uy * ah * 0.55, ty - uy * ah + ux * ah * 0.55);
  ctx.lineTo(tx - ux * ah + uy * ah * 0.55, ty - uy * ah - ux * ah * 0.55);
  ctx.closePath();
  ctx.fillStyle = color;
  ctx.fill();
  ctx.restore();
}

// --------------------------------------------------------------- helpers

function esc(s) {
  return String(s == null ? "" : s)
    .replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
}
function fmt(v) {
  if (v == null) return "—";
  if (typeof v !== "number") return esc(v);
  if (!isFinite(v)) return String(v);
  if (v !== 0 && Math.abs(v) < 1e-12) return "0";
  return (Math.abs(v) >= 1e-4 && Math.abs(v) < 1e12)
    ? String(Math.round(v * 1e10) / 1e10)
    : v.toExponential(4);
}
function fmtPoint(p) {
  if (!p) return "—";
  return "(" + fmt(p.x) + "," + fmt(p.y) + ")";
}

// ------------------------------------------------------------- run a job

function extractObjects(text) {
  const data = JSON.parse(text);
  const objs = [];
  function pushGeom(g, id) {
    if (!g || !g.type) return;
    if (g.type === "GeometryCollection") (g.geometries || []).forEach((x) => pushGeom(x, id));
    else objs.push(id == null ? g : { id, geometry: g });
  }
  if (Array.isArray(data)) data.forEach((d) => {
    if (d.type === "Feature") pushGeom(d.geometry, d.id || (d.properties && d.properties.id));
    else pushGeom(d);
  });
  else if (data.type === "FeatureCollection")
    (data.features || []).forEach((f) => pushGeom(f.geometry, f.id || (f.properties && f.properties.id)));
  else if (data.type === "Feature") pushGeom(data.geometry, data.id || (data.properties && data.properties.id));
  else pushGeom(data);
  if (!objs.length) throw new Error("没有可变换的几何对象");
  return objs;
}

async function runJob() {
  try {
    LAST_OBJECTS = extractObjects($("geoInput").value);
    const body = {
      sourceCrs: $("jobFrom").value,
      targetCrs: $("jobTo").value,
      objects: LAST_OBJECTS
    };
    const job = await api("/api/jobs", "POST", body);
    LAST_JOB = job;
    renderJob(job);
    await refresh();
  } catch (e) { toast(String(e), true); }
}

function renderJob(job) {
  const s = job.summary;
  $("jobSummary").innerHTML =
    '作业 <b>' + esc(job.jobId) + '</b> · 注册表 v' + job.registryVersion +
    ' · 指纹 <span class="pill">' + esc(job.fingerprint.slice(0, 16)) + '…</span>' +
    ' <span class="ok">成功 ' + s.succeeded + '</span> / <span class="fail">失败 ' + s.failed +
    '</span> / 共 ' + s.total;
  const ok = job.items.filter((i) => i.status === "success");
  drawOverlay(job.items);
  const box = $("candidateInfo");
  box.innerHTML = "";
  if (!ok.length) {
    box.innerHTML = '<div class="err-box">没有成功的对象。展开失败项诊断：</div>';
  }
  job.items.forEach((item) => {
    const d = document.createElement("div");
    d.className = "cand " + (item.status === "success" ? "cand-ok" : "cand-no");
    let html = '<b>#' + item.index + (item.id ? " " + esc(item.id) : "") + "</b> " +
      esc(job.sourceCrs) + " → " + esc(job.targetCrs) + " ";
    if (item.status === "success") {
      html += '<span class="tag active">success</span> 选中链: <b>' + esc(item.selectedPath) +
        '</b> 累计误差上界 x=' + fmt(item.cumulativeError.x) + " y=" + fmt(item.cumulativeError.y) +
        " max=" + fmt(item.cumulativeError.max) +
        ' <span class="pill">(点击查看候选比较)</span>';
    } else {
      html += '<span class="tag disabled">failed</span> <span class="fail">' +
        esc(item.error.code) + "</span>: " + esc(item.error.message);
    }
    d.innerHTML = html;
    d.onclick = () => showCandidates(item);
    box.appendChild(d);
  });
}

function reasonText(reason, detail) {
  switch (reason) {
    case "out-of-coverage":
      return "点 " + fmtPoint(detail && detail.point) + " 不在边 " + esc(detail && detail.edgeId) +
        "（" + esc(detail && detail.direction) + "）的有效范围 " + JSON.stringify(detail && detail.coverage);
    case "latitude-out-of-range":
      return "变换后纬度越界: " + fmt(detail && detail.latitude);
    case "non-finite-output":
      return "边 " + esc(detail && detail.edgeId) + " 产生了非有限结果";
    default:
      return reason;
  }
}

function showCandidates(item) {
  drawGraph(item.status === "success" ? item.selectedEdgeIds : null);
  const w = window.open("", "_blank", "width=760,height=720");
  if (!w) { toast("浏览器拦截了弹窗，候选详情将打印到控制台"); console.log(item.candidates); return; }
  const rows = item.candidates || [];
  const body = rows.map((c, i) => {
    const best = item.status === "success" && c.path === item.selectedPath;
    const cls = c.feasibleAtAllPoints ? "cand-ok" : "cand-no";
    let extra;
    if (c.feasibleAtAllPoints) {
      extra = "各点最差累计误差 x=" + fmt(c.worstCumulativeError.x) +
        " y=" + fmt(c.worstCumulativeError.y) + " max=" + fmt(c.worstCumulativeError.max);
    } else if (c.firstFailure) {
      const d = c.firstFailure.detail || {};
      extra = '<span class="fail">排除 @点' + c.firstFailure.pointIndex + "：" +
        reasonText(c.firstFailure.reason, d) + "</span>";
    } else extra = "不可行";
    const steps = (c.steps || []).map((st) =>
      "<tr><td>" + esc(st.edgeId) + " (" + esc(st.direction) + ")</td><td>" +
      esc(st.fromCrs) + "→" + esc(st.toCrs) + "</td><td>" +
      "累计误差 (" + fmt(st.cumulativeError.x) + "," + fmt(st.cumulativeError.y) + ")</td><td>" +
      fmtPoint(st.pointAfterStep) + "</td></tr>").join("");
    return '<div class="cand ' + cls + (best ? " cand-best" : "") + '"><b>候选 ' + (i + 1) + ": " +
      esc(c.path) + "</b> " + (best ? '<span class="tag active">被选中</span>' : "") +
      "<div>" + extra + "</div>" +
      (steps ? '<table><tr><th>边</th><th>步骤</th><th>累计误差</th><th>步后点</th></tr>' + steps + "</table>" : "") +
      "</div>";
  }).join("");
  w.document.write("<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'><title>候选路径解释</title>" +
    "<style>body{font:13px/1.5 -apple-system,'PingFang SC',sans-serif;margin:18px;background:#0f1420;color:#e7ecf5}" +
    ".cand{background:#1e2740;border-radius:6px;padding:8px 10px;margin:8px 0}" +
    ".cand-ok{border-left:3px solid #3ecf8e}.cand-no{border-left:3px solid #ff6b6b}" +
    ".cand-best{outline:1px solid #5aa9ff}table{width:100%;border-collapse:collapse;margin-top:6px}" +
    "td,th{border-bottom:1px solid #2b3650;padding:3px 5px;text-align:left;font-size:11.5px}" +
    ".fail{color:#ff8a8a}</style></head><body><h2>候选路径为何被接受 / 排除</h2>" +
    "<p>选择顺序：① 覆盖全部顶点 → ② 累计误差上界最小 → ③ 边 id 字典序稳定平局</p>" +
    body + "</body></html>");
  w.document.close();
}

// ------------------------------------------------------- overlay canvas

function walkCoords(geom, fn) {
  const t = geom.type, c = geom.coordinates;
  const pt = (p) => fn(p[0], p[1]);
  const line = (l) => l.forEach(pt);
  if (t === "Point") pt(c);
  else if (t === "MultiPoint" || t === "LineString") line(c);
  else if (t === "MultiLineString") c.forEach(line);
  else if (t === "Polygon") c.forEach(line);
  else if (t === "MultiPolygon") c.forEach((poly) => poly.forEach(line));
}

function allPointsOf(items) {
  const pts = [];
  items.forEach((it) => {
    if (it.input) walkCoords(it.input, (x, y) => pts.push([x, y]));
    if (it.output) walkCoords(it.output, (x, y) => pts.push([x, y]));
  });
  return pts;
}

function drawOverlay(items) {
  const cv = $("overCanvas"), ctx = cv.getContext("2d");
  ctx.clearRect(0, 0, cv.width, cv.height);
  const pts = allPointsOf(items);
  if (!pts.length) return;
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  pts.forEach(([x, y]) => { minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                            minY = Math.min(minY, y); maxY = Math.max(maxY, y); });
  if (maxX - minX < 1e-9) { minX -= 1; maxX += 1; }
  if (maxY - minY < 1e-9) { minY -= 1; maxY += 1; }
  const pad = 30;
  const sx = (x) => pad + ((x - minX) / (maxX - minX)) * (cv.width - 2 * pad);
  const sy = (y) => cv.height - pad - ((y - minY) / (maxY - minY)) * (cv.height - 2 * pad);

  items.forEach((it) => {
    if (it.input) drawGeometry(ctx, it.input, sx, sy, "#5aa9ff", false);
    if (it.output) drawGeometry(ctx, it.output, sx, sy, "#ffb454", true);
  });

  ctx.fillStyle = "#93a0b8";
  ctx.font = "11px monospace";
  ctx.fillText("x[" + fmt(minX) + " … " + fmt(maxX) + "]  y[" + fmt(minY) + " … " + fmt(maxY) + "]", 8, 14);
  $("overInfo").textContent = "共享同一坐标尺度；蓝=" + ($("jobFrom").value) + " 橙=" + ($("jobTo").value);
}

function drawGeometry(ctx, geom, sx, sy, color, filled) {
  ctx.strokeStyle = color; ctx.fillStyle = color; ctx.lineWidth = 2;
  const pt = (p, r) => { ctx.beginPath(); ctx.arc(sx(p[0]), sy(p[1]), r == null ? 3.5 : r, 0, Math.PI * 2); ctx.fill(); };
  const pathFromLine = (line) => {
    ctx.beginPath();
    line.forEach((p, i) => { const X = sx(p[0]), Y = sy(p[1]); i ? ctx.lineTo(X, Y) : ctx.moveTo(X, Y); });
  };
  const t = geom.type, c = geom.coordinates;
  if (t === "Point") pt(c, 5);
  else if (t === "MultiPoint") c.forEach((p) => pt(p));
  else if (t === "LineString") { pathFromLine(c); ctx.stroke(); c.forEach((p) => pt(p, 2.5)); }
  else if (t === "MultiLineString") c.forEach((l) => { pathFromLine(l); ctx.stroke(); });
  else if (t === "Polygon") {
    c.forEach((ring, ri) => {
      pathFromLine(ring);
      if (ri === 0 && filled) { ctx.globalAlpha = 0.12; ctx.fill(); ctx.globalAlpha = 1; }
      ctx.stroke();
    });
  } else if (t === "MultiPolygon") {
    c.forEach((poly) => poly.forEach((ring, ri) => {
      pathFromLine(ring);
      if (ri === 0 && filled) { ctx.globalAlpha = 0.12; ctx.fill(); ctx.globalAlpha = 1; }
      ctx.stroke();
    }));
  }
}

// ------------------------------------------------------------ history etc

async function renderHistory() {
  try {
    const data = await api("/api/jobs");
    const box = $("jobHistory");
    if (!data.jobs.length) { box.innerHTML = '<span class="muted">尚无作业。</span>'; return; }
    box.innerHTML = '<table><tr><th>作业</th><th>链路方向</th><th>版本</th><th>结果</th><th>指纹</th><th></th></tr>' +
      data.jobs.slice().reverse().map((j) => {
        const s = j.summary;
        return "<tr><td>" + esc(j.jobId) + "</td><td>" + esc(j.sourceCrs) + "→" + esc(j.targetCrs) +
          "</td><td>v" + j.registryVersion +
          '</td><td><span class="ok">' + s.succeeded + '</span>/<span class="fail">' + s.failed +
          '</span>/' + s.total + '</td><td class="pill">' + esc(j.fingerprint.slice(0, 12)) +
          '…</td><td><button onclick="viewJob(\'' + esc(j.jobId) + "')\">查看</button></td></tr>";
      }).join("") + "</table>";
  } catch (e) { /* non-fatal */ }
}

async function viewJob(id) {
  const job = await api("/api/jobs/" + encodeURIComponent(id));
  LAST_JOB = job;
  renderJob(job);
  toast("已载入历史作业 " + id + "（绑定 v" + job.registryVersion + "）");
}

function doExport() {
  window.location.href = "/api/export";
}

async function doImport() {
  const text = prompt("粘贴此前导出的 JSON 文档（将整体替换当前状态）");
  if (!text) return;
  try {
    const doc = JSON.parse(text);
    const r = await api("/api/import", "POST", doc);
    toast("导入完成：作业 " + r.jobs + " 个，注册表 v" + r.registryVersion);
    await refresh();
  } catch (e) { toast(String(e), true); }
}

refresh();
