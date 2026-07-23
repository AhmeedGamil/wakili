// Auto-growing textarea (the JS path — engines with CSS field-sizing skip it).
// Every height write reflows the page (the bar shares a flex column with the
// transcript), so heights are written only when the line count actually
// changed; reset-to-auto happens only when text got shorter — the one case the
// box may need to shrink. Also toggles `multiline` on the bar once the text
// passes one line, so the buttons drop from centered to hugging the bottom.

export function createAutosize(input, bar, maxHeight) {
  let lastLen = 0, lastH = 0, metrics = null;
  return function autoSize() {
    const len = input.value.length;
    const shrunk = len < lastLen;
    lastLen = len;
    if (shrunk) input.style.height = "auto";
    const h = Math.min(input.scrollHeight, maxHeight);
    if (h === lastH && !shrunk) return; // same line count — zero layout writes
    lastH = h;
    input.style.height = h + "px";
    if (!metrics) {
      const cs = getComputedStyle(input);
      const line = parseFloat(cs.lineHeight) || parseFloat(cs.fontSize) * 1.4;
      metrics = { line, padV: parseFloat(cs.paddingTop) + parseFloat(cs.paddingBottom) };
    }
    const lines = Math.round((input.scrollHeight - metrics.padV) / metrics.line);
    bar.classList.toggle("multiline", lines > 1);
  };
}
