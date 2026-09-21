let registry = { crs: [], edges: [], pendingEdges: [] };
let lastJob = null;
let graphNodes = [];

const $ = (id) => document.getElementById(id);
const api = async (path, options = {}) => {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
    body: options.body ? JSON.stringify(options.body) : undefined
  });
  const text = await response.text();
  const data = text ? JSON.parse(text) : {};
  if (!response.ok) throw new Error(data.error || ('HTTP ' + response.status));
  return data;
};

async function refresh() {
  registry = await api('/api/registry');
  $('status').textContent = `已连接 · ${registry.crs.length} 个 CRS · ${registry.edges.length} 条有效边`;
  $('registryVersion').textContent = registry.version;
  $('registryFingerprint').textContent = registry.fingerprint.slice(0, 18) + '…';
  fillSelects();
  drawGraph();
  renderPending();
  loadJobs();
}

function fillSelects() {
  const ids = registry.crs.map(c => c.id);
  for (const select of [$('sourceCrs'), $('targetCrs'), $('edgeFrom'), $('edgeTo')]) {
    const old = select.value;
    select.innerHTML = ids.map(id => `<option>${id}</option>`).join('');
    if (ids.includes(old)) select.value = old;
  }
  $('sourceCrs').value = ids.includes('WGS84_GEO') ? 'WGS84_GEO' : ids[0] || '';
  $('targetCrs').value = ids.includes('LOCAL_GRID') ? 'LOCAL_GRID' : ids[1] || '';
  $('edgeFrom').value = ids.includes('WGS84_GEO') ? 'WGS84_GEO' : ids[0] || '';
  $('edgeTo').value = ids.includes('JGD2011_GEO') ? 'JGD2011_GEO' : ids[1] || '';
}

function geometryFromText() {
  const parsed = JSON.parse($('geoInput').value);
  return parsed.type === 'Feature' ? parsed.geometry
    : parsed.type === 'FeatureCollection' ? parsed.features[0].geometry : parsed;
}

async function onlyPlan() {
  $('message').textContent = '';
  try {
    const result = await api('/api/plan', {
      method: 'POST',
      body: {
        sourceCrs: $('sourceCrs').value,
        targetCrs: $('targetCrs').value,
        geometry: geometryFromText()
      }
    });
    renderCandidates(result);
    if (!result.eligible) $('message').textContent = result.diagnostic;
  } catch (error) {
    $('message').textContent = error.message;
  }
}

async function submitJob() {
  $('message').textContent = '正在执行……';
  try {
    const parsed = JSON.parse($('geoInput').value);
    const geometries = parsed.type === 'FeatureCollection'
      ? parsed.features.map(f => f.geometry)
      : [parsed.type === 'Feature' ? parsed.geometry : parsed];
    const items = geometries.map((geometry, index) => ({
      id: 'geometry-' + index,
      sourceCrs: $('sourceCrs').value,
      targetCrs: $('targetCrs').value,
      geometry
    }));
    lastJob = await api('/api/jobs', { method: 'POST', body: { label: '网页作业', items } });
    $('message').textContent = `作业 ${lastJob.id}：${lastJob.status}，成功 ${lastJob.successCount}，失败 ${lastJob.failureCount}`;
    renderJob(lastJob);
    loadJobs();
  } catch (error) {
    $('message').textContent = error.message;
  }
}

function renderCandidates(decision) {
  const target = $('candidates');
  if (!decision.candidates || decision.candidates.length === 0) {
    target.innerHTML = '<div class="path">没有可枚举到目标的候选路径。</div>';
  } else {
    target.innerHTML = decision.candidates.map((candidate, index) => {
      const path = (candidate.hops || []).map(hop =>
        `${hop.fromCrs} → ${hop.edgeId}${hop.direction === 'reverse' ? '⁻¹' : ''} → ${hop.toCrs}`
      ).join('<br>');
      const cls = index === 0 && decision.eligible ? 'path selected'
        : candidate.status === 'EXCLUDED_COVERAGE' ? 'path excluded' : 'path';
      return `<div class="${cls}"><b>${candidate.status}</b> · 误差上界 ${formatNumber(candidate.errorBound)}<br>${path || '零边恒等路径'}<br><span class="hint">${candidate.reason || ''}</span></div>`;
    }).join('');
  }
  const rejected = (decision.rejectedArcs || []).map(arc =>
    `<div class="path pending"><b>${arc.code}</b> ${arc.edgeId} ${arc.direction}: ${arc.reason}</div>`
  ).join('');
  target.insertAdjacentHTML('beforeend', rejected);
}

