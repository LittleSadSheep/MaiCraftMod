# SPDX-License-Identifier: GPL-3.0-only
"""Self-contained visual review of recorded LightNav responses (stdlib only)."""

import json
from pathlib import Path


def write_report(records: list[dict], output: Path, instruction: str) -> None:
    """Write observations and predictions without interpreting them as controls."""
    payload = json.dumps({"records": records, "instruction": instruction}, ensure_ascii=False)
    payload = payload.replace("&", "\\u0026").replace("<", "\\u003c").replace(">", "\\u003e")
    payload = payload.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(_HTML.replace("__PAYLOAD__", payload), encoding="utf-8")


_HTML = r'''<!doctype html>
<html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>LightNav 离线帧回放</title>
<style>
:root{color-scheme:dark;font:16px/1.5 system-ui,sans-serif;background:#101820;color:#edf3f9}
body{max-width:1400px;margin:auto;padding:24px}h1{font-size:26px;margin:0 0 12px}
p{margin:8px 0}.muted{color:#aebfce}.notice{padding:14px;background:#29332c;border-left:4px solid #d1bb67}
.controls{display:flex;align-items:center;gap:12px;margin:20px 0}input{flex:1;min-width:80px}
button{padding:6px 12px;background:#283e50;color:inherit;border:1px solid #597187;border-radius:5px}
.panels{display:grid;grid-template-columns:1.3fr 1fr;gap:20px}.panel{background:#192631;padding:16px;border-radius:10px;min-width:0}
.camera{position:relative}.camera img{display:block;width:100%;height:auto}.camera img[hidden]{display:none}.camera svg{position:absolute;inset:0;width:100%;height:100%;pointer-events:none}
#trajectory{width:100%;background:#101c26;border-radius:6px}#instruction,#metadata,#grounding,#waypoints{white-space:pre-wrap;overflow-wrap:anywhere}
pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px}h2{font-size:18px;margin:0 0 10px}
@media(max-width:850px){.panels{grid-template-columns:1fr}body{padding:14px}}
</style>
<h1>LightNav 离线帧回放</h1>
<p class="notice">实验模型预测，仅供人工检查；本报告不控制游戏。路径点单位为米，不等同于 Minecraft 方块。</p>
<p>导航指令：<span id="instruction"></span></p>
<div class="controls"><button id="previous">上一帧</button><input id="slider" type="range" min="0" value="0" aria-label="选择帧"><button id="next">下一帧</button><span id="position"></span></div>
<p id="metadata"></p>
<div class="panels"><section class="panel"><h2>原始画面与像素定位</h2>
<div class="camera"><img id="frame" alt="记录的输入帧"><svg id="overlay" aria-label="模型像素定位"></svg></div>
<p id="grounding" class="muted"></p></section>
<section class="panel"><h2>当前帧坐标系中的预测路径</h2>
<svg id="trajectory" viewBox="0 0 640 480" role="img" aria-label="预测路径俯视图"></svg>
<p class="muted">每行是相对当前帧的累计坐标，直接绘制，不将各行相加。前方朝上，左侧朝左；yaw 从前方起逆时针为正。</p>
<p id="waypoints"></p></section></div>
<details><summary>原始协议响应</summary><pre id="raw"></pre></details>
<script id="payload" type="application/json">__PAYLOAD__</script>
<script>
'use strict';
const payload=JSON.parse(document.getElementById('payload').textContent), records=payload.records;
const byId=id=>document.getElementById(id), slider=byId('slider'), frame=byId('frame');
const ns='http://www.w3.org/2000/svg';
let active=null;
function svg(parent,tag,attrs,text){const node=document.createElementNS(ns,tag);for(const [key,value] of Object.entries(attrs))node.setAttribute(key,String(value));if(text!==undefined)node.textContent=text;parent.appendChild(node);return node;}
function label(parent,x,y,text,color='#b7c9d8'){return svg(parent,'text',{x,y,fill:color,'font-size':14},text);}
function numeric(value){return typeof value==='number'&&Number.isFinite(value);}
function display(value){return value===undefined?'未提供':String(value);}
function latency(value){return numeric(value)?value.toFixed(1)+' ms':'未提供';}
function drawGrounding(record){
  if(record!==active)return;
  const layer=byId('overlay');layer.replaceChildren();
  const pointing=record.response?.data?.pointing, size=pointing?.frame_size;
  if(!pointing){byId('grounding').textContent='没有像素定位（grounding）数据。';return;}
  if(!Array.isArray(size)||size.length!==2||!size.every(numeric)||size[0]<=0||size[1]<=0){byId('grounding').textContent='定位缺少有效 frame_size，未叠加像素点。';return;}
  if(frame.naturalWidth!==size[0]||frame.naturalHeight!==size[1]){byId('grounding').textContent=`定位尺寸 ${size.join(' × ')} 与原始图片 ${frame.naturalWidth} × ${frame.naturalHeight} 不一致，未叠加像素点。`;return;}
  layer.setAttribute('viewBox',`0 0 ${size[0]} ${size[1]}`);
  const notes=[], radius=Math.max(4,Math.min(...size)/70);
  for(const [key,color] of [['apos','#ffcc66'],['opos','#81e1ff']]){
    const point=pointing[key+'_px'], state=pointing[key+'_state'], clamped=pointing[key+'_clamped'];
    const valid=state==='point'&&Array.isArray(point)&&point.length===2&&point.every(numeric);
    const inside=valid&&point[0]>=0&&point[1]>=0&&point[0]<size[0]&&point[1]<size[1];
    notes.push(`${key}: ${display(state)}；边界截断=${display(clamped)}${inside?'': '；无可叠加像素点'}`);
    if(!inside)continue;
    svg(layer,'circle',{cx:point[0],cy:point[1],r:radius,fill:color,'fill-opacity':.25,stroke:color,'stroke-width':Math.max(2,radius/3)});
    svg(layer,'text',{x:Math.min(size[0]-radius*8,point[0]+radius),y:Math.max(radius*2,point[1]-radius),fill:color,'font-size':radius*2,stroke:'#101820','stroke-width':radius/8,'paint-order':'stroke'},key+(clamped?' (clamped)':''));
  }
  byId('grounding').textContent=notes.join('\n');
}
function drawTrajectory(data){
  const chart=byId('trajectory');chart.replaceChildren();
  const source=Array.isArray(data.actions?.actions)?data.actions.actions:[];
  const points=source.filter(row=>Array.isArray(row)&&row.length===3&&row.every(numeric));
  const maxLeft=Math.max(1,...points.map(row=>Math.abs(row[1]))), maxForward=Math.max(1,...points.map(row=>Math.abs(row[0])));
  const scale=Math.min(255/maxLeft,160/maxForward), project=row=>[320-row[1]*scale,240-row[0]*scale];
  svg(chart,'line',{x1:45,y1:240,x2:605,y2:240,stroke:'#405b6d'});
  svg(chart,'line',{x1:320,y1:45,x2:320,y2:420,stroke:'#405b6d'});
  label(chart,330,32,'+前方 (m)');label(chart,12,225,'+左侧 (m)');label(chart,330,460,`标尺：${(60/scale).toFixed(2)} m`);
  svg(chart,'line',{x1:260,y1:450,x2:320,y2:450,stroke:'#aebfce','stroke-width':2});
  svg(chart,'circle',{cx:320,cy:240,r:5,fill:'#eaf6ff'});label(chart,329,260,'原点');
  if(points.length){
    svg(chart,'polyline',{points:[[320,240],...points.map(project)].map(p=>p.join(',')).join(' '),fill:'none',stroke:'#69d6bb','stroke-width':3});
    points.forEach((row,index)=>{
      const [x,y]=project(row), yaw=row[2], dx=-Math.sin(yaw), dy=-Math.cos(yaw);
      svg(chart,'circle',{cx:x,cy:y,r:4,fill:'#69d6bb'});
      svg(chart,'line',{x1:x,y1:y,x2:x+22*dx,y2:y+22*dy,stroke:'#ffcc66','stroke-width':3});
      svg(chart,'polyline',{points:[[x+15*dx+5*dy,y+15*dy-5*dx],[x+22*dx,y+22*dy],[x+15*dx-5*dy,y+15*dy+5*dx]].map(p=>p.join(',')).join(' '),fill:'none',stroke:'#ffcc66','stroke-width':2});
      label(chart,x+8,y-8,String(index+1));
    });
  }else label(chart,170,100,'本帧没有路径点（可能为缓冲预热响应）');
  byId('waypoints').textContent=points.map((row,index)=>`${index+1}: forward=${row[0]} m, left=${row[1]} m, yaw=${row[2]} rad`).join('\n')+(source.length!==points.length?'\n无效路径行已省略。':'');
}
function render(){
  const index=Number(slider.value), record=records[index];active=record;
  byId('instruction').textContent=record?.instruction??payload.instruction;
  byId('previous').disabled=!record||index===0;byId('next').disabled=!record||index===records.length-1;
  byId('position').textContent=record?`${index+1} / ${records.length}`:'0 / 0';
  byId('overlay').replaceChildren();
  if(!record){frame.hidden=true;byId('metadata').textContent='没有可回放的记录。';byId('grounding').textContent='没有像素定位数据。';drawTrajectory({});return;}
  const data=record.response?.data||{};
  byId('metadata').textContent=`seq=${display(record.seq)} · ${display(record.frame)}\nstop=${display(data.stop)} · visible=${display(data.visible)} · rc=${display(data.rc)} · 往返=${latency(record.roundtrip_ms)} · 服务端=${latency(data.latency_ms)}`;
  byId('raw').textContent=JSON.stringify(record.response,null,2);drawTrajectory(data);
  byId('grounding').textContent='正在检查图片尺寸与定位坐标…';
  frame.onload=()=>drawGrounding(record);
  frame.onerror=()=>{if(record===active){frame.hidden=true;byId('overlay').replaceChildren();byId('grounding').textContent='图片无法加载，未叠加像素定位。';}};
  if(typeof record.image==='string'&&/^data:image\/(png|jpeg);base64,[A-Za-z0-9+/=\s]+$/.test(record.image)){frame.hidden=false;frame.src=record.image;}
  else {frame.hidden=true;frame.removeAttribute('src');byId('grounding').textContent='没有有效的内嵌 PNG/JPEG 图片，未叠加像素定位。';}
}
byId('instruction').textContent=payload.instruction;
slider.max=String(Math.max(0,records.length-1));slider.disabled=records.length===0;
slider.addEventListener('input',render);
byId('previous').addEventListener('click',()=>{slider.value=String(Math.max(0,Number(slider.value)-1));render();});
byId('next').addEventListener('click',()=>{slider.value=String(Math.min(records.length-1,Number(slider.value)+1));render();});
render();
</script></html>'''
