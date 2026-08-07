package madacode.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-contained Chinese diagnostic dashboard backed by the exact v1 report,
 * case-report, result, and trace data models.
 */
public final class EvalReportHtml {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvalReportHtml() {
    }

    public static String render(
            List<EvalCaseReport> reports,
            EvalRunProgress progress,
            EvalCostEstimator costEstimator,
            Path runDir) {
        // Escape '<' as a JSON unicode escape so embedded data cannot terminate the
        // surrounding <script> (</script>) or open an HTML comment (<!--), while remaining
        // valid JSON for JSON.parse.
        String data = json(viewData(reports, progress, costEstimator, runDir))
                .replace("<", "\\u003c");
        return TEMPLATE.replace("__EVAL_DATA__", data);
    }

    private static Map<String, Object> viewData(
            List<EvalCaseReport> reports,
            EvalRunProgress progress,
            EvalCostEstimator costEstimator,
            Path runDir) {
        EvalCostEstimator estimator = costEstimator == null ? EvalCostEstimator.none() : costEstimator;
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("report", EvalReportJson.from(reports, estimator, progress));
        List<Map<String, Object>> cases = new ArrayList<>();
        for (EvalCaseReport report : reports) {
            if (report.skipped()) {
                continue;
            }
            Map<String, Object> caseData = new LinkedHashMap<>();
            String caseReportPath = "cases/" + report.id() + "/case-report.json";
            caseData.put("report", readJsonFile(runDir, caseReportPath)
                    .orElseGet(() -> MAPPER.valueToTree(EvalReportJson.caseReportJson(report, estimator))));
            caseData.put("sourcePath", caseReportPath);
            List<Map<String, Object>> attempts = new ArrayList<>();
            for (int index = 0; index < report.attempts().size(); index++) {
                attempts.add(attemptData(
                        report.id(),
                        index + 1,
                        report.attempts().get(index),
                        runDir));
            }
            caseData.put("attempts", attempts);
            cases.add(caseData);
        }
        root.put("cases", cases);
        return root;
    }

    private static Map<String, Object> attemptData(
            String caseId,
            int number,
            EvalResult attempt,
            Path runDir) {
        String basePath = "cases/" + caseId + "/attempts/attempt-" + number + "/";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("result", readJsonFile(runDir, basePath + "result.json")
                .orElseGet(() -> MAPPER.valueToTree(
                        EvalReportJson.attemptJson(caseId, number, attempt))));
        data.put("trace", readJson(runDir, attempt, "trace.json"));
        data.put("verify", readText(runDir, attempt, "verify.txt"));
        data.put("resultPath", basePath + "result.json");
        data.put("tracePath", basePath + "trace.json");
        data.put("verifyPath", basePath + "verify.txt");
        return data;
    }

    private static JsonNode readJson(Path runDir, EvalResult attempt, String fileName) {
        Path path = artifactPath(runDir, attempt, fileName);
        if (path == null) {
            return MAPPER.createObjectNode().put("available", false);
        }
        try {
            return MAPPER.readTree(path.toFile());
        } catch (IOException e) {
            return MAPPER.createObjectNode()
                    .put("available", false)
                    .put("error", e.getMessage());
        }
    }

    private static java.util.Optional<JsonNode> readJsonFile(Path runDir, String relativePath) {
        Path normalizedRunDir = runDir.toAbsolutePath().normalize();
        Path path = normalizedRunDir.resolve(relativePath).normalize();
        if (!path.startsWith(normalizedRunDir) || !Files.isRegularFile(path)) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.ofNullable(MAPPER.readTree(path.toFile()));
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    private static String readText(Path runDir, EvalResult attempt, String fileName) {
        Path path = artifactPath(runDir, attempt, fileName);
        if (path == null) {
            return "未生成";
        }
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return "读取失败：" + e.getMessage();
        }
    }

