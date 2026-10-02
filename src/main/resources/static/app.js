"use strict";

// 不依赖前端框架。state 保存页面状态，DOM 只负责展示，数据以服务端响应为准。
const topics = { JAVA: "Java 基础", MYSQL: "MySQL 数据库", REDIS: "Redis 缓存", AGENT: "AI Agent" };
const difficulties = { EASY: "基础", MEDIUM: "进阶", HARD: "挑战" };
const state = { topic: "", page: 1, size: 5, total: 0, selectedId: null, loading: false, saving: false };
const el = Object.fromEntries([
  "topic-nav", "list-heading", "total-label", "question-list", "list-feedback", "range-label", "page-label",
  "prev-page", "next-page", "page-size", "detail-content", "connection-status", "add-question", "question-dialog",
  "question-form", "question-title", "question-topic", "question-difficulty", "question-answer", "title-count",
  "answer-count", "form-error", "submit-question", "close-dialog", "cancel-dialog", "toast"
].map(id => [id, document.getElementById(id)]));
let listVersion = 0;
let detailVersion = 0;
let listController;
let detailController;
let toastTimer;

// 所有来自 API / 用户输入的文本都通过 textContent 放入页面，避免将题目解释成 HTML。
function node(tag, className, text) {
  const result = document.createElement(tag);
  if (className) result.className = className;
  if (text !== undefined) result.textContent = text;
  return result;
}

async function requestJson(path, options = {}) {
  const response = await fetch(path, options);
  const payload = await response.json().catch(() => null);
  // fetch 遇到 400 / 500 并不会自动抛错，必须检查 response.ok。
  if (!response.ok) {
    const fields = payload?.errors?.map(error => error.message).filter(Boolean);
    const message = fields?.length ? fields.join("；") : payload?.detail;
    throw new Error(message || `请求未完成（${response.status}），请稍后重试。`);
  }
  if (!payload) throw new Error("服务返回了无法读取的数据，请稍后重试。");
  return payload;
}

function errorMessage(error) {
  return error instanceof TypeError ? "暂时无法连接服务，请确认应用已启动后重试。" : error.message;
}

function connection(online) {
  el["connection-status"].className = `connection ${online ? "online" : "offline"}`;
  el["connection-status"].replaceChildren(node("i"), document.createTextNode(online ? "题库已连接" : "暂时无法连接"));
}

function tag(topic) {
  return node("span", `topic-tag ${topic.toLowerCase()}`, topics[topic] || topic);
}

function difficulty(level) {
  return node("span", `difficulty ${level.toLowerCase()}`, difficulties[level] || level);
}

function syncPagination() {
  const pages = Math.max(1, Math.ceil(state.total / state.size));
  el["page-label"].textContent = `${state.page} / ${pages}`;
  el["prev-page"].disabled = state.loading || state.page <= 1;
  el["next-page"].disabled = state.loading || state.page >= pages;
  el["range-label"].textContent = state.total
    ? `第 ${(state.page - 1) * state.size + 1}–${Math.min(state.page * state.size, state.total)} 条，共 ${state.total} 条`
    : "还没有题目";
}

function resetDetail() {
  detailVersion += 1;
  detailController?.abort();
  state.selectedId = null;
  const placeholder = node("div", "detail-placeholder");
  const heading = node("h2", "", "选一道题，开始思考");
  heading.id = "detail-heading";
  placeholder.append(node("span", "", "↖"), heading, node("p", "", "点击题目，在这里查看问题和参考答案。"));
  el["detail-content"].replaceChildren(placeholder);
}

function renderRows(items) {
  el["question-list"].replaceChildren();
  for (const question of items) {
    const row = node("button", "question-row");
    row.type = "button";
    row.dataset.id = String(question.id);
    row.setAttribute("aria-pressed", "false");
    const symbol = node("span", "row-symbol", "≡");
    symbol.setAttribute("aria-hidden", "true");
    const content = node("span", "row-content");
    const metadata = node("span", "row-meta");
    metadata.append(tag(question.topic), difficulty(question.difficulty), node("span", "question-id", `#${String(question.id).padStart(3, "0")}`));
    content.append(node("span", "row-title", question.title), metadata);
    const arrow = node("span", "row-arrow", "↗");
    arrow.setAttribute("aria-hidden", "true");
    row.append(symbol, content, arrow);
    row.addEventListener("click", () => selectQuestion(question.id, true));
    el["question-list"].append(row);
  }
}

function feedback(title, message, buttonText, action) {
  const button = node("button", "button secondary", buttonText);
  button.type = "button";
  button.addEventListener("click", action);
  el["list-feedback"].replaceChildren(node("span", "empty-symbol", "◌"), node("h3", "", title), node("p", "", message), button);
  el["list-feedback"].hidden = false;
  el["question-list"].hidden = true;
}

