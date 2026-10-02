"use strict";

const $ = (id) => document.getElementById(id);
const stateLabels = { PENDING: "待建索引", INDEXING: "正在索引", READY: "可检索", FAILED: "索引失败" };
let busy = false;
let retryAt = 0;
function node(tag, text, css) {
  const el = document.createElement(tag);
  el.textContent = text; // 原文、标题与模型内容一律作为纯文本，Markdown 不执行 HTML。
  if (css) el.className = css;
  return el;
}
function message(text, error = false) {
  $("knowledge-message").textContent = text;
  $("knowledge-message").classList.toggle("error", error);
}
function controls() {
  const cooling = Date.now() < retryAt;
  document.querySelectorAll("button, #import-form input, #import-form textarea, #search-query").forEach(el => {
    el.disabled = busy || (cooling && (el.dataset.remote === "true" || ["import-submit", "search-submit"].includes(el.id)));
  });
  $("search-submit").textContent = cooling ? `${Math.ceil((retryAt - Date.now()) / 1000)} 秒后可检索` : "检索资料 →";
}
async function request(url, body) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), body === undefined ? 15000 : 65000);
  try {
    const response = await fetch(url, { signal: controller.signal,
      ...(body === undefined ? {} : { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }) });
    const data = await response.json();
    if (!response.ok) {
      const seconds = Number(data.retryAfterSeconds || response.headers.get("Retry-After") || 0);
      if (seconds > 0) retryAt = Date.now() + seconds * 1000;
      throw new Error(data.detail || "请求失败，请检查输入或稍后重试。");
    }
    return data;
  } catch (error) {
    if (error.name === "AbortError" || error instanceof TypeError) throw new Error("请求未能确认完成，请刷新资料列表核对。原文和已完成的索引会保留，不会自动重试模型请求。");
    throw error;
  } finally { clearTimeout(timer); }
}
async function operation(action) {
  if (busy) return;
  busy = true; controls();
  try { await action(); } catch (error) { message(error.message, true); }
  finally { busy = false; controls(); }
}
function button(text, action, remote = false) {
  const el = node("button", text, "button secondary");
  el.type = "button"; el.dataset.remote = String(remote);
  el.addEventListener("click", () => operation(action)); return el;
}
async function loadList() {
  const docs = await request("/api/knowledge/documents");
  $("document-list").replaceChildren();
  if (!docs.length) $("document-list").append(node("p", "还没有学习资料。从左侧导入第一份笔记。", "history-empty"));
  for (const doc of docs) {
    const card = node("article", "", "document-card");
    const status = doc.state === "READY" && !doc.compatible ? "模型已变化 · 需重建" : stateLabels[doc.state];
    card.append(node("h3", doc.title), node("p", `${status} · ${doc.chunkCount} 个已存片段 · #${doc.id}`), node("p", doc.message));
    const actions = node("div", "", "document-actions");
    actions.append(button("查看原文与分块", () => showDocument(doc.id)));
    if (doc.state !== "READY" || !doc.compatible) actions.append(button("建立 / 重试索引", () => indexDocument(doc.id), true));
    card.append(actions); $("document-list").append(card);
  }
  controls();
}
async function indexDocument(id) {
  if (Date.now() < retryAt) return;
  message("原文已保存，正在批量生成向量；请稍候，失败后可以手动重试。");
  try {
    const doc = await request(`/api/knowledge/documents/${id}/index`, {});
    message(`“${doc.title}”索引已就绪，共 ${doc.chunkCount} 个片段。可以测试检索或开始新练习。`);
  } finally { await loadList(); }
}
async function showDocument(id) {
  const data = await request(`/api/knowledge/documents/${id}`);
  $("document-detail").hidden = false;
  $("detail-heading").textContent = data.document.title;
  $("detail-meta").textContent = `资料 #${id} · ${stateLabels[data.document.state]} · 下方是按当前分块规则生成的原文预览；待建索引不代表已可检索。`;
  $("detail-content").textContent = data.content;
  $("detail-chunks").replaceChildren();
  for (const part of data.chunks) {
    const card = node("article", "", "source-card");
    card.append(node("h3", `片段 ${part.position}`), node("p", `字符 [${part.start}, ${part.end})`, "source-meta"), node("pre", part.content));
    $("detail-chunks").append(card);
  }
  $("detail-heading").focus();
}
$("import-form").addEventListener("submit", event => {
  event.preventDefault();
  operation(async () => {
    const title = $("document-title").value.trim(), content = $("document-content").value.trim();
    if (!title || !content) throw new Error("标题和正文不能只包含空格。");
    const doc = await request("/api/knowledge/documents", { title, content });
    await indexDocument(doc.id);
  });
});
$("document-file").addEventListener("change", () => operation(async () => {
  const file = $("document-file").files[0];
  if (!file) return;
  if (!/\.(txt|md)$/i.test(file.name) || file.size > 24000) throw new Error("请选择不超过 24 KB 的 .txt 或 .md 文件。正文最多 6000 字符。");
  const content = new TextDecoder("utf-8", { fatal: true }).decode(await file.arrayBuffer());
  if (content.length > 6000) throw new Error("正文超过 6000 字符，请拆分成多份资料。");
  $("document-title").value = file.name.replace(/\.(txt|md)$/i, "").slice(0, 160);
  $("document-content").value = content;
  message("文件已填入表单，尚未保存或发送。确认内容后点击“保存并建立索引”。");
}));
$("search-form").addEventListener("submit", event => {
  event.preventDefault();
  operation(async () => {
    if (Date.now() < retryAt) return;
    $("search-results").replaceChildren(); message("正在检索学习资料…");
    const result = await request("/api/knowledge/search", { query: $("search-query").value.trim() });
    message(result.message);
    for (const hit of result.hits) {
      const card = node("article", "", "source-card");
      card.append(node("h3", `[${hit.sourceId}] ${hit.title}`), node("p", `片段 ${hit.position} · 字符 [${hit.start}, ${hit.end}) · 余弦相似度 ${hit.score.toFixed(3)}`, "source-meta"), node("pre", hit.content), button("查看完整资料", () => showDocument(hit.documentId)));
      $("search-results").append(card);
    }
    if (!result.hits.length) $("search-results").append(node("p", "没有可展示的片段。可先建立索引，或换一个与资料更相关的问题。", "history-empty"));
  });
});
$("refresh-library").addEventListener("click", () => operation(async () => { await loadList(); message("资料列表已刷新，没有调用模型。"); }));
setInterval(controls, 1000); // 仅更新按钮倒计时，不轮询远程模型。
operation(async () => { await loadList(); message("先导入资料，再测试检索。已完成的历史反馈会保留原样。 "); });