function formatNumber(value) {
  if (!Number.isFinite(value)) return String(value);
  return value === null ? '不适用（已排除）' : Number(value.toPrecision(12)).toString();
}

function allCoordinates(geometry) {
  if (!geometry) return [];
  if (geometry.type === 'Point') return [geometry.coordinates];
  if (geometry.type === 'LineString') return geometry.coordinates;
  return geometry.coordinates.flat();
}

function renderJob(job) {
  const success = job.items.find(item => item.status === 'SUCCESS');
  if (success) renderCandidates(success.pathDecision);
  else if (job.items[0] && job.items[0].pathDecision) renderCandidates(job.items[0].pathDecision);
  drawItems(job.items);
  const diagnostics = job.items.filter(i => i.status === 'FAILED')
    .map(i => `#${i.index} ${i.id}: ${i.errorCode} — ${i.diagnostic}`).join('\n');
  if (diagnostics) $('message').textContent += '\n' + diagnostics;
}

function drawItems(items) {
  const canvas = $('canvas');
  const ctx = canvas.getContext('2d');
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  const sets = [];
  for (const item of items) {
    if (item.input) sets.push({ geometry: item.input, color: '#78a9ff', width: 2 });
    if (item.output) sets.push({ geometry: item.output, color: '#65d6ad', width: 3 });
  }
  const points = sets.flatMap(set => allCoordinates(set.geometry));
  if (!points.length) {
    $('canvasInfo').textContent = '没有可绘制坐标';
    return;
  }
  const bbox = geographicBBox(points);
  drawGrid(ctx, bbox);
  for (const set of sets) drawGeometry(ctx, set.geometry, bbox, set.color, set.width);
  for (const item of items) {
    if (item.status === 'FAILED') {
      for (const point of allCoordinates(item.input)) drawPoint(ctx, point, bbox, '#ff6b7a', 5);
    }
  }
  $('canvasInfo').textContent = `范围 x:${formatNumber(bbox.minX)}..${formatNumber(bbox.maxX)} y:${formatNumber(bbox.minY)}..${formatNumber(bbox.maxY)}`;
}

function geographicBBox(points) {
  let minY = Math.min(...points.map(p => p[1]));
  let maxY = Math.max(...points.map(p => p[1]));
  const crossing = points.some((p, i) => i > 0 && Math.abs(p[0] - points[i - 1][0]) > 180);
  if (crossing) {
    return { minX: Math.max(...points.map(p => p[0])), maxX: Math.min(...points.map(p => p[0])), minY, maxY, crossing };
  }
  return {
    minX: Math.min(...points.map(p => p[0])), maxX: Math.max(...points.map(p => p[0])),
    minY, maxY, crossing: false
  };
}

function project(point, bbox) {
  const pad = 34;
  const width = 520 - pad * 2;
  const height = 410 - pad * 2;
  let x;
  if (bbox.crossing) {
    const span = (180 - bbox.minX) + (bbox.maxX + 180);
    const unwrapped = point[0] >= bbox.minX || point[0] <= bbox.maxX
      ? (point[0] >= bbox.minX ? point[0] : point[0] + 360) : point[0];
    x = pad + ((unwrapped - bbox.minX) / span) * width;
  } else {
    const dx = bbox.maxX - bbox.minX || 1;
    x = pad + ((point[0] - bbox.minX) / dx) * width;
  }
  const dy = bbox.maxY - bbox.minY || 1;
  const y = pad + ((bbox.maxY - point[1]) / dy) * height;
  return [x, y];
}

function drawGrid(ctx, bbox) {
  ctx.strokeStyle = '#263650';
  ctx.lineWidth = 1;
  for (let i = 0; i <= 8; i++) {
    const x = 34 + i * (452 / 8);
    ctx.beginPath(); ctx.moveTo(x, 34); ctx.lineTo(x, 376); ctx.stroke();
    const y = 34 + i * (342 / 8);
    ctx.beginPath(); ctx.moveTo(34, y); ctx.lineTo(486, y); ctx.stroke();
  }
  ctx.fillStyle = '#9fb0cc';
  ctx.font = '10px monospace';
  ctx.fillText(formatNumber(bbox.minX), 34, 394);
  ctx.fillText(formatNumber(bbox.maxX), 430, 394);
}

