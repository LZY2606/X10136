/* 坐标变换注册站 front-end: plain fetch + SVG graph + canvas overlay. */
let state = { version: 0, crs: [], edges: [], jobs: [] };
let lastInput = null, lastOutput = null;

async function api(path, method, body) {
  const res = await fetch(path, {
    method: method || 'GET',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await res.text();
  let data;
  try { data = JSON.parse(text); } catch (e) { data = { error: text }; }
  return { status: res.status, data };
}

function show(id, obj) {
  document.getElementById(id).textContent =
    typeof obj === 'string' ? obj : JSON.stringify(obj, null, 2);
}

async function refresh() {
  const { data } = await api('/api/state');
  state = data;
  document.getElementById('version').textContent = data.version;
  renderGraph();
  renderEdges();
  renderSelectors();
  const log = await api('/api/export');
  document.getElementById('logOut').textContent =
    (log.data.log || []).map(e => 'v' + e.version + ' ' + e.action + ' — ' + e.detail).join('\n');
}

function renderSelectors() {
  for (const id of ['srcSel', 'dstSel']) {
    const sel = document.getElementById(id);
    sel.innerHTML = '';
    for (const c of state.crs) {
      const o = document.createElement('option');
      o.value = c.id; o.textContent = c.id;
      sel.appendChild(o);
    }
  }
}

function renderGraph() {
  const svg = document.getElementById('graph');
  const W = 600, H = 260, cx = W / 2, cy = H / 2, R = 95;
  const nodes = state.crs;
  const pos = {};
  nodes.forEach((c, i) => {
    const a = -Math.PI / 2 + 2 * Math.PI * i / Math.max(nodes.length, 1);
    pos[c.id] = [cx + R * Math.cos(a), cy + R * Math.sin(a)];
  });
  let s = '<defs>' +
    '<marker id="arr" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto">' +
    '<path d="M0,0 L7,3 L0,6 z" fill="#556"/></marker>' +
    '<marker id="arrP" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto">' +
    '<path d="M0,0 L7,3 L0,6 z" fill="#b26a00"/></marker></defs>';
  state.edges.forEach((e, i) => {
    const a = pos[e.src], b = pos[e.dst];
    if (!a || !b) return;
    const mx = (a[0] + b[0]) / 2, my = (a[1] + b[1]) / 2;
    const dx = b[0] - a[0], dy = b[1] - a[1];
    const off = 14 + (i % 3) * 10;
    const nx = -dy, ny = dx;
    const len = Math.hypot(nx, ny) || 1;
    const qx = mx + nx / len * off, qy = my + ny / len * off;
    const pending = e.status === 'PENDING';
    s += `<path d="M${a[0]},${a[1]} Q${qx},${qy} ${b[0]},${b[1]}" fill="none" ` +
      `stroke="${pending ? '#b26a00' : '#556'}" stroke-width="1.4" ` +
      `${pending ? 'stroke-dasharray="5,4"' : ''} marker-end="url(#${pending ? 'arrP' : 'arr'})"/>`;
    s += `<text x="${qx}" y="${qy}" font-size="10" fill="${pending ? '#b26a00' : '#445'}" text-anchor="middle">` +
      `${e.id} (±${e.accuracy})${e.invertible ? ' ⇄' : ''}</text>`;
  });
  for (const c of nodes) {
    const p = pos[c.id];
    s += `<circle cx="${p[0]}" cy="${p[1]}" r="22" fill="#1f3a5f"/>`;
    s += `<text x="${p[0]}" y="${p[1] + 3}" font-size="10" fill="#fff" text-anchor="middle">${c.id}</text>`;
  }
  svg.innerHTML = s;
}

function renderEdges() {
  const div = document.getElementById('edgeList');
  let html = '<table><tr><th>边</th><th>方向</th><th>有效范围</th><th>精度</th><th>可逆</th><th>状态</th><th></th></tr>';
  for (const e of state.edges) {
    const r = e.region;
    html += `<tr><td>${e.id}</td><td>${e.src} → ${e.dst}</td>` +
      `<td>[${r.minLon},${r.minLat}] ~ [${r.maxLon},${r.maxLat}]${r.minLon > r.maxLon ? ' (跨反经线)' : ''}</td>` +
      `<td>±${e.accuracy}</td><td>${e.invertible ? '是' : '否'}</td>` +
      `<td class="${e.status === 'PENDING' ? 'pending' : 'active'}">${e.status}</td>` +
      `<td>${e.status === 'PENDING' ? `<button class="ghost" onclick="confirmEdge('${e.id}')">确认</button>` : ''}</td></tr>`;
    if (e.note) html += `<tr><td></td><td colspan="6" style="color:#b26a00">${e.note}</td></tr>`;
    if (e.confirmReason) html += `<tr><td></td><td colspan="6" style="color:#1a7f37">确认理由: ${e.confirmReason}</td></tr>`;
  }
  div.innerHTML = html + '</table>';
}

async function addCrs() {
  const id = document.getElementById('crsId').value.trim();
  const name = document.getElementById('crsName').value.trim();
  const { data } = await api('/api/crs', 'POST', { id, name });
  show('regOut', data);
  refresh();
}

async function loadSample() {
  const sample = [
    { path: '/api/crs', body: { id: 'WGS84', name: 'WGS 84' } },
    { path: '/api/crs', body: { id: 'GCJ02', name: 'GCJ-02 火星坐标' } },
    { path: '/api/crs', body: { id: 'BD09', name: 'BD-09 百度坐标' } },
    { path: '/api/edges', body: {
      id: 'wgs-gcj', src: 'WGS84', dst: 'GCJ02',
      region: { minLon: 70, minLat: 0, maxLon: 140, maxLat: 60 },
      accuracy: 0.5, affine: [1, 0, 0.006, 0, 1, -0.001], invertible: true } },
    { path: '/api/edges', body: {
      id: 'gcj-bd', src: 'GCJ02', dst: 'BD09',
      region: { minLon: 70, minLat: 0, maxLon: 140, maxLat: 60 },
      accuracy: 0.3, affine: [1, 0, 0.003, 0, 1, 0.002], invertible: true } },
    { path: '/api/edges', body: {
      id: 'datum-local', src: 'GCJ02', dst: 'BD09',
      region: { minLon: 170, minLat: 0, maxLon: -170, maxLat: 60 },
      accuracy: 0.4, affine: [1, 0, 0.003, 0, 1, 0.002], invertible: false } }
  ];
  for (const s of sample) {
    const { status, data } = await api(s.path, 'POST', s.body);
    if (status >= 400) { show('regOut', data); break; }
  }
  refresh();
}

async function addEdge() {
  let body;
  try { body = JSON.parse(document.getElementById('edgeJson').value); }
  catch (e) { show('regOut', 'JSON 解析失败: ' + e.message); return; }
  const { data } = await api('/api/edges', 'POST', body);
  show('regOut', data);
  refresh();
}

async function confirmEdge(id) {
  const reason = prompt('确认理由 (required):');
  if (!reason) return;
  const { data } = await api(`/api/edges/${id}/confirm`, 'POST', { reason });
  show('regOut', data);
  refresh();
}

async function runTransform() {
  let geom;
  try { geom = JSON.parse(document.getElementById('geojson').value); }
  catch (e) { show('transformOut', 'GeoJSON 解析失败: ' + e.message); return; }
  const src = document.getElementById('srcSel').value;
  const dst = document.getElementById('dstSel').value;
  const { status, data } = await api('/api/transform', 'POST', { geometry: geom, src, dst });
  show('transformOut', data);
  renderCandidates(data);
  lastInput = geom;
  lastOutput = data.result || null;
  drawCanvas();
}

function renderCandidates(data) {
  const div = document.getElementById('candidates');
  if (!data.candidates) { div.innerHTML = ''; return; }
  let html = '<table><tr><th>候选路径</th><th>累计误差</th><th>覆盖</th><th>结论</th></tr>';
  for (const c of data.candidates) {
    const label = c.path.map(s => (s.direction === 'inverse' ? '⁻¹' : '') + s.edge).join(' → ') || '(恒等)';
    html += `<tr><td>${label}</td><td>${c.cumulativeError}</td><td>${c.covered ? '是' : '否'}</td>` +
      `<td class="${c.accepted ? 'ok' : 'failed'}">${c.reason}</td></tr>`;
  }
  div.innerHTML = html + '</table>';
}

function collectPoints(geom, out) {
  if (!geom) return;
  const t = geom.type, c = geom.coordinates;
  if (t === 'Point') out.push(c);
  else if (t === 'LineString') c.forEach(p => out.push(p));
  else if (t === 'Polygon') c.forEach(r => r.forEach(p => out.push(p)));
}

function drawGeom(ctx, geom, color, box) {
  if (!geom) return;
  const px = p => [(p[0] - box.x0) * box.kx + box.pad, box.h - (p[1] - box.y0) * box.ky - box.pad];
  ctx.strokeStyle = color; ctx.fillStyle = color; ctx.lineWidth = 1.6;
  const t = geom.type, c = geom.coordinates;
  if (t === 'Point') {
    const [x, y] = px(c);
    ctx.beginPath(); ctx.arc(x, y, 4, 0, 7); ctx.fill();
  } else if (t === 'LineString' || t === 'Polygon') {
    const lines = t === 'LineString' ? [c] : c;
    for (const line of lines) {
      ctx.beginPath();
      line.forEach((p, i) => { const [x, y] = px(p); i ? ctx.lineTo(x, y) : ctx.moveTo(x, y); });
      ctx.stroke();
    }
  }
}

function drawCanvas() {
  const cv = document.getElementById('canvas');
  const ctx = cv.getContext('2d');
  ctx.clearRect(0, 0, cv.width, cv.height);
  const pts = [];
  collectPoints(lastInput, pts);
  collectPoints(lastOutput, pts);
  if (!pts.length) return;
  let x0 = Infinity, x1 = -Infinity, y0 = Infinity, y1 = -Infinity;
  for (const p of pts) {
    x0 = Math.min(x0, p[0]); x1 = Math.max(x1, p[0]);
    y0 = Math.min(y0, p[1]); y1 = Math.max(y1, p[1]);
  }
  const pad = 24;
  const kx = (cv.width - 2 * pad) / Math.max(x1 - x0, 1e-9);
  const ky = (cv.height - 2 * pad) / Math.max(y1 - y0, 1e-9);
  const box = { x0, y0, kx, ky, pad, h: cv.height };
  drawGeom(ctx, lastInput, '#1f6feb', box);
  drawGeom(ctx, lastOutput, '#d63031', box);
}

async function runJob() {
  let body;
  try { body = JSON.parse(document.getElementById('jobJson').value); }
  catch (e) { show('jobOut', 'JSON 解析失败: ' + e.message); return; }
  const { data } = await api('/api/jobs', 'POST', body);
  show('jobOut', data);
  refresh();
}

async function doExport() {
  const { data } = await api('/api/export');
  show('storeOut', data);
}

async function doImport() {
  let body;
  try { body = JSON.parse(document.getElementById('storeOut').textContent); }
  catch (e) { show('storeOut', '先在上方粘贴或生成导出 JSON'); return; }
  const { data } = await api('/api/import', 'POST', body);
  show('storeOut', data);
  refresh();
}

refresh();
