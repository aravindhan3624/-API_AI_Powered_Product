package com.api.automation.report;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.api.automation.utils.PiiMasker;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Saves every full (decrypted, masked) response of a run next to the report:
 *
 *   reports/<PROJECT>/Reports_<PROJECT>_<date>_<time>_files/responses/TC001-1.json
 *   reports/<PROJECT>/Reports_<PROJECT>_<date>_<time>_files/responses/TC001-1.html   ← viewer (table / tree / raw)
 *
 * Excel and the HTML report link to the .html viewer, so big responses stay readable.
 */
public final class ResponseStore {

    private static final ObjectMapper M = new ObjectMapper();
    private static volatile Path runFolder;      // reports/<PROJECT>/<date_time>/

    private ResponseStore() {}

    /** Called by the runner once per project run, with the report path (without extension). */
    public static void start(Path reportBase) {
        runFolder = reportBase.getParent();   // the run's own folder; viewers go to <run folder>/responses/
    }

    public static void stop() {
        runFolder = null;
    }

    /**
     * @return path of the viewer relative to the report folder (for hyperlinks), or null
     */
    public static String save(String tcId, String apiName, String method, String endpoint,
                              int httpStatus, long timeMs, String result, String request, String response) {
        return save(tcId, apiName, method, endpoint, httpStatus, timeMs, result, request, response, System.currentTimeMillis());
    }

