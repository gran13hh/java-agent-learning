"use strict";
const $ = id => document.getElementById(id);
const runLabels = { PENDING:"等待执行", RUNNING:"正在分析", SUCCEEDED:"已完成", FAILED:"执行失败", RATE_LIMITED:"达到限流", LIMIT_REACHED:"达到调用上限", TIMED_OUT:"超过时间预算", INTERRUPTED:"执行已中断" };
const stepLabels = { STARTED:"处理中", SUCCEEDED:"完成", REJECTED:"已拒绝", FAILED:"未完成" };
const toolLabels = { chat:"询问模型", list_questions:"查询题库", search_knowledge:"检索学习资料", read_interview:"读取选定面试" };
let current = null, busy = false, pollTimer = null, retryAt = 0, pendingCreate = null, pollVersion = 0;
function node(tag, text, css) { const el = document.createElement(tag); el.textContent = text; if (css) el.className = css; return el; }
function error(text) { $("agent-error").textContent = text; $("agent-error").hidden = !text; }
function controls() {
  const cooling = Date.now() < retryAt;
  ["start-agent","new-agent","agent-goal","agent-session","retry-agent","execute-agent"].forEach(id => { $(id).disabled = busy || (cooling && ["start-agent","retry-agent","execute-agent"].includes(id)); });
  document.querySelectorAll(".agent-run-link").forEach(el => { el.disabled = busy; });
  $("start-agent").textContent = cooling ? `${Math.ceil((retryAt-Date.now())/1000)} 秒后可开始` : "开始分析 →";
}
async function request(url, body, timeout = 15000) {
  const controller = new AbortController(), timer = setTimeout(() => controller.abort(), timeout);
  try {
    const response = await fetch(url, {signal:controller.signal, ...(body === undefined ? {} : {method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify(body)})});
    const data = await response.json();
    if (!response.ok) throw new Error(data.detail || "请求失败，请稍后重试。");
    return data;
  } catch (failure) {
    if (failure.name === "AbortError" || failure instanceof TypeError) throw new Error("未能确认执行结果，请刷新运行记录核对；不会自动重发模型请求。");
    throw failure;
  } finally { clearTimeout(timer); }
}
function pretty(value) { if (!value) return "尚无结果"; try { return JSON.stringify(JSON.parse(value), null, 2); } catch (_) { return value; } }
function render() {
  const {run,steps} = current;
  $("agent-form").hidden = true; $("agent-run").hidden = false;
  $("run-label").textContent = `AGENT / #${run.id}${run.sessionId ? ` · 参考面试 #${run.sessionId}` : ""}`;
  $("run-state").textContent = runLabels[run.state]; $("run-goal").textContent = run.prompt; $("run-message").textContent = run.message;
  $("run-counts").replaceChildren(node("span", `模型尝试 ${run.modelCalls} / 3`), node("span", `工具请求 ${run.toolCalls} / 3`));
  $("run-result").hidden = !run.result; $("agent-answer").textContent = run.result || "";
  $("execute-agent").hidden = run.state !== "PENDING";
  $("retry-agent").hidden = ["PENDING","RUNNING","SUCCEEDED"].includes(run.state);
  // 轮询时保留展开状态，避免阅读工具结果被每次刷新打断。
  const opened = new Set([...document.querySelectorAll(".trace-step[open]")].map(el => el.dataset.position));
  $("agent-steps").replaceChildren();
  for (const step of steps) {
    const item = node("details", "", "trace-step" + (["FAILED","REJECTED"].includes(step.state) ? " failed" : ""));
    item.dataset.position = String(step.position); item.open = opened.has(String(step.position));
    let evidence = ""; try { evidence = JSON.parse(step.output || "{}").evidenceId || ""; } catch (_) {}
    item.append(node("summary", `${step.position}. ${toolLabels[step.name] || step.name} · ${stepLabels[step.state]}${evidence ? ` · ${evidence}` : ""}`));
    item.append(node("p", step.createdAt.replace("T"," ") + (step.completedAt ? " → " + step.completedAt.replace("T"," ") : ""), "trace-meta"));
    item.append(node("h4", step.kind === "MODEL" ? "本轮调用" : "申请参数"), node("pre", pretty(step.input)), node("h4","返回结果"), node("pre",pretty(step.output)));
    $("agent-steps").append(item);
  }
  if (!steps.length) $("agent-steps").append(node("p", "尚未开始调用。", "agent-empty"));
  controls();
}
async function loadList() {
  const runs = await request("/api/agent/runs"); $("agent-history").replaceChildren();
  for (const run of runs) {
    const item = node("button", "", "history-item agent-run-link"); item.type = "button";
    item.append(node("strong", run.prompt.length > 38 ? run.prompt.slice(0,38)+"…" : run.prompt), node("span", `#${run.id} · ${runLabels[run.state]}`), node("span",run.createdAt.replace("T"," ").slice(0,16)));
    item.addEventListener("click", () => loadRun(run.id).catch(failure => error(failure.message))); $("agent-history").append(item);
  }
  if (!runs.length) $("agent-history").append(node("p", "还没有运行记录。写下第一个复习目标。", "agent-empty"));
  controls();
}
function stopPoll() { pollVersion++; if (pollTimer) clearTimeout(pollTimer); pollTimer = null; }
function schedulePoll() {
  stopPoll();
  if (!current || current.run.state !== "RUNNING") return;
  const id = current.run.id, version = pollVersion;
  // 只读取本地 MySQL 的执行快照，不执行或重试任何模型/工具。
  pollTimer = setTimeout(async () => {
    try { const detail = await request(`/api/agent/runs/${id}`); if (current?.run.id !== id || version !== pollVersion) return; current = detail; render(); schedulePoll(); }
    catch (_) { error("进度读取暂时失败，可手动刷新；执行请求没有被重发。"); }
  }, 2000);
}
async function loadRun(id) {
  stopPoll(); const version = pollVersion; const detail = await request(`/api/agent/runs/${id}`);
  if (version !== pollVersion) return; current = detail; error(""); render();
  history.replaceState(null,"",`?run=${id}`); schedulePoll();
}
async function executeCurrent() {
  const id = current.run.id;
  // 先启动长请求，再读取进度。界面显示 RUNNING 只是本地占位，真实状态以 GET 为准。
  const execution = request(`/api/agent/runs/${id}/execute`, {}, 110000);
  current.run.state = "RUNNING"; current.run.message = "正在启动，请稍候…"; render(); schedulePoll();
  try {
    current = await execution;
    if (current.run.retryAfterSeconds > 0) retryAt = Date.now() + current.run.retryAfterSeconds * 1000;
    stopPoll(); render();
  } catch (failure) {
    try { await loadRun(id); } catch (_) {}
    throw failure;
  }
}
async function operation(action) {
  if (busy) return; busy = true; error(""); controls();
  try { await action(); } catch (failure) { error(failure.message); }
  finally { busy = false; controls(); try { await loadList(); } catch (_) {} }
}
async function createAndExecute(prompt, sessionId) {
  if (Date.now() < retryAt) throw new Error("请等待限流倒计时结束再尝试。");
  // 保存响应丢失时复用同一 requestId；只要输入变化就用新 ID。
  if (!pendingCreate || pendingCreate.prompt !== prompt || pendingCreate.sessionId !== sessionId)
    pendingCreate = {requestId:crypto.randomUUID(), prompt, sessionId};
  const run = await request("/api/agent/runs", pendingCreate); pendingCreate = null;
  current = {run,steps:[]}; history.replaceState(null,"",`?run=${run.id}`); render(); await executeCurrent();
}
$("agent-form").addEventListener("submit", event => { event.preventDefault(); operation(async () => {
  const prompt = $("agent-goal").value.trim(); if (!prompt) throw new Error("请填写复习目标，不能只有空格。");
  await createAndExecute(prompt, $("agent-session").value ? Number($("agent-session").value) : null);
}); });
$("execute-agent").addEventListener("click", () => operation(executeCurrent));
$("retry-agent").addEventListener("click", () => operation(() => createAndExecute(current.run.prompt,current.run.sessionId)));
$("refresh-run").addEventListener("click", () => { if (current) loadRun(current.run.id).catch(failure => error(failure.message)); });
$("refresh-runs").addEventListener("click", () => loadList().catch(failure => error(failure.message)));
$("new-agent").addEventListener("click", () => { stopPoll(); current = null; pendingCreate = null; error(""); $("agent-run").hidden = true; $("agent-form").hidden = false; history.replaceState(null,"",location.pathname); $("agent-goal").focus(); });
async function setup() {
  try {
    await loadList();
    const sessions = await request("/api/interviews?page=1&size=20");
    for (const session of sessions.items) { const option = node("option", `#${session.id} · ${session.topic || "综合"} · ${session.createdAt.replace("T"," ").slice(0,16)}`); option.value = session.id; $("agent-session").append(option); }
    const id = new URLSearchParams(location.search).get("run"); if (id && /^[1-9]\d*$/.test(id)) await loadRun(id);
  } catch (failure) { error(failure.message); }
}
setInterval(controls, 1000); // 仅按钮倒计时。
setup();
