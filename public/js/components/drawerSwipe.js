// Drawer swipe — native app shell only (gated at the call site in main.js).
// On phones the sidebar is a drawer (app.css @media); inside the app it also
// follows the finger: dragging right anywhere on the page pulls it open (like
// a native Android drawer — no edge hunting), dragging left over the open
// drawer (or the dimmed page beside it) pushes it shut. A drag first has to
// commit to a direction (SLOP): vertical movement is never claimed, so list
// scrolling, pull-to-refresh, and long-press all keep working — and a
// horizontally scrolled code block keeps its own rightward drags. While a drag is live JS drives the transform and backdrop opacity
// directly with transitions off (body.side-drag); the release re-enables them
// and lets nav-open name the endpoint, so the drawer animates from wherever
// the finger left it. Browser tabs never call this — they keep the plain
// tap-to-toggle drawer.

export function initDrawerSwipe({ sidebar, backdrop, main, blocked }) {
  const mq = window.matchMedia("(max-width: 760px)"); // drawer layout only (see app.css)
  const EDGE = 40;    // opening drags starting this near the left edge claim diagonals eagerly
  const SLOP = 8;     // finger travel before the gesture commits to a direction
  const FLICK = 0.35; // px/ms — a faster release follows the flick, not the midpoint

  let width = 0, shown = 0, startX = 0, startY = 0, lastX = 0, lastT = 0, vx = 0;
  let dragging = false, wasOpen = false;

  // True when some element between the touch target and #main scrolls
  // horizontally and is scrolled away from its left edge — a rightward drag
  // there is that scroller's to consume, not the drawer's.
  function hscrollUnder(t) {
    for (let n = t; n && n !== main; n = n.parentElement) {
      if (n.scrollLeft > 0 && n.scrollWidth > n.clientWidth) {
        const o = getComputedStyle(n).overflowX;
        if (o === "auto" || o === "scroll") return true;
      }
    }
    return false;
  }

  // x = how much of the drawer pokes past the screen's left edge, 0..width.
  function paint(x) {
    shown = Math.max(0, Math.min(width, x));
    sidebar.style.transform = `translateX(${shown - width}px)`;
    backdrop.style.opacity = String(shown / width);
  }

  function onStart(e) {
    if (!mq.matches || e.touches.length !== 1 || (blocked && blocked())) return;
    const t = e.touches[0];
    wasOpen = document.body.classList.contains("nav-open");
    // A closing drag starts on the drawer or its backdrop; an opening drag
    // anywhere on the page itself. The target checks also keep the gesture
    // from firing under body-level layers (Files, terminal, the guide,
    // popover shields…) — a touch there targets the layer, not us.
    if (wasOpen ? !(sidebar.contains(e.target) || e.target === backdrop)
                : !main.contains(e.target)) return;
    dragging = false;
    startX = lastX = t.clientX; startY = t.clientY; lastT = e.timeStamp; vx = 0;
    // move/end attach per-gesture: a permanent non-passive touchmove on the
    // document would put every transcript scroll through the main thread.
    document.addEventListener("touchmove", onMove, { passive: false });
    document.addEventListener("touchend", onEnd);
    document.addEventListener("touchcancel", onEnd);
  }

  function detach() {
    document.removeEventListener("touchmove", onMove);
    document.removeEventListener("touchend", onEnd);
    document.removeEventListener("touchcancel", onEnd);
  }

  function onMove(e) {
    const t = e.touches[0];
    const dx = t.clientX - startX, dy = t.clientY - startY;
    if (!dragging) {
      if (Math.abs(dx) < SLOP && Math.abs(dy) < SLOP) return; // not committed yet
      // The browser beat us to it — this touch already drives a scroll, so its
      // moves arrive non-cancelable and preventDefault would be a no-op.
      // Dragging anyway would move the drawer AND scroll the chat at once;
      // whoever wins the touch owns it alone.
      if (!e.cancelable) return detach();
      // Direction lock. A touch at the screen edge is almost always meant for
      // the drawer — only clearly vertical movement (2:1) hands it back. Away
      // from the edge (and always when closing, where the session list's own
      // scrolling is at stake) a tie goes to whatever was scrolling.
      const vertical = Math.abs(dy) >= Math.abs(dx) * (!wasOpen && startX <= EDGE ? 2 : 1);
      // Headed the useless way (open + rightward, closed + leftward): also not ours.
      if (vertical || (wasOpen ? dx > 0 : dx < 0)) return detach();
      // A horizontal scroller under the finger (code block, wide table) that
      // can still consume a rightward drag keeps it; once it sits at its left
      // stop, the same drag starts opening the drawer instead.
      if (!wasOpen && hscrollUnder(e.target)) return detach();
      dragging = true;
      width = sidebar.offsetWidth;
      document.body.classList.add("side-drag");
    }
    if (e.cancelable) e.preventDefault(); // the drawer owns this touch — nothing scrolls under it
    const dt = e.timeStamp - lastT;
    if (dt > 0) vx = (t.clientX - lastX) / dt;
    lastX = t.clientX; lastT = e.timeStamp;
    paint((wasOpen ? width : 0) + dx);
  }

  function onEnd() {
    detach();
    if (!dragging) return;
    dragging = false;
    // A flick follows the finger's direction; a slow release settles to the
    // nearer resting place.
    const open = Math.abs(vx) > FLICK ? vx > 0 : shown >= width / 2;
    // One frame does it all: transitions come back (side-drag off), the inline
    // mid-drag styles go, nav-open names the endpoint — so the drawer animates
    // from where the finger left it to where it belongs.
    document.body.classList.remove("side-drag");
    sidebar.style.transform = "";
    backdrop.style.opacity = "";
    document.body.classList.toggle("nav-open", open);
  }

  document.addEventListener("touchstart", onStart, { passive: true });
}
