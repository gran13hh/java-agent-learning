"use strict";

// 页面只使用同源 fetch 与原生 DOM；不依赖 npm，也不把回答保存到浏览器持久存储。
const topicLabels = { JAVA: "Java 基础", MYSQL: "MySQL 数据库", REDIS: "Redis 缓存", AGENT: "AI Agent" };
const difficultyLabels = { EASY: "基础", MEDIUM: "进阶", HARD: "挑战" };
const $ = (id) => document.getElementById(id);
const practice = { session: null, selected: null, busy: false, page: 1, pages: 1, historyVersion: 0, drafts: new Map() };

function textNode(tag, text, className) {
  const element = document.createElement(tag);
  element.textContent = text; // 题目和回答均作为纯文本，避免把用户输入执行为 HTML。
  if (className) element.className = className;
  return element;
}

async function request(url, body) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(url, {
      signal: controller.signal,
      ...(body ? { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) } : {})
    });
    const data = await response.json();
    if (!response.ok) {
      const error = new Error(data.detail || "请求失败，请稍后重试。");
      error.status = response.status;
      throw error;
    }
    return data;
  } finally {
    clearTimeout(timer);
  }
}

function showError(error, writing = false) {
  const box = $("practice-error");
  const uncertain = !error.status || error.status >= 500;
  box.replaceChildren(textNode("p", uncertain
    ? (writing ? "未能确认保存结果，请先刷新练习记录核对。相同回答可安全重试；新建练习请先确认记录，避免重复创建。" : "暂时无法读取数据，请检查服务后重试。")
    : error.message));
  if (practice.session) {
    const retry = textNode("button", "重新读取当前练习", "button secondary");
    retry.type = "button";
    retry.addEventListener("click", () => loadSession(practice.session.id));
    box.append(retry);
  }
  box.hidden = false;
}

function setBusy(value) {
  practice.busy = value;
  for (const id of ["new-session", "start-session", "submit-answer", "practice-topic", "practice-count", "practice-answer", "next-turn"]) {
    $(id).disabled = value;
  }
  document.querySelectorAll(".history-item, .turn-button").forEach((button) => {
    button.disabled = value || button.dataset.locked === "true";
  });
  $("start-session").textContent = value ? "请稍候…" : "开始练习 →";
  $("submit-answer").textContent = value ? "请稍候…" : "提交回答 ↗";
}

function draftKey() { return `${practice.session.id}:${practice.selected}`; }

function renderSession() {
  const session = practice.session;
  $("setup-form").hidden = true;
  $("session-panel").hidden = false;
  $("session-label").textContent = `练习 #${session.id} / ${topicLabels[session.topic] || "综合练习"}`;
  $("session-status").textContent = session.status === "COMPLETED" ? "已完成" : "进行中";
  $("progress-label").textContent = `已作答 ${session.answeredCount} / ${session.questionCount} 题`;
  const percent = Math.round(session.answeredCount / session.questionCount * 100);
  $("progress-percent").textContent = `${percent}%`;
  $("session-progress").value = percent;
  const current = session.turns.find((turn) => !turn.answeredAt);
  $("turn-nav").replaceChildren(...session.turns.map((turn) => {
    const button = textNode("button", turn.answeredAt ? `${turn.position} ✓` : String(turn.position),
      `turn-button${turn.answeredAt ? " answered" : ""}${turn.id === practice.selected ? " active" : ""}`);
    button.type = "button";
    button.setAttribute("aria-label", `第 ${turn.position} 题${turn.answeredAt ? "，已作答" : ""}`);
    button.setAttribute("aria-pressed", String(turn.id === practice.selected));
    button.dataset.locked = String(!turn.answeredAt && turn.id !== current?.id);
    button.disabled = practice.busy || button.dataset.locked === "true";
    button.addEventListener("click", () => { practice.selected = turn.id; renderSession(); });
    return button;
  }));
  const turn = session.turns.find((item) => item.id === practice.selected);
  $("turn-meta").textContent = `QUESTION ${String(turn.position).padStart(2, "0")} · ${topicLabels[turn.topic]} · ${difficultyLabels[turn.difficulty]}`;
  $("turn-title").textContent = turn.title;
  $("answer-form").hidden = Boolean(turn.answeredAt);
  $("review-panel").hidden = !turn.answeredAt;
  if (turn.answeredAt) {
    $("saved-answer").textContent = turn.answer;
    $("saved-feedback").textContent = turn.feedback;
    $("saved-reference").textContent = turn.referenceAnswer;
    document.querySelector(".reference-block").open = false;
    $("next-turn").hidden = !current;
    $("completion-note").textContent = current ? "回答已保存。复盘后，继续下一题。" : "本场练习已完成，记录已保存。可点击题号逐题复盘。";
  } else {
    $("practice-answer").setCustomValidity("");
    $("practice-answer").value = practice.drafts.get(draftKey()) || "";
    $("practice-answer-count").textContent = `${$("practice-answer").value.length} / 10000`;
  }
  document.querySelectorAll(".history-item").forEach((button) => button.classList.toggle("selected", Number(button.dataset.id) === session.id));
}

