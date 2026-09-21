package station.web;

/** 单页应用 HTML（内联资源，无外部依赖）。 */
public final class Page {
    private Page() {}

    public static final String HTML = "<!DOCTYPE html>\n"
        + "<html lang=\"zh-CN\">\n"
        + "<head>\n"
        + "<meta charset=\"utf-8\">\n"
        + "<title>坐标变换注册站</title>\n"
        + "<style>\n"
        + "body{font-family:system-ui,'PingFang SC','Microsoft YaHei',sans-serif;margin:0;background:#f4f6f8;color:#222}\n"
        + "header{background:#1f3a5f;color:#fff;padding:14px 22px}\n"
        + "header h1{margin:0;font-size:20px}\n"
        + "header .meta{font-size:12px;opacity:.85;margin-top:4px}\n"
        + "main{display:grid;grid-template-columns:1fr 1fr;gap:14px;padding:14px}\n"
        + "section{background:#fff;border:1px solid #dde3ea;border-radius:8px;padding:12px 14px}\n"
        + "section h2{margin:0 0 8px;font-size:15px;color:#1f3a5f}\n"
        + "textarea{width:100%;box-sizing:border-box;font-family:ui-monospace,monospace;font-size:12px}\n"
        + "input,select,button{font-size:13px;margin:2px 0}\n"
        + "button{background:#1f3a5f;color:#fff;border:0;border-radius:4px;padding:5px 12px;cursor:pointer}\n"
        + "button:hover{background:#2a5288}\n"
        + "canvas{border:1px solid #dde3ea;border-radius:6px;background:#fbfcfe;width:100%}\n"
        + ".row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}\n"
        + ".ok{color:#137333}.err{color:#b3261e}.pending{color:#b06000}\n"
        + "table{border-collapse:collapse;width:100%;font-size:12px}\n"
        + "td,th{border:1px solid #e2e8f0;padding:4px 6px;text-align:left}\n"
        + "pre{background:#f6f8fa;padding:8px;border-radius:6px;font-size:12px;overflow:auto;max-height:260px}\n"
        + ".tag{display:inline-block;padding:1px 6px;border-radius:8px;font-size:11px;background:#e8eef7;margin-right:4px}\n"
        + "</style>\n"
        + "</head>\n"
        + "<body>\n"
        + "<header><h1>坐标变换注册站</h1>"
        + "<div class=\"meta\">注册表版本 <span id=ver>-</span> · "
        + "<a style=\"color:#cfe1ff\" href=\"/api/export\" target=\"_blank\">导出 JSON</a></div></header>\n"
        + "<main>\n"
        + "<section><h2>参考系与变换图</h2><canvas id=graph height=300></canvas>"
        + "<div id=edgeList style=\"font-size:12px;margin-top:6px\"></div></section>\n"
        + "<section><h2>注册</h2>\n"
        + "<div class=row><input id=crsId placeholder=\"参考系 id 如 WGS84\">"
        + "<input id=crsName placeholder=\"名称\"><button onclick=addCrs()>注册参考系</button></div>\n"
        + "<textarea id=edgeJson rows=6 placeholder='变换边 JSON：{\"id\":\"e1\",\"from\":\"WGS84\",\"to\":\"TOKYO\",\"region\":{\"lonMin\":120,\"lonMax\":150,\"latMin\":20,\"latMax\":50},\"accuracy\":0.5,\"invertible\":true,\"affine\":[1,0,0.1,0,1,-0.2]}'></textarea>\n"
        + "<button onclick=addEdge()>注册变换边</button>\n"
        + "<div class=row style=\"margin-top:6px\"><input id=confirmId placeholder=\"待确认边 id\">"
        + "<input id=confirmReason placeholder=\"确认理由\" size=24><button onclick=confirmEdge()>确认边</button></div>\n"
        + "<pre id=regOut></pre></section>\n"
        + "<section><h2>批量变换</h2>\n"
        + "<div class=row><select id=srcCrs></select><span>→</span><select id=dstCrs></select></div>\n"
        + "<textarea id=geojson rows=7 placeholder='粘贴 GeoJSON 数组，如 [{\"id\":\"a\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[139.7,35.6]}}]'></textarea>\n"
        + "<button onclick=runTransform()>执行变换</button>\n"
        + "<div id=jobOut style=\"margin-top:8px\"></div></section>\n"
        + "<section><h2>输入 / 输出叠加</h2><canvas id=overlay height=300></canvas>"
        + "<div style=\"font-size:12px\"><span style=\"color:#1a73e8\">■ 输入</span> "
        + "<span style=\"color:#d93025\">■ 输出</span></div>"
        + "<h2 style=\"margin-top:10px\">候选路径解释</h2><div id=paths></div></section>\n"
        + "</main>\n"
        + "<script>\n"
        + JS
        + "</script>\n"
        + "</body></html>\n";