function drawGeometry(ctx, geometry, bbox, color, width) {
  ctx.strokeStyle = color;
  ctx.fillStyle = color;
  ctx.lineWidth = width;
  if (geometry.type === 'Point') return drawPoint(ctx, geometry.coordinates, bbox, color, 5);
  const lines = geometry.type === 'LineString' ? [geometry.coordinates] : geometry.coordinates;
  for (const line of lines) {
    ctx.beginPath();
    let pen = false;
    line.forEach((point, index) => {
      if (index > 0 && Math.abs(point[0] - line[index - 1][0]) > 180) pen = false;
      const [x, y] = project(point, bbox);
      if (!pen) { ctx.moveTo(x, y); pen = true; } else ctx.lineTo(x, y);
    });
    ctx.stroke();
    line.forEach(point => drawPoint(ctx, point, bbox, color, 2.5));
  }
}

function drawPoint(ctx, point, bbox, color, radius) {
  const [x, y] = project(point, bbox);
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.arc(x, y, radius, 0, Math.PI * 2);
  ctx.fill();
}

function drawGraph() {
  const svg = $('graph');
  const width = 920, height = 390;
  const crses = registry.crs;
  graphNodes = crses.map((crs, index) => {
    const angle = (Math.PI * 2 * index) / Math.max(1, crses.length) - Math.PI / 2;
    return { ...crs, x: width / 2 + Math.cos(angle) * 285, y: height / 2 + Math.sin(angle) * 145 };
  });
  const byId = Object.fromEntries(graphNodes.map(n => [n.id, n]));
  const defs = '<defs><marker id="arrow" markerWidth="10" markerHeight="10" refX="9" refY="3" orient="auto" markerUnits="strokeWidth"><path d="M0,0 L0,6 L9,3 z" fill="#8ab4f8"/></marker></defs>';
  const edges = registry.edges.map(edge => {
    const a = byId[edge.fromCrs], b = byId[edge.toCrs];
    if (!a || !b) return '';
    const p = edgePoint(a, b);
    return `<line class="edge" x1="${p.x1}" y1="${p.y1}" x2="${p.x2}" y2="${p.y2}"></line>
      <text class="edge-label" x="${(p.x1+p.x2)/2}" y="${(p.y1+p.y2)/2 - 6}">${edge.id} ${edge.inverseSafe ? '⇄' : '→'}</text>
      ${edge.inverseSafe ? '' : `<text class="danger-label" x="${(p.x1+p.x2)/2}" y="${(p.y1+p.y2)/2 + 10}">不可逆</text>`}`;
  }).join('');
  const pending = (registry.pendingEdges || []).map(edge => {
    const a = byId[edge.fromCrs], b = byId[edge.toCrs];
    if (!a || !b) return '';
    const p = edgePoint(a, b);
    return `<line class="edge pending" x1="${p.x1}" y1="${p.y1 - 8}" x2="${p.x2}" y2="${p.y2 - 8}"></line>
      <text class="edge-label" x="${(p.x1+p.x2)/2}" y="${(p.y1+p.y2)/2 - 20}">${edge.id} 待确认</text>`;
  }).join('');
  const nodes = graphNodes.map(node => `<g data-id="${node.id}">
    <circle class="node" cx="${node.x}" cy="${node.y}" r="34"></circle>
    <text class="node-text" text-anchor="middle" x="${node.x}" y="${node.y - 2}">${node.id.split('_')[0]}</text>
    <text class="node-text" text-anchor="middle" x="${node.x}" y="${node.y + 13}">${node.axes}</text>
  </g>`).join('');
  svg.innerHTML = defs + edges + pending + nodes;
}

function edgePoint(a, b) {
  const dx = b.x - a.x, dy = b.y - a.y;
  const length = Math.hypot(dx, dy) || 1;
  const ux = dx / length, uy = dy / length;
  return { x1: a.x + ux * 36, y1: a.y + uy * 36, x2: b.x - ux * 45, y2: b.y - uy * 45 };
}

function renderPending() {
  const list = registry.pendingEdges || [];
  $('pending').innerHTML = list.length ? list.map(edge =>
    `<div class="path pending"><b>${edge.id}</b> ${edge.fromCrs} → ${edge.toCrs}<br>
     状态：${edge.consistencyCheck.status}，最大差异 ${formatNumber(edge.consistencyCheck.maximumAbsoluteDifference)}，容忍 ${formatNumber(edge.consistencyCheck.tolerance)}</div>`
  ).join('') : '<p>暂无待确认边。</p>';
}

async function createCrs() {
  try {
    await api('/api/crs', { method: 'POST', body: {
      id: $('newCrsId').value.trim(), name: $('newCrsName').value.trim(),
      axes: $('newCrsAxes').value, unit: $('newCrsUnit').value.trim(),
      description: $('newCrsDescription').value.trim()
    }});
    await refresh();
  } catch (e) { alert(e.message); }
}