    private static Path artifactPath(Path runDir, EvalResult attempt, String fileName) {
        String directory = attempt.artifacts().directory();
        if (directory == null || directory.isBlank()) {
            return null;
        }
        Path normalizedRunDir = runDir.toAbsolutePath().normalize();
        Path path = normalizedRunDir.resolve(directory).resolve(fileName).normalize();
        return path.startsWith(normalizedRunDir) && Files.isRegularFile(path) ? path : null;
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to render eval HTML data", e);
        }
    }

    private static final String TEMPLATE = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <link rel="icon" href="data:,">
              <title>MadaCode Eval 诊断控制台</title>
              <style>
                :root{color-scheme:dark;--bg:#07101e;--surface:#0d1728;--surface2:#111e32;--surface3:#17253b;--line:#263750;--text:#eaf0fa;--muted:#8fa1b9;--faint:#61738d;--accent:#6ea8fe;--accent2:#8b7cff;--good:#42d392;--warn:#f8c555;--bad:#ff6b7a;--info:#5fd6e8;--mono:"SFMono-Regular",Consolas,"Liberation Mono",monospace}
                *{box-sizing:border-box}html,body{margin:0;min-height:100%;background:var(--bg);color:var(--text);font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif}button,input{font:inherit}button{color:inherit}.mono{font-family:var(--mono);font-size:12px}.muted{color:var(--muted)}.faint{color:var(--faint)}.wrap{overflow-wrap:anywhere}.shell{min-height:100vh}.appbar{height:68px;padding:0 24px;border-bottom:1px solid var(--line);display:flex;align-items:center;justify-content:space-between;gap:20px;background:rgba(7,16,30,.92);backdrop-filter:blur(14px);position:sticky;top:0;z-index:20}.brand{display:flex;align-items:center;gap:12px}.logo{width:34px;height:34px;border-radius:10px;background:linear-gradient(135deg,var(--accent),var(--accent2));box-shadow:0 0 28px rgba(110,168,254,.24);display:grid;place-items:center;font-weight:800;color:#07101e}.brand h1{font-size:17px;margin:0}.brand .sub{font-size:12px;color:var(--muted)}.run-state{display:flex;align-items:center;gap:10px}
                .dashboard{display:grid;grid-template-columns:330px minmax(0,1fr);min-height:calc(100vh - 68px)}.rail{border-right:1px solid var(--line);background:#091321;position:sticky;top:68px;height:calc(100vh - 68px);display:flex;flex-direction:column}.rail-head{padding:18px 16px 12px;border-bottom:1px solid var(--line)}.rail-title{display:flex;justify-content:space-between;align-items:center;margin-bottom:12px}.rail-title strong{font-size:13px}.search{width:100%;background:var(--surface);border:1px solid var(--line);border-radius:8px;color:var(--text);padding:9px 11px;outline:none}.search:focus{border-color:var(--accent)}.filters{display:flex;gap:6px;margin-top:10px;flex-wrap:wrap}.filter{border:1px solid var(--line);background:transparent;border-radius:999px;padding:4px 9px;font-size:12px;cursor:pointer}.filter.active{border-color:var(--accent);background:rgba(110,168,254,.12);color:var(--accent)}.case-list{overflow:auto;padding:8px}.case-item{width:100%;overflow:hidden;border:1px solid transparent;background:transparent;text-align:left;padding:11px 10px;border-radius:9px;cursor:pointer;margin-bottom:3px}.case-item:hover{background:var(--surface)}.case-item.active{background:linear-gradient(90deg,rgba(110,168,254,.16),rgba(139,124,255,.06));border-color:rgba(110,168,254,.35)}.case-top{display:flex;min-width:0;align-items:center;justify-content:space-between;gap:10px}.case-id{flex:1;min-width:0;font-family:var(--mono);font-size:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.case-meta{display:grid;grid-template-columns:auto auto minmax(0,1fr) auto;min-width:0;gap:9px;color:var(--muted);font-size:11px;margin-top:6px;white-space:nowrap}.case-meta span:nth-child(3){overflow:hidden;text-overflow:ellipsis}
                .content{min-width:0;padding:22px 26px 60px}.summary-grid{display:grid;grid-template-columns:repeat(6,minmax(135px,1fr));gap:10px;margin-bottom:18px}.metric-card{background:linear-gradient(145deg,var(--surface2),var(--surface));border:1px solid var(--line);border-radius:11px;padding:13px 14px;min-height:92px}.metric-label{color:var(--muted);font-size:12px}.metric-value{font-size:23px;font-weight:700;letter-spacing:-.03em;margin-top:4px}.metric-note{font-size:11px;color:var(--faint);margin-top:2px}.case-header{background:linear-gradient(120deg,var(--surface2),#10213b);border:1px solid var(--line);border-radius:13px;padding:18px 20px;margin-bottom:12px}.case-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}.case-heading h2{font:650 19px/1.4 var(--mono);margin:0;overflow-wrap:anywhere}.chips{display:flex;gap:6px;flex-wrap:wrap;margin-top:8px}.chip,.badge{display:inline-flex;align-items:center;border:1px solid var(--line);border-radius:999px;padding:3px 8px;font-size:11px}.badge{flex:0 0 auto;font-weight:700;line-height:1.2;white-space:nowrap;word-break:keep-all}.good{color:var(--good);border-color:rgba(66,211,146,.5);background:rgba(66,211,146,.08)}.bad{color:var(--bad);border-color:rgba(255,107,122,.5);background:rgba(255,107,122,.08)}.warn{color:var(--warn);border-color:rgba(248,197,85,.5);background:rgba(248,197,85,.08)}.neutral{color:var(--muted)}.header-facts{display:grid;grid-template-columns:repeat(6,minmax(105px,1fr));gap:10px;margin-top:16px}.header-fact{border-left:2px solid var(--line);padding-left:9px}.header-fact span{display:block;color:var(--muted);font-size:11px}.header-fact b{display:block;margin-top:2px}
                .tabs{display:flex;gap:4px;border-bottom:1px solid var(--line);margin-bottom:14px;position:sticky;top:68px;background:var(--bg);z-index:10;padding-top:4px}.tab{border:0;background:transparent;padding:11px 13px;color:var(--muted);cursor:pointer;border-bottom:2px solid transparent}.tab.active{color:var(--accent);border-color:var(--accent)}.panel-grid{display:grid;grid-template-columns:repeat(12,minmax(0,1fr));gap:12px}.panel{background:var(--surface);border:1px solid var(--line);border-radius:11px;padding:15px;min-width:0}.panel h3{font-size:13px;margin:0 0 12px}.span-12{grid-column:span 12}.span-8{grid-column:span 8}.span-7{grid-column:span 7}.span-6{grid-column:span 6}.span-5{grid-column:span 5}.span-4{grid-column:span 4}.data-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px}.datum{background:var(--surface2);border-radius:8px;padding:10px}.datum span{display:block;color:var(--muted);font-size:11px}.datum b{display:block;margin-top:3px;overflow-wrap:anywhere}.bar{height:6px;background:var(--surface3);border-radius:999px;overflow:hidden;margin-top:7px}.bar i{display:block;height:100%;background:linear-gradient(90deg,var(--accent),var(--accent2));border-radius:inherit}
                table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:9px 10px;border-bottom:1px solid var(--line);vertical-align:top}th{color:var(--muted);font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:.03em}td{font-size:12px}.table-wrap{overflow:auto}.kv td:first-child{width:210px;color:var(--muted);white-space:nowrap}.kv td:last-child{font-family:var(--mono);overflow-wrap:anywhere}.attempt-strip{display:flex;gap:7px;overflow:auto;padding-bottom:4px;margin-bottom:12px}.attempt-btn{white-space:nowrap;border:1px solid var(--line);background:var(--surface);border-radius:8px;padding:7px 10px;cursor:pointer}.attempt-btn.active{border-color:var(--accent);background:rgba(110,168,254,.1)}.section-title{display:flex;justify-content:space-between;gap:10px;align-items:center;margin:18px 0 9px}.section-title h3{margin:0}.source-links{display:flex;gap:10px;flex-wrap:wrap}.source-links a{color:var(--accent);font-family:var(--mono);font-size:11px;text-decoration:none}.source-links a:hover{text-decoration:underline}
                .dimension{border-left:3px solid var(--line);background:var(--surface2);border-radius:7px;padding:10px 11px;margin-bottom:7px}.dimension.gating{border-left-color:var(--accent)}.dimension-head{display:flex;justify-content:space-between;gap:12px}.dimension-detail{color:var(--muted);white-space:pre-wrap;margin-top:5px;font-size:12px}.timeline{border-left:2px solid var(--line);margin-left:8px;padding-left:20px}.invocation{position:relative;margin:0 0 12px;background:var(--surface2);border:1px solid var(--line);border-radius:9px}.invocation:before{content:"";position:absolute;left:-26px;top:17px;width:9px;height:9px;border-radius:50%;background:var(--accent);box-shadow:0 0 0 4px var(--bg)}details>summary{cursor:pointer;list-style:none}.invocation>summary{padding:11px 12px;display:flex;gap:10px;align-items:center}.invocation-body{padding:0 12px 12px}.trace-stats{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:8px}.conversation{display:grid;grid-template-columns:1fr 1fr;gap:10px}.turn{border:1px solid var(--line);border-radius:8px;padding:10px;white-space:pre-wrap}.turn.user{background:rgba(110,168,254,.07)}.turn.assistant{background:rgba(139,124,255,.07)}pre{font:12px/1.55 var(--mono);white-space:pre-wrap;word-break:break-word;background:#07101e;border:1px solid var(--line);border-radius:8px;padding:11px;max-height:420px;overflow:auto}.json-tree{font:12px/1.55 var(--mono);background:#07101e;border:1px solid var(--line);border-radius:8px;padding:10px;max-height:560px;overflow:auto}.json-tree details{margin-left:13px}.json-key{color:#82cfff}.json-string{color:#9fe6a0}.json-number{color:#ffcb75}.json-bool{color:#d7a6ff}.json-null{color:var(--faint)}.empty{padding:28px;text-align:center;color:var(--muted)}.notice{border:1px solid rgba(248,197,85,.35);background:rgba(248,197,85,.07);color:var(--warn);padding:10px 12px;border-radius:8px}.raw-toggle{display:block;border:1px solid var(--line);border-radius:9px;padding:10px 12px;background:var(--surface2)}
                @media(max-width:1180px){.summary-grid{grid-template-columns:repeat(3,1fr)}.header-facts{grid-template-columns:repeat(3,1fr)}.data-grid{grid-template-columns:repeat(2,1fr)}}@media(max-width:820px){.appbar{height:auto;padding:12px 16px;position:static}.dashboard{display:block}.rail{position:static;height:auto;border-right:0;border-bottom:1px solid var(--line)}.case-list{display:flex;overflow:auto}.case-item{min-width:250px}.content{padding:16px}.tabs{top:0}.summary-grid{grid-template-columns:repeat(2,1fr)}.span-8,.span-7,.span-6,.span-5,.span-4{grid-column:span 12}.conversation{grid-template-columns:1fr}}@media(max-width:480px){.summary-grid,.header-facts,.trace-stats{grid-template-columns:1fr 1fr}.content{padding:12px}.run-state .muted{display:none}}
              </style>
            </head>
            <body>
              <div class="shell">
                <header class="appbar">
                  <div class="brand"><div class="logo">M</div><div><h1>MadaCode Eval 诊断控制台</h1><div class="sub" id="run-meta"></div></div></div>
                  <div class="run-state"><span class="muted" id="schema-meta"></span><span id="run-badge"></span></div>
                </header>
                <div class="dashboard">
                  <aside class="rail">
                    <div class="rail-head"><div class="rail-title"><strong>Case 导航</strong><span class="muted" id="case-count"></span></div><input class="search" id="case-search" placeholder="搜索 Case / mode / capability"><div class="filters" id="case-filters"></div></div>
                    <div class="case-list" id="case-list"></div>
                  </aside>
                  <main class="content">
                    <section class="summary-grid" id="run-summary"></section>
                    <section id="case-shell"><div class="empty">暂无已完成 Case</div></section>
                  </main>
                </div>
              </div>
              <script id="eval-data" type="application/json">__EVAL_DATA__</script>
              <script>
                "use strict";
                const payload=JSON.parse(document.getElementById("eval-data").textContent);
                const root=payload.report;
                const cases=payload.cases||[];
                let selectedCase=0,selectedAttempt=0,selectedTab="overview",caseFilter="ALL",searchText="";
                const $=id=>document.getElementById(id);
                const esc=value=>String(value??"").replace(/[&<>"']/g,char=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[char]));
                const fmt=value=>new Intl.NumberFormat("zh-CN").format(Number(value)||0);
                const ms=value=>Number(value)>=1000?(Number(value)/1000).toFixed(2)+" 秒":fmt(value)+" ms";
                const pct=value=>(Number(value||0)*100).toFixed(Number(value||0)*100%1?1:0)+"%";
                const usd=value=>value==null?"未配置":"$"+Number(value).toFixed(6);
                const zh=value=>({PASS:"通过",FAIL:"失败",PASS_AT_K:"通过",INFRA_ERROR:"基础设施错误",SKIPPED:"跳过",COMPLETED:"已完成",RUNNING:"运行中",ABORTED:"已中止",ERROR:"错误",NOT_RUN:"未执行",OK:"正常",TIMED_OUT:"超时",CRASHED:"崩溃",CANCELLED:"取消",CREATED:"创建",MODIFIED:"修改",DELETED:"删除"}[value]||value||"—");
                const tone=value=>["PASS","PASS_AT_K","COMPLETED","OK"].includes(value)?"good":["FAIL","INFRA_ERROR","ABORTED","ERROR","CRASHED","TIMED_OUT"].includes(value)?"bad":["RUNNING"].includes(value)?"warn":"neutral";
                const badge=value=>'<span class="badge '+tone(value)+'">'+esc(zh(value))+"</span>";
                const token=m=>m?.tokenUsage?.totalTokens||0;
                const datum=(label,value,note)=>'<div class="datum"><span>'+esc(label)+'</span><b>'+value+'</b>'+(note?'<small class="faint">'+esc(note)+"</small>":"")+"</div>";
                const sourceLinks=(caseData,attemptData)=>{let html='<div class="source-links"><a href="report.json">report.json</a><a href="'+esc(caseData.sourcePath)+'">case-report.json</a>';if(attemptData)html+='<a href="'+esc(attemptData.resultPath)+'">result.json</a><a href="'+esc(attemptData.tracePath)+'">trace.json</a><a href="'+esc(attemptData.verifyPath)+'">verify.txt</a>';return html+"</div>"};
                function renderChrome(){
                  const run=root.run,m=run.totalMetrics||{},duration=cases.reduce((sum,item)=>sum+(item.report.evalCase.totalDurationMs||0),0);
                  $("run-meta").textContent=run.completedCases+"/"+run.plannedCases+" 个 Case · "+new Date(run.updatedAt).toLocaleString("zh-CN");
                  $("schema-meta").textContent="schema v"+root.schemaVersion+" · "+(root.environment?.provider||"provider 未知")+" / "+(root.environment?.model||"model 未知");
                  $("run-badge").innerHTML=badge(run.status);
                  const cards=[
                    ["Gate 稳定通过",run.stableCases+"/"+run.totalCases,pct(run.totalCases?run.stableCases/run.totalCases:0)],
                    ["pass@k 通过",run.casesPassed+"/"+run.measuredCases,"测量 Case"],
                    ["Attempt 通过",run.attemptPasses+"/"+run.attemptValid,pct(run.attemptValid?run.attemptPasses/run.attemptValid:0)+" · Wilson "+run.attemptPassRateWilson95.lowerPercent+"%–"+run.attemptPassRateWilson95.upperPercent+"%"],
                    ["Infra / 跳过",run.attemptInfraErrors+" / "+run.skippedCases,run.infraOnlyCases+" 个纯 Infra Case"],
                    ["Token / 工具",fmt(token(m))+" / "+fmt(m.toolCalls),"总迭代 "+fmt(m.totalIterations)],
                    ["耗时 / 成本",ms(duration)+" / "+usd(run.costEstimate?.totalUsd),"全部已完成 Case"]
                  ];
                  $("run-summary").innerHTML=cards.map(card=>'<article class="metric-card"><div class="metric-label">'+esc(card[0])+'</div><div class="metric-value">'+esc(card[1])+'</div><div class="metric-note">'+esc(card[2])+"</div></article>").join("");
                  $("case-filters").innerHTML=["ALL","PASS","FAIL","INFRA_ERROR","SKIPPED"].map(value=>'<button class="filter '+(value===caseFilter?"active":"")+'" data-filter="'+value+'">'+(value==="ALL"?"全部":zh(value))+"</button>").join("");
                  $("case-filters").onclick=event=>{const button=event.target.closest("[data-filter]");if(!button)return;caseFilter=button.dataset.filter;renderFilters();renderCaseList()};
                  $("case-search").oninput=event=>{searchText=event.target.value.trim().toLowerCase();renderCaseList()};
                  renderCaseList();
                }
                function renderFilters(){
                  document.querySelectorAll("[data-filter]").forEach(el=>el.classList.toggle("active",el.dataset.filter===caseFilter));
                }
                function filteredCases(){
                  return cases.map((item,index)=>({item,index})).filter(entry=>{const c=entry.item.report.evalCase;const statusMatch=caseFilter==="ALL"||(caseFilter==="PASS"&&c.gateVerdict==="PASS")||(caseFilter==="FAIL"&&c.gateVerdict==="FAIL")||(caseFilter==="INFRA_ERROR"&&c.gateVerdict==="INFRA_ERROR")||(caseFilter==="SKIPPED"&&c.skipped);const text=[c.id,c.mode,...(c.capabilities||[])].join(" ").toLowerCase();return statusMatch&&(!searchText||text.includes(searchText))});
                }
                function renderCaseList(){
                  const visible=filteredCases();$("case-count").textContent=visible.length+"/"+cases.length;
                  $("case-list").innerHTML=visible.map(entry=>{const c=entry.item.report.evalCase;return '<button class="case-item '+(entry.index===selectedCase?"active":"")+'" data-case="'+entry.index+'" title="'+esc(c.id)+'"><div class="case-top"><span class="case-id">'+esc(c.id)+'</span>'+badge(c.gateVerdict)+'</div><div class="case-meta"><span>'+esc(c.mode)+'</span><span>'+c.passes+"/"+c.validAttempts+'</span><span>'+fmt(token(c.totalMetrics))+" token</span><span>"+ms(c.totalDurationMs)+"</span></div></button>"}).join("")||'<div class="empty">没有匹配的 Case</div>';
                  $("case-list").onclick=event=>{const button=event.target.closest("[data-case]");if(button)selectCase(Number(button.dataset.case))};
                }
                function selectCase(index){
                  if(!cases[index])return;
                  selectedCase=index;selectedAttempt=0;selectedTab="overview";
                  document.querySelectorAll("[data-case]").forEach(el=>el.classList.toggle("active",Number(el.dataset.case)===index));
                  renderCaseShell();
                }
                function renderCaseShell(){
                  const caseData=cases[selectedCase],report=caseData.report,c=report.evalCase;
                  $("case-shell").innerHTML='<section class="case-header"><div class="case-heading"><div><h2>'+esc(c.id)+'</h2><div class="chips"><span class="chip">'+esc(c.mode)+'</span>'+(c.capabilities||[]).map(x=>'<span class="chip">'+esc(x)+'</span>').join("")+'</div></div><div>'+badge(c.gateVerdict)+" "+badge(c.passAtKVerdict)+'</div></div><div class="header-facts">'+
                    datum("Gate",badge(c.gateVerdict))+datum("pass@k",badge(c.passAtKVerdict))+datum("k / N",c.passes+" / "+c.validAttempts)+datum("Pass rate",pct(c.passRate))+datum("Wilson 95% CI",c.passRateWilson95.lowerPercent+"%–"+c.passRateWilson95.upperPercent+"%")+datum("总耗时",ms(c.totalDurationMs))+
                    '</div></section><nav class="tabs" id="case-tabs"></nav><section id="tab-content"></section>';
                  renderTabs();renderTab();
                }
                function renderTabs(){
                  const tabs=[["overview","Case 总览"],["attempts","Attempt 诊断"],["trace","Trace 下钻"],["environment","环境与复现"]];
                  $("case-tabs").innerHTML=tabs.map(tab=>'<button class="tab '+(selectedTab===tab[0]?"active":"")+'" data-tab="'+tab[0]+'">'+tab[1]+"</button>").join("");
                  $("case-tabs").onclick=event=>{const button=event.target.closest("[data-tab]");if(!button||button.dataset.tab===selectedTab)return;selectedTab=button.dataset.tab;document.querySelectorAll("[data-tab]").forEach(el=>el.classList.toggle("active",el.dataset.tab===selectedTab));renderTab()};
                }
                function renderTab(){({overview:renderOverview,attempts:renderAttempts,trace:renderTrace,environment:renderEnvironment}[selectedTab]||renderOverview)()}
                function metricGrid(m){
                  const t=m?.tokenUsage||{};
                  return '<div class="data-grid">'+datum("Control iterations",fmt(m?.controlIterations))+datum("Worker iterations",fmt(m?.workerIterations))+datum("Total iterations",fmt(m?.totalIterations))+datum("Worker cycles",fmt(m?.workerCycles))+datum("Tool calls",fmt(m?.toolCalls))+datum("Input token",fmt(t.inputTokens))+datum("Output token",fmt(t.outputTokens))+datum("Cache create",fmt(t.cacheCreationTokens))+datum("Cache read",fmt(t.cacheReadTokens))+datum("Total token",fmt(t.totalTokens))+"</div>";
                }
                function sourceRows(c){
                  if(!c.repository)return "";
                  return '<tr><td>Repository</td><td>'+esc(c.repository)+'</td></tr><tr><td>Base commit</td><td>'+esc(c.baseCommit||"—")+'</td></tr><tr><td>Workspace protocol</td><td>'+esc(c.workspaceProtocol||"—")+'</td></tr>';
                }
                function renderOverview(){
                  const d=cases[selectedCase],r=d.report,c=r.evalCase,total=Math.max(1,c.validAttempts);
                  const dimensions=(r.dimensions||[]).map(x=>'<tr><td class="mono">'+esc(x.dimension)+'</td><td class="good">'+x.pass+'</td><td class="bad">'+x.fail+'</td><td class="bad">'+x.error+'</td><td class="muted">'+x.notRun+'</td><td><div class="bar"><i style="width:'+Math.round(x.pass*100/total)+'%"></i></div></td></tr>').join("");
                  $("tab-content").innerHTML='<div class="panel-grid"><article class="panel span-7"><h3>判定与统计</h3><div class="data-grid">'+datum("Samples",fmt(c.samples))+datum("Valid attempts",fmt(c.validAttempts))+datum("Passes",fmt(c.passes))+datum("Infra errors",fmt(c.infraErrors))+datum("Stable",c.stable?"是":"否")+datum("Passed",c.passed?"是":"否")+datum("Skipped",c.skipped?"是":"否")+datum("Pass rate",pct(c.passRate))+'</div>'+(c.skipDetail?'<div class="notice" style="margin-top:10px">'+esc(c.skipReason)+" · "+esc(c.skipDetail)+"</div>":"")+'</article><article class="panel span-5"><h3>身份与来源</h3><table class="kv"><tr><td>Case hash</td><td>'+esc(c.caseHash)+'</td></tr>'+sourceRows(c)+'<tr><td>schemaVersion</td><td>'+esc(r.schemaVersion)+'</td></tr><tr><td>Case report</td><td>'+esc(d.sourcePath)+'</td></tr></table><div style="margin-top:10px">'+sourceLinks(d)+'</div></article><article class="panel span-7"><h3>维度聚合（跨 Attempt）</h3><div class="table-wrap"><table><thead><tr><th>维度</th><th>PASS</th><th>FAIL</th><th>ERROR</th><th>NOT RUN</th><th>通过占比</th></tr></thead><tbody>'+dimensions+'</tbody></table></div></article><article class="panel span-5"><h3>Case 资源消耗</h3>'+metricGrid(c.totalMetrics)+'<div class="section-title"><h3>成本估算</h3></div><div class="data-grid">'+datum("Input USD",usd(c.costEstimate?.inputUsd))+datum("Output USD",usd(c.costEstimate?.outputUsd))+datum("Total USD",usd(c.costEstimate?.totalUsd))+'</div><div class="mono muted" style="margin-top:9px">未配置价格时保留 token 事实，不推测成本。</div></article></div>';
                }
                function attemptSelector(d){
                  return '<div class="attempt-strip" id="attempt-strip">'+(d.attempts||[]).map((a,index)=>'<button class="attempt-btn '+(index===selectedAttempt?"active":"")+'" data-attempt="'+index+'">#'+a.result.attemptNumber+" "+zh(a.result.verdict)+" · "+ms(a.result.durationMs)+"</button>").join("")+"</div>";
                }
                function bindAttemptSelector(callback){
                  const strip=$("attempt-strip");if(!strip)return;strip.onclick=event=>{const button=event.target.closest("[data-attempt]");if(!button)return;selectedAttempt=Number(button.dataset.attempt);strip.querySelectorAll("[data-attempt]").forEach(el=>el.classList.toggle("active",Number(el.dataset.attempt)===selectedAttempt));callback()};
                }
                function renderAttempts(){
                  const d=cases[selectedCase];
                  const comparison=(d.attempts||[]).map(a=>{const r=a.result;return '<tr><td class="mono">#'+r.attemptNumber+'</td><td>'+badge(r.verdict)+'</td><td>'+badge(r.harnessStatus)+'</td><td>'+badge(r.executionStatus)+'</td><td>'+badge(r.judgeStatus)+'</td><td>'+ms(r.executionDurationMs)+'</td><td>'+ms(r.judgeDurationMs)+'</td><td>'+fmt(r.metrics?.toolCalls)+'</td><td>'+fmt(token(r.metrics))+'</td></tr>'}).join("");
                  $("tab-content").innerHTML=attemptSelector(d)+'<article class="panel" style="margin-bottom:12px"><h3>Attempt 横向对比</h3><div class="table-wrap"><table><thead><tr><th>#</th><th>Verdict</th><th>Harness</th><th>Execution</th><th>Judge</th><th>执行耗时</th><th>Judge 耗时</th><th>工具</th><th>Token</th></tr></thead><tbody>'+comparison+'</tbody></table></div></article><div id="attempt-content"></div>';
                  bindAttemptSelector(renderAttemptDetail);renderAttemptDetail();
                }
                function renderAttemptDetail(){
                  const d=cases[selectedCase],a=d.attempts[selectedAttempt];if(!a){$("attempt-content").innerHTML='<div class="empty">没有 Attempt</div>';return}const r=a.result;
                  const dimensions=(r.dimensions||[]).map(x=>'<div class="dimension '+(x.gating?"gating":"")+'"><div class="dimension-head"><strong class="mono">'+esc(x.dimension)+'</strong><span>'+badge(x.status)+" "+(x.gating?'<span class="chip">Gate</span>':"")+'</span></div><div class="dimension-detail">'+esc(x.detail)+"</div></div>").join("");
                  const files=(r.artifacts?.files||[]).map(x=>"<li class=mono>"+esc(x)+"</li>").join("")||"<li>无</li>",warnings=(r.artifacts?.warnings||[]).map(x=>"<li>"+esc(x)+"</li>").join("")||"<li>无</li>";
                  $("attempt-content").innerHTML='<div class="panel-grid"><article class="panel span-12"><h3>状态与耗时</h3><div class="data-grid">'+datum("Verdict",badge(r.verdict))+datum("Harness",badge(r.harnessStatus))+datum("Execution",badge(r.executionStatus))+datum("Judge",badge(r.judgeStatus))+datum("执行耗时",ms(r.executionDurationMs))+datum("Judge 耗时",ms(r.judgeDurationMs))+datum("总耗时",ms(r.durationMs))+datum("Started at",esc(r.startedAt||"—"))+'</div></article><article class="panel span-7"><h3>评分维度</h3>'+dimensions+'</article><article class="panel span-5"><h3>Attempt metrics</h3>'+metricGrid(r.metrics)+'</article><article class="panel span-6"><h3>执行说明</h3><pre>'+esc(r.executionDetail||"无")+'</pre><h3>Terminal summary</h3><pre>'+esc(r.terminalSummary||"无")+'</pre></article><article class="panel span-6"><h3>产物与写入状态</h3><div class="mono muted">'+esc(r.artifacts?.directory||"")+"</div><h3>Files</h3><ul>"+files+"</ul><h3>Warnings</h3><ul>"+warnings+"</ul>"+sourceLinks(d,a)+"</article></div>";
                }
                function renderTrace(){
                  const d=cases[selectedCase];
                  $("tab-content").innerHTML=attemptSelector(d)+'<div id="trace-content"></div>';
                  bindAttemptSelector(renderTraceDetail);renderTraceDetail();
                }
                function renderTraceDetail(){
                  const d=cases[selectedCase],a=d.attempts[selectedAttempt];if(!a){$("trace-content").innerHTML='<div class="empty">没有 Trace</div>';return}const t=a.trace||{},inv=t.invocations||[],files=t.fileEffects||[],users=t.userTurns||[],assistants=t.assistantTurns||[];
                  const timeline=inv.map((x,index)=>'<details class="invocation" data-invocation="'+index+'"><summary><span class="mono faint">#'+esc(x.ordinal??index+1)+'</span><strong class="mono">'+esc(x.name)+'</strong><span class="chip">'+esc(x.phase||"UNKNOWN")+'</span>'+(x.resultTruncated?'<span class="badge warn">输出已截断</span>':"")+'</summary><div class="invocation-body"><div class="muted">展开后加载输入、输出与访问证据</div></div></details>').join("")||'<div class="empty">没有记录工具调用</div>';
                  const effects=files.map(x=>'<tr><td class="mono wrap">'+esc(x.relPath)+'</td><td>'+badge(x.kind)+'</td></tr>').join("")||'<tr><td colspan="2" class="muted">没有文件变化</td></tr>';
                  const turns=Math.max(users.length,assistants.length),conversation=Array.from({length:turns},(_,i)=>'<div class="turn user"><strong>用户 #'+(i+1)+'</strong><br>'+esc(users[i]||"—")+'</div><div class="turn assistant"><strong>Agent #'+(i+1)+'</strong><br>'+esc(assistants[i]||"—")+"</div>").join("")||'<div class="muted">没有记录对话轮次</div>';
                  $("trace-content").innerHTML='<div class="panel-grid"><article class="panel span-12"><div class="trace-stats">'+datum("Trace available",t.available?"是":"否")+datum("Tool invocations",fmt(inv.length))+datum("File effects",fmt(files.length))+datum("User turns",fmt(users.length))+datum("Assistant turns",fmt(assistants.length))+'</div></article><article class="panel span-8"><div class="section-title"><h3>工具调用时间线</h3><span class="muted">按 ordinal 顺序</span></div><div class="timeline">'+timeline+'</div></article><article class="panel span-4"><h3>文件变化</h3><div class="table-wrap"><table><thead><tr><th>路径</th><th>变化</th></tr></thead><tbody>'+effects+'</tbody></table></div><h3>Verify 原始输出</h3><pre>'+esc(a.verify)+'</pre></article><article class="panel span-12"><h3>对话记录</h3><div class="conversation">'+conversation+'</div></article><article class="panel span-12"><h3>最终回复</h3><pre>'+esc(t.finalText||a.result.terminalSummary||"无")+'</pre></article><article class="panel span-12"><details class="raw-toggle" id="raw-trace"><summary><strong>完整 trace.json 结构化浏览</strong> <span class="muted">按需展开，避免阻塞 Case 切换</span></summary><div id="raw-trace-body" style="margin-top:10px"></div></details><div style="margin-top:12px">'+sourceLinks(d,a)+"</div></article></div>";
                  document.querySelectorAll("[data-invocation]").forEach(el=>el.addEventListener("toggle",()=>{if(el.open&&!el.dataset.loaded){hydrateInvocation(el,inv[Number(el.dataset.invocation)]);el.dataset.loaded="1"}}));
                  $("raw-trace").addEventListener("toggle",event=>{if(event.currentTarget.open&&!event.currentTarget.dataset.loaded){$("raw-trace-body").innerHTML='<div class="json-tree">'+jsonTree(t,"trace",0)+"</div>";event.currentTarget.dataset.loaded="1"}});
                }
                function hydrateInvocation(element,invocation){
                  const body=element.querySelector(".invocation-body"),access=invocation.accessEvidence||[];
                  body.innerHTML='<div class="data-grid">'+datum("Ordinal",fmt(invocation.ordinal))+datum("Phase",esc(invocation.phase||"—"))+datum("Result chars",fmt(invocation.originalResultChars))+datum("Truncated",invocation.resultTruncated?"是":"否")+'</div><h3>Input</h3><div class="json-tree">'+jsonTree(parseJson(invocation.inputJson),null,0)+'</div><h3>Result</h3><div class="json-tree">'+jsonTree(parseJson(invocation.resultJson),null,0)+'</div><h3>Access evidence</h3>'+(access.length?'<table><thead><tr><th>Path</th><th>Source</th><th>Heuristic</th></tr></thead><tbody>'+access.map(x=>'<tr><td class="mono wrap">'+esc(x.path)+'</td><td>'+esc(x.source)+'</td><td>'+(x.heuristic?"是":"否")+"</td></tr>").join("")+"</tbody></table>":'<div class="muted">没有访问证据</div>');
                }
                function parseJson(value){if(typeof value!=="string")return value;try{return JSON.parse(value)}catch(ignored){return value}}
                function jsonTree(value,key,depth){
                  const keyHtml=key==null?"":'<span class="json-key">'+esc(key)+"</span>: ";
                  if(value===null)return keyHtml+'<span class="json-null">null</span>';
                  if(Array.isArray(value)){if(!value.length)return keyHtml+"[]";return '<details '+(depth<1?"open":"")+'><summary>'+keyHtml+"Array("+value.length+")</summary>"+value.map((item,index)=>jsonTree(item,String(index),depth+1)).join("")+"</details>"}
                  if(typeof value==="object"){const entries=Object.entries(value);if(!entries.length)return keyHtml+"{}";return '<details '+(depth<1?"open":"")+'><summary>'+keyHtml+"Object("+entries.length+")</summary>"+entries.map(entry=>jsonTree(entry[1],entry[0],depth+1)).join("")+"</details>"}
                  if(typeof value==="string")return '<div>'+keyHtml+'<span class="json-string">"'+esc(value)+'"</span></div>';
                  if(typeof value==="number")return '<div>'+keyHtml+'<span class="json-number">'+esc(value)+"</span></div>";
                  return '<div>'+keyHtml+'<span class="json-bool">'+esc(value)+"</span></div>";
                }
                function renderEnvironment(){
                  const d=cases[selectedCase],env=d.report.environment||{},rows=Object.entries(env).map(entry=>'<tr><td>'+esc(entry[0])+'</td><td>'+esc(Array.isArray(entry[1])?entry[1].join("\\n"):entry[1])+"</td></tr>").join("");
                  const mismatch=(root.run.manifestMismatches||[]).length?'<div class="notice">Run 中存在环境字段不一致：'+esc(root.run.manifestMismatches.join("、"))+"</div>":'<div class="chip good">环境字段在 Run 内一致</div>';
                  $("tab-content").innerHTML='<div class="panel-grid"><article class="panel span-8"><h3>执行环境与可复现性</h3><table class="kv">'+rows+'</table></article><article class="panel span-4"><h3>可信性检查</h3>'+mismatch+'<div style="margin-top:12px">'+datum("Trusted measurement",env.trustedMeasurement?"是":"否")+datum("Isolation",esc(env.isolation||"—"))+datum("Judge visibility",esc(env.judgeVisibility||"—"))+datum("Network",esc(env.networkAccess||"—"))+'</div><h3>来源</h3>'+sourceLinks(d)+"</article></div>";
                }
                renderChrome();if(cases.length)renderCaseShell();
              </script>
            </body>
            </html>
            """;
}
