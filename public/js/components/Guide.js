// Guided tour. Shown automatically on the first visit (remembered in
// localStorage) and replayable from Settings → Other. Dims the page and
// spotlights one element at a time with a short explanation. Steps can carry a
// `before` hook that navigates the UI first (open the settings panel, switch
// its tab, …), so the tour walks through the real screens element by element.
// A step whose target is missing (e.g. a control this OS doesn't support) is
// skipped when marked `optional`.

import { el } from "./dom.js";
import { icon } from "./icons.js";

const KEY = "ra-guide-done";

export function maybeShowGuide(steps, opts) {
  if (localStorage.getItem(KEY)) return;
  showGuide(steps, opts);
}

export function showGuide(steps, { onEnd } = {}) {
  let i = -1;

  // The tour points at sidebar elements — on a phone the sidebar is a drawer,
  // so keep it open for the duration.
  document.body.classList.add("nav-open");

  const hl = el("div", { class: "guide-hl" });
  const title = el("div", { class: "guide-title" });
  const text = el("div", { class: "guide-text" });
  const dots = el("div", { class: "guide-dots" });
  const skipBtn = el("button", { class: "btn ghost", type: "button", onClick: end }, "Skip");
  const nextBtn = el("button", { class: "btn primary guide-next", type: "button", onClick: () => goTo(i + 1) }, "Next");
  const card = el("div", { class: "guide-card" }, title, text, dots, el("div", { class: "guide-btns" }, skipBtn, nextBtn));
  const overlay = el("div", { class: "guide-overlay" }, hl, card);
  document.body.appendChild(overlay);

  async function goTo(k) {
    if (k >= steps.length) return end();
    i = k;
    const s = steps[i];
    try { if (s.before) await s.before(); } catch { /* navigate best-effort */ }
    // Let drawer/panel transitions settle before measuring the target.
    setTimeout(() => {
      if (!s.target() && s.optional) return goTo(k + 1);
      place();
    }, 320);
  }

  function place() {
    const s = steps[i];
    const target = s.target();
    title.textContent = s.title;
    text.innerHTML = "";
    text.append(...(typeof s.body === "string" ? [document.createTextNode(s.body)] : s.body()));
    dots.replaceChildren(...steps.map((_, k) => el("span", { class: "guide-dot" + (k === i ? " on" : "") })));
    nextBtn.textContent = i === steps.length - 1 ? "Done" : "Next";
    const r = target ? target.getBoundingClientRect() : null;
    const cw = Math.min(320, window.innerWidth - 24);
    card.style.width = cw + "px";
    if (r) {
      hl.style.display = "block";
      hl.style.left = r.left - 6 + "px";
      hl.style.top = r.top - 6 + "px";
      hl.style.width = r.width + 12 + "px";
      hl.style.height = r.height + 12 + "px";
      // card sits under the target, clamped to the viewport
      card.style.left = Math.max(12, Math.min(r.left, window.innerWidth - cw - 12)) + "px";
      card.style.top = Math.min(r.bottom + 14, window.innerHeight - 260) + "px";
    } else {
      // target missing: center the card, no spotlight
      hl.style.display = "none";
      card.style.left = Math.max(12, (window.innerWidth - cw) / 2) + "px";
      card.style.top = "30%";
    }
  }

  function end() {
    localStorage.setItem(KEY, "1");
    overlay.remove();
    document.body.classList.remove("nav-open");
    if (onEnd) onEnd();
  }

  goTo(0);
}

// One "icon + name — what it does" row for multi-part step bodies.
export function guideRow(ico, name, does) {
  return el("div", { class: "guide-row" }, icon(ico), el("span", {},
    el("strong", { text: name + " — " }), document.createTextNode(does)));
}