    private static final String JS = String.join("\n",
        "let state=null, lastJob=null;",
        "async function api(path,body){",
        "  const opt=body?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}:{};",
        "  const r=await fetch(path,opt); const j=await r.json();",
        "  if(!r.ok) throw new Error(j.error||('HTTP '+r.status)); return j; }",
        "async function refresh(){",
        "  state=await api('/api/state');",
        "  document.getElementById('ver').textContent=state.version;",
        "  const opts=state.crs.map(c=>`<option>${c.id}</option>`).join('');",
        "  document.getElementById('srcCrs').innerHTML=opts;",
        "  document.getElementById('dstCrs').innerHTML=opts;",
        "  drawGraph(); renderEdges(); }",
        "function renderEdges(){",
        "  document.getElementById('edgeList').innerHTML=state.edges.map(e=>{",
        "    const cls=e.status==='PENDING'?'pending':'ok';",
        "    return `<div><span class=tag>${e.id}</span> ${e.from} → ${e.to} ",
        "    精度 ${e.accuracy} ${e.invertible?'可逆':'不可逆'} ",
        "    <span class=${cls}>${e.status}</span>",
        "    ${e.pendingReason?(' · '+e.pendingReason):''}</div>`;}).join(''); }",
        "function drawGraph(){",
        "  const cv=document.getElementById('graph'),ctx=cv.getContext('2d');",
        "  cv.width=cv.clientWidth; ctx.clearRect(0,0,cv.width,cv.height);",
        "  const n=state.crs.length; if(!n){ctx.fillText('尚未注册参考系',10,20);return;}",
        "  const cx=cv.width/2,cy=cv.height/2,R=Math.min(cx,cy)-40,pos={};",
        "  state.crs.forEach((c,i)=>{const a=2*Math.PI*i/n-Math.PI/2;",
        "    pos[c.id]=[cx+R*Math.cos(a),cy+R*Math.sin(a)];});",
        "  state.edges.forEach((e,k)=>{const p1=pos[e.from],p2=pos[e.to]; if(!p1||!p2)return;",
        "    ctx.strokeStyle=e.status==='PENDING'?'#b06000':'#1f3a5f';",
        "    ctx.setLineDash(e.status==='PENDING'?[5,4]:[]);",
        "    ctx.beginPath(); ctx.moveTo(p1[0],p1[1]); ctx.lineTo(p2[0],p2[1]); ctx.stroke();",
        "    const mx=(p1[0]+p2[0])/2,my=(p1[1]+p2[1])/2;",
        "    const dx=p2[0]-p1[0],dy=p2[1]-p1[1],L=Math.hypot(dx,dy)||1;",
        "    ctx.fillStyle=ctx.strokeStyle; ctx.beginPath();",
        "    ctx.moveTo(p2[0]-12*dx/L,p2[1]-12*dy/L);",
        "    ctx.lineTo(p2[0]-20*dx/L-5*dy/L,p2[1]-20*dy/L+5*dx/L);",
        "    ctx.lineTo(p2[0]-20*dx/L+5*dy/L,p2[1]-20*dy/L-5*dx/L);",
        "    ctx.closePath(); ctx.fill();",
        "    ctx.font='11px sans-serif'; ctx.fillText(e.id+(e.invertible?' ⇄':''),mx+4,my-4+k%2*10);});",
        "  ctx.setLineDash([]);",
        "  state.crs.forEach(c=>{const p=pos[c.id];",
        "    ctx.fillStyle='#1f3a5f'; ctx.beginPath(); ctx.arc(p[0],p[1],16,0,7); ctx.fill();",
        "    ctx.fillStyle='#fff'; ctx.font='10px sans-serif'; ctx.textAlign='center';",
        "    ctx.fillText(c.id,p[0],p[1]+3); ctx.textAlign='left';}); }",
        "async function addCrs(){ try{",
        "  await api('/api/crs',{id:crsId.value,name:crsName.value||crsId.value});",
        "  regOut.textContent='已注册参考系 '+crsId.value; refresh();",
        " }catch(e){regOut.textContent=e.message;} }",
        "async function addEdge(){ try{",
        "  const j=await api('/api/edges',JSON.parse(edgeJson.value));",
        "  regOut.textContent=JSON.stringify(j,null,1); refresh();",
        " }catch(e){regOut.textContent=e.message;} }",
        "async function confirmEdge(){ try{",
        "  const j=await api('/api/edges/confirm',{id:confirmId.value,reason:confirmReason.value});",
        "  regOut.textContent=JSON.stringify(j,null,1); refresh();",
        " }catch(e){regOut.textContent=e.message;} }",
        "async function runTransform(){ try{",
        "  const objects=JSON.parse(geojson.value);",
        "  const j=await api('/api/transform',{sourceCrs:srcCrs.value,targetCrs:dstCrs.value,objects});",
        "  lastJob=j; renderJob(j);",
        " }catch(e){jobOut.innerHTML='<span class=err>'+e.message+'</span>';} }",
        "function renderJob(j){",
        "  let h=`<div>作业 <b>${j.id}</b> · 注册表版本 ${j.registryVersion} · 指纹 <code>${j.fingerprint.slice(0,16)}…</code></div>`;",
        "  h+='<table><tr><th>对象</th><th>状态</th><th>路径 / 诊断</th><th>误差上界</th></tr>';",
        "  j.results.forEach(r=>{",
        "    if(r.status==='ok'){",
        "      const p=r.path.map(a=>a.edge+(a.inverse?'(逆)':'')).join(' → ');",
        "      h+=`<tr><td>${r.id}</td><td class=ok>成功</td><td>${p||'(恒等)'}</td><td>${r.errorBound}</td></tr>`;",
        "    }else{ h+=`<tr><td>${r.id}</td><td class=err>失败</td><td>${r.diagnostic}</td><td>-</td></tr>`; }",
        "  });",
        "  h+='</table>'; jobOut.innerHTML=h;",
        "  let ph=''; j.results.forEach(r=>{ if(!r.candidates)return;",
        "    ph+=`<h3 style=\"font-size:13px;margin:6px 0\">对象 ${r.id}</h3>`;",
        "    r.candidates.forEach(c=>{",
        "      const cls=c.chosen?'ok':(c.accepted?'ok':'err');",
        "      ph+=`<div style=\"font-size:12px;margin:3px 0\"><span class=${cls}>${c.chosen?'✔ 已选':'✘ 排除'}</span> ",
        "      <code>${c.path}</code> 误差 ${c.totalError} — ${c.reason}</div>`;});});",
        "  paths.innerHTML=ph||'<span style=\"font-size:12px;color:#888\">无候选路径</span>';",
        "  drawOverlay(j); }",
        "function geomsOf(j,which){ const out=[];",
        "  j.results.forEach(r=>{ if(r.status==='ok'&&r[which]) out.push(r[which]); }); return out; }",
        "function collectCoords(g,cb){",
        "  if(g.type==='Point') cb(g.coordinates);",
        "  else if(g.type==='LineString') g.coordinates.forEach(cb);",
        "  else if(g.type==='Polygon') g.coordinates.forEach(r=>r.forEach(cb)); }",
        "function drawOverlay(j){",
        "  const cv=document.getElementById('overlay'),ctx=cv.getContext('2d');",
        "  cv.width=cv.clientWidth; ctx.clearRect(0,0,cv.width,cv.height);",
        "  const ins=geomsOf(j,'input'),outs=geomsOf(j,'output');",
        "  let xs=[],ys=[];",
        "  ins.concat(outs).forEach(g=>collectCoords(g,c=>{xs.push(c[0]);ys.push(c[1]);}));",
        "  if(!xs.length){ctx.fillText('无可绘制对象',10,20);return;}",
        "  const x0=Math.min(...xs),x1=Math.max(...xs),y0=Math.min(...ys),y1=Math.max(...ys);",
        "  const sx=(x1-x0)||1,sy=(y1-y0)||1,s=Math.min((cv.width-40)/sx,(cv.height-40)/sy);",
        "  const X=v=>20+(v-x0)*s, Y=v=>cv.height-20-(v-y0)*s;",
        "  function draw(g,color){ ctx.strokeStyle=color; ctx.fillStyle=color;",
        "    if(g.type==='Point'){ ctx.beginPath(); ctx.arc(X(g.coordinates[0]),Y(g.coordinates[1]),4,0,7); ctx.fill(); }",
        "    else { const lines=g.type==='LineString'?[g.coordinates]:g.coordinates;",
        "      lines.forEach(line=>{ ctx.beginPath();",
        "        line.forEach((c,i)=>{ i?ctx.lineTo(X(c[0]),Y(c[1])):ctx.moveTo(X(c[0]),Y(c[1])); });",
        "        ctx.stroke();}); } }",
        "  ins.forEach(g=>draw(g,'#1a73e8')); outs.forEach(g=>draw(g,'#d93025')); }",
        "refresh();"
    );
}