async function loadQuestions(selectId) {
  const version = ++listVersion;
  listController?.abort();
  listController = new AbortController();
  const controller = listController;
  const timeout = setTimeout(() => controller.abort("timeout"), 12000);
  state.loading = true;
  resetDetail();
  el["question-list"].hidden = false;
  el["question-list"].setAttribute("aria-busy", "true");
  el["list-feedback"].hidden = true;
  el["list-heading"].textContent = state.topic ? topics[state.topic] : "全部题目";
  el["total-label"].textContent = "正在加载";
  el["question-list"].replaceChildren();
  for (let i = 0; i < 3; i++) {
    const skeleton = node("div", "skeleton-row");
    skeleton.setAttribute("aria-hidden", "true");
    skeleton.append(node("div", "skeleton long"), node("div", "skeleton short"));
    el["question-list"].append(skeleton);
  }
  for (const button of el["topic-nav"].querySelectorAll("button")) {
    const active = button.dataset.topic === state.topic;
    button.classList.toggle("active", active);
    button.setAttribute("aria-pressed", String(active));
  }
  syncPagination();
  el["range-label"].textContent = "正在读取题库…";
  try {
    const query = new URLSearchParams({ page: state.page, size: state.size });
    if (state.topic) query.set("topic", state.topic);
    const result = await requestJson(`/api/questions?${query}`, { signal: controller.signal });
    // 切换主题时，旧请求即使稍后才返回，也不允许覆盖最新页面。
    if (version !== listVersion) return;
    state.total = result.total;
    const lastPage = Math.max(1, Math.ceil(state.total / state.size));
    if (state.page > lastPage) {
      state.page = lastPage;
      await loadQuestions(selectId);
      return;
    }
    connection(true);
    el["total-label"].textContent = `${state.total} 道题`;
    renderRows(result.items);
    if (result.items.length === 0) {
      feedback("这里还没有题目", state.topic ? `把正在学习的 ${topics[state.topic]} 问题记下来，从第一道开始。` : "把今天学到的问题记下来，慢慢建立自己的知识库。", "新增第一道题", openDialog);
    } else {
      const selected = result.items.find(question => question.id === selectId) || result.items[0];
      selectQuestion(selected.id);
    }
  } catch (error) {
    if (version !== listVersion) return;
    connection(false);
    state.total = 0;
    el["total-label"].textContent = "加载失败";
    const message = controller.signal.reason === "timeout" ? "加载时间有点久，请稍后重试。" : errorMessage(error);
    feedback("题库暂时没有加载成功", message, "重新加载", () => loadQuestions());
  } finally {
    clearTimeout(timeout);
    if (version === listVersion) {
      state.loading = false;
      el["question-list"].setAttribute("aria-busy", "false");
      syncPagination();
      if (el["total-label"].textContent === "加载失败") {
        el["range-label"].textContent = "等待重新加载";
        el["prev-page"].disabled = true;
        el["next-page"].disabled = true;
      }
    }
  }
}

async function selectQuestion(id, revealOnSmallScreen = false) {
  const version = ++detailVersion;
  detailController?.abort();
  detailController = new AbortController();
  const controller = detailController;
  const timeout = setTimeout(() => controller.abort("timeout"), 12000);
  state.selectedId = id;
  for (const row of el["question-list"].querySelectorAll("button")) {
    const selected = Number(row.dataset.id) === id;
    row.classList.toggle("selected", selected);
    row.setAttribute("aria-pressed", String(selected));
  }
  el["detail-content"].replaceChildren(node("p", "detail-loading", "正在打开这道题…"));
  try {
    const question = await requestJson(`/api/questions/${id}`, { signal: controller.signal });
    if (version !== detailVersion) return;
    const meta = node("div", "detail-meta");
    meta.append(tag(question.topic), difficulty(question.difficulty));
    const heading = node("h2", "", question.title);
    heading.id = "detail-heading";
    const date = String(question.createdAt || "").slice(0, 10).replaceAll("-", ".");
    const created = node("p", "detail-created", `题目 #${question.id}${date ? ` · 收录于 ${date}` : ""}`);
    const recall = node("div", "recall-card");
    recall.append(node("strong", "", "先给自己一点思考时间"), node("p", "", "试着说出核心概念、背后的原因，再举一个你理解的例子。"));
    const toggle = node("button", "answer-toggle", "展开参考答案 ↓");
    toggle.type = "button";
    toggle.setAttribute("aria-expanded", "false");
    toggle.setAttribute("aria-controls", "reference-answer");
    const answer = node("div", "answer-text");
    answer.id = "reference-answer";
    answer.hidden = true;
    answer.append(node("span", "answer-title", "参考答案"), node("p", "", question.referenceAnswer));
    toggle.addEventListener("click", () => {
      answer.hidden = !answer.hidden;
      recall.hidden = !answer.hidden;
      toggle.textContent = answer.hidden ? "展开参考答案 ↓" : "收起参考答案 ↑";
      toggle.setAttribute("aria-expanded", String(!answer.hidden));
    });
    el["detail-content"].replaceChildren(meta, heading, created, recall, toggle, answer);
    // 窄屏详情位于列表下方，用户主动选题后滚动过去；自动选题时不改变滚动位置。
    if (revealOnSmallScreen && window.matchMedia("(max-width: 950px)").matches) {
      document.querySelector(".detail-panel").scrollIntoView({
        behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "instant" : "smooth",
        block: "start"
      });
    }
  } catch (error) {
    if (version !== detailVersion) return;
    const retry = node("button", "answer-toggle", "重试打开题目");
    retry.type = "button";
    retry.addEventListener("click", () => selectQuestion(id));
    el["detail-content"].replaceChildren(node("p", "detail-error", controller.signal.reason === "timeout" ? "题目加载超时，请重试。" : errorMessage(error)), retry);
  } finally {
    clearTimeout(timeout);
  }
}