function edgeBody() {
  return {
    id: $('edgeId').value.trim(), fromCrs: $('edgeFrom').value, toCrs: $('edgeTo').value,
    forward: { a: num('edgeA'), b: num('edgeB'), c: num('edgeC'), d: num('edgeD'), e: num('edgeE'), f: num('edgeF') },
    inverseSafe: $('edgeInverseSafe').checked,
    forwardAccuracy: num('edgeAcc'),
    inverseAccuracy: $('edgeInverseSafe').checked ? num('edgeInvAcc') : null,
    region: { boxes: [{ minX: num('boxMinX'), minY: num('boxMinY'), maxX: num('boxMaxX'), maxY: num('boxMaxY'), crossesAntimeridian: $('boxCross').checked }] },
    inverseRegion: $('edgeInverseSafe').checked ? { boxes: [{ minX: num('boxMinX'), minY: num('boxMinY'), maxX: num('boxMaxX'), maxY: num('boxMaxY'), crossesAntimeridian: $('boxCross').checked }] } : null
  };
}
const num = (id) => Number($(id).value);

async function createEdge() {
  try {
    const result = await api('/api/edges', { method: 'POST', body: edgeBody() });
    await refresh();
    $('message').textContent = result.status === 'PENDING'
      ? `边 ${result.edge.id} 已进入待确认，不参与默认路径。最大差异 ${result.consistencyCheck.maximumAbsoluteDifference} > ${result.consistencyCheck.tolerance}`
      : '边已登记并激活。';
  } catch (e) { alert(e.message); }
}

async function confirmEdge() {
  try {
    await api('/api/edges/confirm', { method: 'POST', body: {
      edgeId: $('confirmId').value.trim(), reason: $('confirmReason').value.trim()
    }});
    $('confirmReason').value = '';
    await refresh();
  } catch (e) { alert(e.message); }
}

async function loadJobs() {
  const data = await api('/api/jobs');
  $('jobs').innerHTML = data.jobs.length ? data.jobs.map(job =>
    `<div class="job" data-id="${job.id}"><span><b>${job.id}</b> ${job.label} · v${job.registryVersion}</span>
     <span class="${job.status === 'SUCCESS' ? 'ok' : job.status === 'FAILED' ? 'fail' : 'partial'}">${job.status} ${job.successCount}/${job.successCount + job.failureCount}</span></div>`
  ).join('') : '<p>暂无历史作业。</p>';
  document.querySelectorAll('.job').forEach(row => row.onclick = async () => {
    lastJob = await api('/api/jobs/' + row.dataset.id);
    renderJob(lastJob);
  });
}

$('refreshBtn').onclick = refresh;
$('planBtn').onclick = onlyPlan;
$('submitBtn').onclick = submitJob;
$('createCrsBtn').onclick = createCrs;
$('createEdgeBtn').onclick = createEdge;
$('confirmBtn').onclick = confirmEdge;
$('exportBtn').onclick = async () => {
  const data = await api('/api/export');
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url; link.download = 'coordinate-registry-export.json'; link.click();
  URL.revokeObjectURL(url);
};
$('importFile').onchange = async (event) => {
  try {
    const data = JSON.parse(await event.target.files[0].text());
    await api('/api/import', { method: 'POST', body: data });
    await refresh();
  } catch (e) { alert(e.message); }
};
$('sampleBtn').onclick = () => {
  $('sourceCrs').value = 'WGS84_GEO'; $('targetCrs').value = 'LOCAL_GRID';
  $('geoInput').value = JSON.stringify({
    type: 'FeatureCollection',
    features: [
      { type: 'Feature', properties: { name: 'boundary-point' }, geometry: { type: 'Point', coordinates: [139.7, 35.65] } },
      { type: 'Feature', properties: { name: 'line' }, geometry: { type: 'LineString', coordinates: [[139.5, 35.55], [139.8, 35.7], [140.0, 35.85]] } },
      { type: 'Feature', properties: { name: 'polygon' }, geometry: { type: 'Polygon', coordinates: [[[139.55,35.55],[140.05,35.55],[140.05,35.95],[139.55,35.95],[139.55,35.55]]] } }
    ]
  }, null, 2);
};
$('datelineBtn').onclick = () => {
  $('sourceCrs').value = 'WGS84_GEO'; $('targetCrs').value = 'PACIFIC_GEO';
  $('geoInput').value = JSON.stringify({
    type: 'Polygon',
    coordinates: [[[176,-5],[-176,-5],[-176,5],[176,5],[176,-5]]]
  }, null, 2);
};
$('geoInput').value = JSON.stringify({ type: 'Point', coordinates: [139.7, 35.65] }, null, 2);
refresh().catch(error => $('status').textContent = error.message);