async function loadSession(id) {
  if (practice.busy) return;
  setBusy(true);
  $("practice-error").hidden = true;
  $("practice-loading").hidden = false;
  try {
    const session = await request(`/api/interviews/${id}`);
    practice.session = session;
    practice.selected = (session.turns.find((turn) => !turn.answeredAt) || session.turns[0]).id;
    // URL 是恢复入口：刷新、复制本地链接或重新打开都能从数据库恢复已保存的进度。
    history.replaceState(null, "", `?session=${session.id}`);
    renderSession();
  } catch (error) {
    showError(error);
  } finally {
    $("practice-loading").hidden = true;
    setBusy(false);
  }
}

async function loadHistory() {
  const version = ++practice.historyVersion;
  $("history-error").hidden = true;
  $("history-prev").disabled = true;
  $("history-next").disabled = true;
  $("refresh-history").disabled = true;
  try {
    const data = await request(`/api/interviews?page=${practice.page}&size=5`);
    if (version !== practice.historyVersion) return;
    practice.pages = Math.max(1, Math.ceil(data.total / 5));
    $("history-list").replaceChildren(...data.items.map((session) => {
      const button = textNode("button", "", `history-item${session.id === practice.session?.id ? " selected" : ""}`);
      button.type = "button";
      button.dataset.id = session.id;
      button.disabled = practice.busy;
      button.append(textNode("strong", `${topicLabels[session.topic] || "综合练习"} · ${session.questionCount} 题`),
        textNode("span", `#${session.id} · ${session.createdAt.replace("T", " ").slice(0, 16)}`),
        textNode("span", session.status === "COMPLETED" ? "已完成 · 查看复盘 ↗" : "进行中 · 继续练习 →", "history-state"));
      button.addEventListener("click", () => loadSession(session.id));
      return button;
    }));
    if (!data.total) $("history-list").append(textNode("p", "还没有练习记录。从第一场小面试开始吧。", "history-empty"));
    $("history-page").textContent = `${practice.page} / ${practice.pages}`;
    $("history-prev").disabled = practice.page <= 1;
    $("history-next").disabled = practice.page >= practice.pages;
  } catch (error) {
    if (version !== practice.historyVersion) return;
    $("history-error").textContent = "记录读取失败，请点击右上方刷新重试。";
    $("history-error").hidden = false;
  } finally {
    if (version === practice.historyVersion) $("refresh-history").disabled = false;
  }
}

$("setup-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  if (practice.busy) return;
  setBusy(true);
  $("practice-error").hidden = true;
  try {
    const session = await request("/api/interviews", { topic: $("practice-topic").value || null, questionCount: Number($("practice-count").value) });
    practice.session = session;
    practice.selected = session.turns[0].id;
    history.replaceState(null, "", `?session=${session.id}`);
    renderSession();
    $("turn-title").focus();
  } catch (error) {
    showError(error, true);
  } finally {
    setBusy(false);
    practice.page = 1;
    loadHistory();
  }
});

$("answer-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  if (practice.busy) return;
  const answer = $("practice-answer").value.trim();
  if (!answer) { $("practice-answer").setCustomValidity("请写下回答，不能只输入空格。"); $("practice-answer").reportValidity(); return; }
  const key = draftKey();
  setBusy(true);
  $("practice-error").hidden = true;
  try {
    // turnId 标识本题；相同题号 + 相同内容重试不会再次写入或改变进度。
    practice.session = await request(`/api/interviews/${practice.session.id}/answers`, { turnId: practice.selected, answer });
    practice.drafts.delete(key);
    renderSession(); // 留在本题展示反馈，用户点击下一题才切换。
  } catch (error) {
    showError(error, true);
  } finally {
    setBusy(false);
    loadHistory();
  }
});

$("practice-answer").addEventListener("input", () => {
  const input = $("practice-answer");
  input.setCustomValidity("");
  practice.drafts.set(draftKey(), input.value);
  $("practice-answer-count").textContent = `${input.value.length} / 10000`;
});
$("next-turn").addEventListener("click", () => {
  const current = practice.session.turns.find((turn) => !turn.answeredAt);
  if (current) { practice.selected = current.id; renderSession(); $("turn-title").focus(); }
});
$("new-session").addEventListener("click", () => {
  practice.session = null;
  practice.selected = null;
  history.replaceState(null, "", location.pathname);
  $("setup-form").hidden = false;
  $("session-panel").hidden = true;
  $("practice-error").hidden = true;
  document.querySelectorAll(".history-item").forEach((button) => button.classList.remove("selected"));
  $("practice-topic").focus();
});
$("refresh-history").addEventListener("click", loadHistory);
$("history-prev").addEventListener("click", () => { if (practice.page > 1) { practice.page--; loadHistory(); } });
$("history-next").addEventListener("click", () => { if (practice.page < practice.pages) { practice.page++; loadHistory(); } });

loadHistory();
const initialSession = new URLSearchParams(location.search).get("session");
if (initialSession && /^[1-9]\d*$/.test(initialSession)) loadSession(initialSession);