function updateCounts() {
  el["title-count"].textContent = `${el["question-title"].value.length} / 200`;
  el["answer-count"].textContent = `${el["question-answer"].value.length} / 10000`;
}

function openDialog() {
  if (el["question-dialog"].open) return;
  // 取消后保留未提交草稿；只有成功保存才清空。
  if (!el["question-title"].value && !el["question-answer"].value) {
    el["question-topic"].value = state.topic || "JAVA";
  }
  el["form-error"].hidden = true;
  el["question-dialog"].showModal();
}

function setSaving(saving) {
  state.saving = saving;
  el["submit-question"].disabled = saving;
  el["close-dialog"].disabled = saving;
  el["cancel-dialog"].disabled = saving;
  for (const field of el["question-form"].querySelectorAll("textarea, select")) field.disabled = saving;
  el["submit-question"].replaceChildren(document.createTextNode(saving ? "正在保存…" : "保存到题库"));
  if (!saving) {
    const arrow = node("span", "", "↗");
    arrow.setAttribute("aria-hidden", "true");
    el["submit-question"].append(arrow);
  }
}

function toast(message) {
  clearTimeout(toastTimer);
  el.toast.textContent = message;
  el.toast.hidden = false;
  toastTimer = setTimeout(() => { el.toast.hidden = true; }, 4500);
}

el["topic-nav"].addEventListener("click", event => {
  const button = event.target.closest("button[data-topic]");
  if (!button || button.dataset.topic === state.topic) return;
  state.topic = button.dataset.topic;
  state.page = 1;
  loadQuestions();
});
el["page-size"].addEventListener("change", () => {
  state.size = Number(el["page-size"].value);
  state.page = 1;
  loadQuestions();
});
el["prev-page"].addEventListener("click", () => { if (!state.loading && state.page > 1) { state.page -= 1; loadQuestions(); } });
el["next-page"].addEventListener("click", () => { if (!state.loading && state.page * state.size < state.total) { state.page += 1; loadQuestions(); } });
el["add-question"].addEventListener("click", openDialog);
for (const id of ["close-dialog", "cancel-dialog"]) {
  el[id].addEventListener("click", () => { if (!state.saving) el["question-dialog"].close(); });
}
el["question-dialog"].addEventListener("cancel", event => { if (state.saving) event.preventDefault(); });
el["question-dialog"].addEventListener("click", event => {
  if (event.target === el["question-dialog"] && !state.saving) {
    const rect = el["question-dialog"].getBoundingClientRect();
    if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) el["question-dialog"].close();
  }
});
for (const id of ["question-title", "question-answer"]) {
  el[id].addEventListener("input", () => { el[id].setCustomValidity(""); el[id].removeAttribute("aria-invalid"); updateCounts(); });
}
el["question-form"].addEventListener("submit", async event => {
  event.preventDefault();
  if (state.saving) return;
  const fields = [el["question-title"], el["question-answer"]];
  for (const field of fields) {
    field.setCustomValidity(field.value.trim() ? "" : "请输入内容，不能只填写空格。");
    field.setAttribute("aria-invalid", String(!field.value.trim()));
  }
  if (!el["question-form"].reportValidity()) return;
  const payload = {
    title: el["question-title"].value.trim(), topic: el["question-topic"].value,
    difficulty: el["question-difficulty"].value, referenceAnswer: el["question-answer"].value.trim()
  };
  el["form-error"].hidden = true;
  setSaving(true);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort("timeout"), 15000);
  try {
    const saved = await requestJson("/api/questions", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload), signal: controller.signal
    });
    // 已保存与列表刷新分开处理：刷新失败不能误报成保存失败，让用户重复提交。
    el["question-dialog"].close();
    el["question-form"].reset();
    updateCounts();
    toast("新题目已收进题库，继续积累吧。");
    state.topic = "";
    state.page = 1;
    await loadQuestions(saved.id);
  } catch (error) {
    // 网络中断 / 超时可能发生在服务端已经保存之后；不自动重试非幂等 POST。
    const uncertain = controller.signal.aborted || error instanceof TypeError;
    el["form-error"].textContent = uncertain
      ? "尚未确认保存结果。草稿已保留，请取消并刷新题库确认是否已保存，再决定是否重试。"
      : errorMessage(error);
    el["form-error"].hidden = false;
  } finally {
    clearTimeout(timeout);
    setSaving(false);
  }
});

loadQuestions();