    public static String save(String tcId, String apiName, String method, String endpoint,
                              int httpStatus, long timeMs, String result, String request, String response, long executedAt) {
        if (runFolder == null || response == null) return null;
        try {
            Path dir = runFolder.resolve("responses");
            Files.createDirectories(dir);
            String safe = tcId.replaceAll("[^A-Za-z0-9_-]", "_");
            String masked = PiiMasker.mask(response);
            String maskedReq = PiiMasker.mask(request == null ? "" : request);
            Files.writeString(dir.resolve(safe + ".json"), masked, StandardCharsets.UTF_8);

            Object parsed;
            try {
                parsed = M.readTree(masked);
            } catch (Exception e) {
                parsed = masked;
            }
            java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put("tcId", tcId);
            meta.put("apiName", apiName);
            meta.put("method", method);
            meta.put("endpoint", PiiMasker.mask(endpoint));
            meta.put("httpStatus", httpStatus);
            meta.put("timeMs", timeMs);
            meta.put("executedAt", java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(executedAt),
                    java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss")));
            meta.put("result", result);
            meta.put("request", maskedReq);
            meta.put("response", parsed);
            String json = M.writeValueAsString(meta).replace("</", "<\\/");
            Files.writeString(dir.resolve(safe + ".html"), VIEWER.replace("/*DATA*/null", json), StandardCharsets.UTF_8);
            return "responses/" + safe + ".html";
        } catch (Exception e) {
            System.out.println("⚠ Could not save full response for " + tcId + ": " + e.getMessage());
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Self-contained viewer (no internet needed). Lists → searchable table, objects → tree.
    // ════════════════════════════════════════════════════════════════════
    private static final String VIEWER = """
<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Response viewer</title>
<style>
:root{--bg:#f3f6fa;--card:#fff;--ink:#1c2833;--muted:#6b7785;--line:#e3e8ef;--navy:#0f2a4a;--blue:#1565c0;--green:#1e8449;--red:#c0392b;--amber:#d68910}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:13.5px/1.45 "Segoe UI",system-ui,Arial,sans-serif}
header{background:var(--navy);color:#fff;padding:14px 22px}header h1{margin:0;font-size:17px}header .m{opacity:.8;font-size:12.5px;margin-top:3px;word-break:break-all}
.pill{display:inline-block;padding:2px 9px;border-radius:999px;font-weight:700;font-size:11.5px;margin-left:8px}
.PASS{background:#e3f5ea;color:var(--green)}.FAIL{background:#fbe5e2;color:var(--red)}.SKIP{background:#fdf0dc;color:#a86400}
main{padding:16px 22px 40px;max-width:1600px;margin:0 auto}
.tabs{display:flex;gap:6px;margin-bottom:12px;flex-wrap:wrap}.tabs button{border:1px solid var(--line);background:#fff;border-radius:999px;padding:6px 14px;cursor:pointer;font:inherit}
.tabs button.on{background:var(--navy);color:#fff;border-color:var(--navy)}
.card{background:var(--card);border-radius:12px;box-shadow:0 1px 3px rgba(0,0,0,.06);padding:14px;margin-bottom:14px;overflow:auto}
.card h3{margin:0 0 8px;font-size:13px;color:var(--muted);text-transform:uppercase;letter-spacing:.5px}
input.s{border:1px solid var(--line);border-radius:8px;padding:6px 10px;font:inherit;width:260px;margin-bottom:8px}
table{border-collapse:collapse;font-size:12.5px;width:max-content;min-width:100%}
th{position:sticky;top:0;background:#f7f9fc;color:var(--muted);text-align:left;padding:7px 10px;border-bottom:1px solid var(--line);cursor:pointer;white-space:nowrap}
td{padding:6px 10px;border-bottom:1px solid #eef2f6;vertical-align:top;max-width:420px;word-break:break-word}
tr:hover td{background:#f6f9fd}.n{color:var(--muted)}
pre{background:#0f1b2d;color:#d6e2f0;border-radius:10px;padding:12px;font:12px/1.5 Consolas,monospace;white-space:pre-wrap;word-break:break-word;margin:0}
details{margin-left:14px}summary{cursor:pointer;color:var(--blue)}.k{color:#6c3fc9}.v{color:#0b5d3b}
.count{color:var(--muted);font-size:12px;margin-left:6px}
</style></head><body>
<header><h1 id="t"></h1><div class="m" id="m"></div></header>
<main><div class="tabs" id="tabs"></div><div id="view"></div></main>
<script>
const D=/*DATA*/null;
const esc=s=>String(s==null?'':s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
document.getElementById('t').innerHTML=esc(D.tcId+' · '+D.apiName)+'<span class="pill '+esc(D.result)+'">'+esc(D.result)+'</span>';
document.getElementById('m').textContent=D.method+' '+D.endpoint+'   ·   HTTP '+D.httpStatus+'   ·   '+D.timeMs+' ms'+(D.executedAt?'   ·   Executed: '+D.executedAt:'');
const R=D.response;
function lists(o,p,out){ if(Array.isArray(o)){ if(o.length&&typeof o[0]==='object'&&o[0]!==null) out.push([p||'(root)',o]); o.forEach((x,i)=>{ if(i<1) lists(x,p+'['+i+']',out); }); }
  else if(o&&typeof o==='object') for(const k in o) lists(o[k],p?p+'.'+k:k,out); return out; }
function table(name,arr){ const cols=[...new Set(arr.flatMap(r=>Object.keys(r||{})))];
  const id='t'+Math.random().toString(36).slice(2);
  let h='<div class="card"><h3>'+esc(name)+'<span class="count">'+arr.length+' rows</span></h3><input class="s" placeholder="Filter rows..." oninput="flt(\\''+id+'\\',this.value)"><table id="'+id+'"><thead><tr><th>#</th>'+cols.map((c,i)=>'<th onclick="srt(\\''+id+'\\','+(i+1)+')">'+esc(c)+'</th>').join('')+'</tr></thead><tbody>';
  arr.forEach((r,i)=>{ h+='<tr><td class="n">'+(i+1)+'</td>'+cols.map(c=>{const v=r?r[c]:'';return '<td>'+(v!==null&&typeof v==='object'?'<details><summary>'+(Array.isArray(v)?v.length+' items':'object')+'</summary>'+tree(v)+'</details>':esc(v))+'</td>';}).join('')+'</tr>'; });
  return h+'</tbody></table></div>'; }
function tree(o){ if(o===null||typeof o!=='object') return '<span class="v">'+esc(JSON.stringify(o))+'</span>';
  return Object.entries(o).map(([k,v])=>v!==null&&typeof v==='object'
    ?'<details><summary><span class="k">'+esc(k)+'</span> '+(Array.isArray(v)?'['+v.length+']':'{…}')+'</summary>'+tree(v)+'</details>'
    :'<div style="margin-left:14px"><span class="k">'+esc(k)+'</span>: <span class="v">'+esc(JSON.stringify(v))+'</span></div>').join(''); }
window.flt=(id,q)=>{q=q.toLowerCase();document.querySelectorAll('#'+id+' tbody tr').forEach(tr=>tr.style.display=tr.textContent.toLowerCase().includes(q)?'':'none');};
window.srt=(id,c)=>{const tb=document.querySelector('#'+id+' tbody');const rows=[...tb.rows];const asc=tb.dataset.c==c&&tb.dataset.d!=='a';
  rows.sort((a,b)=>{const x=a.cells[c].textContent,y=b.cells[c].textContent;const nx=parseFloat(x),ny=parseFloat(y);return (isNaN(nx)||isNaN(ny)?x.localeCompare(y):nx-ny)*(asc?1:-1);});
  rows.forEach(r=>tb.appendChild(r));tb.dataset.c=c;tb.dataset.d=asc?'a':'d';};
const views={
  'Tables':()=>{ const l=typeof R==='object'?lists(R,'',[]):[]; return l.length?l.map(([n,a])=>table(n,a)).join(''):'<div class="card">No list in this response - see Tree or Raw.</div>'; },
  'Tree':()=>'<div class="card">'+(typeof R==='object'?tree(R):esc(R))+'</div>',
  'Raw JSON':()=>'<div class="card"><pre>'+esc(typeof R==='object'?JSON.stringify(R,null,2):R)+'</pre></div>',
  'Request':()=>'<div class="card"><pre>'+esc(D.request||'(no body)')+'</pre></div>'
};
function show(k){document.getElementById('view').innerHTML=views[k]();document.querySelectorAll('#tabs button').forEach(b=>b.classList.toggle('on',b.textContent===k));}
document.getElementById('tabs').innerHTML=Object.keys(views).map(k=>'<button onclick="show(\\''+k+'\\')">'+k+'</button>').join('');
window.show=show; show(typeof R==='object'&&lists(R,'',[]).length?'Tables':'Tree');
</script></body></html>
""";
}
