// Minimal, dependency-free Markdown -> HTML renderer for chat messages.
// Deliberately small: it covers what an agent's answers actually use --
// headings, bold/italic/strikethrough, inline code, fenced code, links, lists,
// blockquotes, rules and paragraphs. Every piece of source text is HTML-escaped
// before any tags are introduced, so the result is safe to assign to innerHTML.

export function renderMarkdown(src) {
  if (src == null) return "";
  const lines = String(src).replace(/\r\n?/g, "\n").split("\n");
  const out = [];
  let para = [];
  let i = 0;

  const flushPara = () => {
    if (!para.length) return;
    out.push("<p>" + inline(para.join("\n")) + "</p>");
    para = [];
  };

  while (i < lines.length) {
    const line = lines[i];

    // fenced code block (``` or ~~~), kept verbatim
    const fence = line.match(/^\s*(`{3,}|~{3,})/);
    if (fence) {
      flushPara();
      const close = fence[1][0] === "`" ? /^\s*`{3,}\s*$/ : /^\s*~{3,}\s*$/;
      const body = [];
      i++;
      while (i < lines.length && !close.test(lines[i])) { body.push(lines[i]); i++; }
      i++; // skip closing fence
      out.push("<pre><code>" + escapeHtml(body.join("\n")) + "</code></pre>");
      continue;
    }

    // blank line ends a paragraph
    if (/^\s*$/.test(line)) { flushPara(); i++; continue; }

    // ATX heading
    const h = line.match(/^(#{1,6})\s+(.*?)\s*#*\s*$/);
    if (h) { flushPara(); const lvl = h[1].length; out.push("<h" + lvl + ">" + inline(h[2]) + "</h" + lvl + ">"); i++; continue; }

    // horizontal rule
    if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) { flushPara(); out.push("<hr>"); i++; continue; }

    // blockquote (a run of > lines, rendered recursively)
    if (/^\s*>/.test(line)) {
      flushPara();
      const buf = [];
      while (i < lines.length && /^\s*>/.test(lines[i])) { buf.push(lines[i].replace(/^\s*>\s?/, "")); i++; }
      out.push("<blockquote>" + renderMarkdown(buf.join("\n")) + "</blockquote>");
      continue;
    }

    // GFM table: a header row with pipes followed by a |---|:---:| delimiter
    // row, then body rows until a line with no pipe. Wrapped in a scroll
    // container so wide tables pan horizontally instead of breaking the layout.
    if (line.includes("|") && i + 1 < lines.length && isTableDelimiter(lines[i + 1])) {
      flushPara();
      const header = splitRow(line);
      const aligns = splitRow(lines[i + 1]).map((c) => {
        const l = c.startsWith(":"), r = c.endsWith(":");
        return l && r ? "center" : r ? "right" : "";
      });
      i += 2;
      const rows = [];
      while (i < lines.length && lines[i].includes("|") && !/^\s*$/.test(lines[i])) {
        rows.push(splitRow(lines[i]));
        i++;
      }
      const tr = (tag, cells) => "<tr>" + header.map((_, c) => {
        const a = aligns[c] ? ' style="text-align:' + aligns[c] + '"' : "";
        return "<" + tag + a + ">" + inline(cells[c] || "") + "</" + tag + ">";
      }).join("") + "</tr>";
      out.push('<div class="table-wrap"><table><thead>' + tr("th", header) + "</thead><tbody>"
        + rows.map((r) => tr("td", r)).join("") + "</tbody></table></div>");
      continue;
    }

    // list (unordered - * +, or ordered 1. / 1) ) -- a run of same-kind items
    if (/^\s*([-*+]\s+|\d+[.)]\s+)/.test(line)) {
      flushPara();
      const ordered = /^\s*\d+[.)]\s+/.test(line);
      const items = [];
      while (i < lines.length
        && /^\s*([-*+]\s+|\d+[.)]\s+)/.test(lines[i])
        && (/^\s*\d+[.)]\s+/.test(lines[i]) === ordered)) {
        items.push(lines[i].replace(/^\s*([-*+]|\d+[.)])\s+/, ""));
        i++;
      }
      const tag = ordered ? "ol" : "ul";
      out.push("<" + tag + ">" + items.map((it) => "<li>" + inline(it) + "</li>").join("") + "</" + tag + ">");
      continue;
    }

    para.push(line);
    i++;
  }
  flushPara();
  // Join with no separator: block tags concatenate directly, so no stray "\n"
  // shows through the message container's white-space: pre-wrap.
  return out.join("");
}

// The row under a table header: only pipes, dashes, colons and spaces, with at
// least one dash and one pipe (so a plain "---" rule is never mistaken for one).
function isTableDelimiter(line) {
  return line.includes("|") && /^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$/.test(line);
}

// Split a table row into trimmed cells: outer pipes are decoration, and \| is
// a literal pipe inside a cell.
function splitRow(line) {
  let s = line.trim();
  if (s.startsWith("|")) s = s.slice(1);
  if (s.endsWith("|") && !s.endsWith("\\|")) s = s.slice(0, -1);
  const cells = [];
  let cur = "";
  for (let j = 0; j < s.length; j++) {
    if (s[j] === "\\" && s[j + 1] === "|") { cur += "|"; j++; }
    else if (s[j] === "|") { cells.push(cur.trim()); cur = ""; }
    else cur += s[j];
  }
  cells.push(cur.trim());
  return cells;
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function inline(s) {
  // Split on `code` spans so their contents are only escaped, never reformatted;
  // the text between spans gets the full emphasis/link treatment.
  const parts = String(s).split(/(`[^`]+`)/);
  return parts.map((part) => {
    const code = part.match(/^`([^`]+)`$/);
    if (code) return "<code>" + escapeHtml(code[1]) + "</code>";
    return emphasis(escapeHtml(part));
  }).join("");
}

// Emphasis, links and soft line breaks applied to already-escaped text.
function emphasis(t) {
  t = t.replace(/\[([^\]]+)\]\(([^)\s]+)(?:\s+&quot;[^&]*&quot;)?\)/g, (_, txt, url) => {
    const safe = /^(https?:|mailto:|\/|#)/i.test(url) ? url : "#";
    return '<a href="' + safe + '" target="_blank" rel="noopener noreferrer">' + txt + "</a>";
  });
  t = t.replace(/\*\*\*([^*]+)\*\*\*/g, "<strong><em>$1</em></strong>");
  t = t.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
  t = t.replace(/__([^_]+)__/g, "<strong>$1</strong>");
  t = t.replace(/(^|[^*])\*([^*\n]+)\*/g, "$1<em>$2</em>");
  t = t.replace(/(^|[^_\w])_([^_\n]+)_/g, "$1<em>$2</em>");
  t = t.replace(/~~([^~]+)~~/g, "<del>$1</del>");
  t = t.replace(/\n/g, "<br>");
  return t;
}
