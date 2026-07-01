// Tiny dependency-free QR Code generator (byte mode, error-correction level M,
// versions 1–10). Enough to encode the gateway URL+token so the phone can scan
// it instead of typing. Algorithm follows the QR spec (Reed–Solomon over
// GF(256), 8 data-mask patterns chosen by penalty score); the RS-block and
// alignment tables are the verified spec values for level M.

// ---- Galois field GF(256), primitive poly 0x11d ----------------------------
const EXP = new Array(512), LOG = new Array(256);
(() => { let x = 1; for (let i = 0; i < 255; i++) { EXP[i] = x; LOG[x] = i; x <<= 1; if (x & 0x100) x ^= 0x11d; } for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255]; })();
const gmul = (a, b) => (a === 0 || b === 0) ? 0 : EXP[LOG[a] + LOG[b]];

function rsGen(n) { let g = [1]; for (let i = 0; i < n; i++) { const ng = new Array(g.length + 1).fill(0); for (let j = 0; j < g.length; j++) { ng[j] ^= g[j]; ng[j + 1] ^= gmul(g[j], EXP[i]); } g = ng; } return g; }
function rsEncode(data, nec) {
  const gen = rsGen(nec), ec = new Array(nec).fill(0);
  for (const d of data) {
    const factor = d ^ ec[0];
    ec.shift(); ec.push(0);
    if (factor !== 0) for (let i = 0; i < nec; i++) ec[i] ^= gmul(gen[i + 1], factor);
  }
  return ec;
}

// ---- spec tables (level M) --------------------------------------------------
// [ecCodewordsPerBlock, [[blockCount, dataCodewordsPerBlock], ...]]
const RSB = {
  1: [10, [[1, 16]]], 2: [16, [[1, 28]]], 3: [26, [[1, 44]]], 4: [18, [[2, 32]]],
  5: [24, [[2, 43]]], 6: [16, [[4, 27]]], 7: [18, [[4, 31]]],
  8: [22, [[2, 38], [2, 39]]], 9: [22, [[3, 36], [2, 37]]], 10: [26, [[4, 43], [1, 44]]],
};
const ALIGN = { 1: [], 2: [6, 18], 3: [6, 22], 4: [6, 26], 5: [6, 30], 6: [6, 34], 7: [6, 22, 38], 8: [6, 24, 42], 9: [6, 26, 46], 10: [6, 28, 50] };
const totalData = (v) => RSB[v][1].reduce((s, [c, d]) => s + c * d, 0);

// ---- BCH for format / version information ----------------------------------
const G15 = 0x537, G15_MASK = 0x5412, G18 = 0x1f25;
const bchDigit = (d) => { let n = 0; while (d !== 0) { n++; d >>>= 1; } return n; };
function bchTypeInfo(data) { let d = data << 10; while (bchDigit(d) - bchDigit(G15) >= 0) d ^= G15 << (bchDigit(d) - bchDigit(G15)); return ((data << 10) | d) ^ G15_MASK; }
function bchTypeNumber(data) { let d = data << 12; while (bchDigit(d) - bchDigit(G18) >= 0) d ^= G18 << (bchDigit(d) - bchDigit(G18)); return (data << 12) | d; }

// ---- bit buffer -------------------------------------------------------------
class Bits { constructor() { this.buf = []; this.len = 0; } put(num, n) { for (let i = n - 1; i >= 0; i--) this.putBit(((num >>> i) & 1) === 1); } putBit(b) { const i = this.len >>> 3; if (this.buf.length <= i) this.buf.push(0); if (b) this.buf[i] |= 0x80 >>> (this.len & 7); this.len++; } }

// ---- data codewords (encode + pad + RS + interleave) ------------------------
function createData(version, bytes) {
  const cci = version < 10 ? 8 : 16;
  const b = new Bits();
  b.put(4, 4);            // byte mode
  b.put(bytes.length, cci);
  for (const by of bytes) b.put(by, 8);
  const cap = totalData(version) * 8;
  if (b.len > cap) throw new Error("qr: data too long");
  if (b.len + 4 <= cap) b.put(0, 4);          // terminator
  while (b.len % 8 !== 0) b.putBit(false);     // byte align
  while (b.len < cap) { b.put(0xec, 8); if (b.len >= cap) break; b.put(0x11, 8); }
  return b.buf;
}

function createCodewords(version, bytes) {
  const data = createData(version, bytes);
  const [ecLen, groups] = RSB[version];
  const dcs = [], ecs = []; let off = 0;
  for (const [count, dlen] of groups) for (let i = 0; i < count; i++) { const dc = data.slice(off, off + dlen); off += dlen; dcs.push(dc); ecs.push(rsEncode(dc, ecLen)); }
  const maxDc = Math.max(...dcs.map((d) => d.length));
  const out = [];
  for (let i = 0; i < maxDc; i++) for (const dc of dcs) if (i < dc.length) out.push(dc[i]);
  for (let i = 0; i < ecLen; i++) for (const ec of ecs) out.push(ec[i]);
  return out;
}

// ---- mask functions ---------------------------------------------------------
const MASK = [
  (i, j) => (i + j) % 2 === 0,
  (i, j) => i % 2 === 0,
  (i, j) => j % 3 === 0,
  (i, j) => (i + j) % 3 === 0,
  (i, j) => (Math.floor(i / 2) + Math.floor(j / 3)) % 2 === 0,
  (i, j) => ((i * j) % 2) + ((i * j) % 3) === 0,
  (i, j) => (((i * j) % 2) + ((i * j) % 3)) % 2 === 0,
  (i, j) => (((i * j) % 3) + ((i + j) % 2)) % 2 === 0,
];

// ---- matrix construction ----------------------------------------------------
function buildMatrix(version, codewords, maskPattern, test) {
  const n = version * 4 + 17;
  const m = Array.from({ length: n }, () => new Array(n).fill(null));

  const probe = (row, col) => {
    for (let r = -1; r <= 7; r++) for (let c = -1; c <= 7; c++) {
      const rr = row + r, cc = col + c;
      if (rr < 0 || rr >= n || cc < 0 || cc >= n) continue;
      m[rr][cc] = (r >= 0 && r <= 6 && (c === 0 || c === 6)) || (c >= 0 && c <= 6 && (r === 0 || r === 6)) || (r >= 2 && r <= 4 && c >= 2 && c <= 4);
    }
  };
  probe(0, 0); probe(n - 7, 0); probe(0, n - 7);

  // timing
  for (let i = 8; i < n - 8; i++) { if (m[i][6] === null) m[i][6] = i % 2 === 0; if (m[6][i] === null) m[6][i] = i % 2 === 0; }

  // alignment
  const pos = ALIGN[version];
  for (const r of pos) for (const c of pos) { if (m[r][c] !== null) continue; for (let dr = -2; dr <= 2; dr++) for (let dc = -2; dc <= 2; dc++) m[r + dr][c + dc] = Math.abs(dr) === 2 || Math.abs(dc) === 2 || (dr === 0 && dc === 0); }

  // version info (v >= 7)
  if (version >= 7) {
    const bits = bchTypeNumber(version);
    for (let i = 0; i < 18; i++) { const mod = !test && ((bits >> i) & 1) === 1; m[Math.floor(i / 3)][i % 3 + n - 8 - 3] = mod; m[i % 3 + n - 8 - 3][Math.floor(i / 3)] = mod; }
  }

  // format info (level M = 0)
  const fbits = bchTypeInfo((0 << 3) | maskPattern);
  for (let i = 0; i < 15; i++) {
    const mod = !test && ((fbits >> i) & 1) === 1;
    if (i < 6) m[i][8] = mod; else if (i < 8) m[i + 1][8] = mod; else m[n - 15 + i][8] = mod;
    if (i < 8) m[8][n - i - 1] = mod; else if (i < 9) m[8][15 - i - 1 + 1] = mod; else m[8][15 - i - 1] = mod;
  }
  m[n - 8][8] = !test; // dark module

  // data placement (zigzag, applying mask to data modules only)
  let inc = -1, row = n - 1, bitIdx = 7, byteIdx = 0;
  for (let col = n - 1; col > 0; col -= 2) {
    if (col === 6) col -= 1;
    for (;;) {
      for (let c = 0; c < 2; c++) {
        if (m[row][col - c] === null) {
          let dark = false;
          if (byteIdx < codewords.length) dark = ((codewords[byteIdx] >>> bitIdx) & 1) === 1;
          if (MASK[maskPattern](row, col - c)) dark = !dark;
          m[row][col - c] = dark;
          if (--bitIdx === -1) { byteIdx++; bitIdx = 7; }
        }
      }
      row += inc;
      if (row < 0 || row >= n) { row -= inc; inc = -inc; break; }
    }
  }
  return m;
}

// ---- mask penalty (spec rules 1–4) -----------------------------------------
function penalty(m) {
  const n = m.length; let p = 0;
  const at = (r, c) => m[r][c] ? 1 : 0;
  for (let r = 0; r < n; r++) for (let dir = 0; dir < 2; dir++) {
    let run = 1, prev = dir ? at(0, r) : at(r, 0);
    for (let i = 1; i < n; i++) { const v = dir ? at(i, r) : at(r, i); if (v === prev) { run++; if (run === 5) p += 3; else if (run > 5) p++; } else { run = 1; prev = v; } }
  }
  for (let r = 0; r < n - 1; r++) for (let c = 0; c < n - 1; c++) { const v = at(r, c); if (v === at(r, c + 1) && v === at(r + 1, c) && v === at(r + 1, c + 1)) p += 3; }
  // rule 3: finder-like pattern 10111010000 / 00001011101
  const seqs = [[1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0], [0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1]];
  for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) for (const dir of [0, 1]) {
    for (const seq of seqs) {
      if ((dir === 0 ? c : r) + 11 > n) continue;
      let ok = true;
      for (let k = 0; k < 11; k++) { const v = dir === 0 ? at(r, c + k) : at(r + k, c); if (v !== seq[k]) { ok = false; break; } }
      if (ok) p += 40;
    }
  }
  let dark = 0; for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) dark += at(r, c);
  const ratio = dark * 100 / (n * n); p += Math.floor(Math.abs(ratio - 50) / 5) * 10;
  return p;
}

/** Build the QR boolean matrix for a string (true = dark module). */
export function qrMatrix(text) {
  const bytes = [...new TextEncoder().encode(text)];
  let version = 0;
  for (let v = 1; v <= 10; v++) { const cci = v < 10 ? 8 : 16; if (bytes.length * 8 + 4 + cci <= totalData(v) * 8) { version = v; break; } }
  if (!version) throw new Error("qr: text too long for v1–10");
  const cw = createCodewords(version, bytes);
  let best = null, bestP = Infinity;
  for (let mask = 0; mask < 8; mask++) { const m = buildMatrix(version, cw, mask, false); const pen = penalty(m); if (pen < bestP) { bestP = pen; best = m; } }
  return best;
}

/** Render the QR to a terminal string using half-block chars + a quiet zone. */
export function qrTerminal(text, quiet = 2) {
  const m = qrMatrix(text);
  const n = m.length, size = n + quiet * 2;
  const get = (r, c) => (r >= quiet && r < quiet + n && c >= quiet && c < quiet + n) ? m[r - quiet][c - quiet] : false;
  let out = "";
  for (let r = 0; r < size; r += 2) {
    let line = "";
    for (let c = 0; c < size; c++) {
      const top = get(r, c), bot = r + 1 < size ? get(r + 1, c) : false;
      line += top && bot ? "█" : top ? "▀" : bot ? "▄" : " ";
    }
    out += line + "\n";
  }
  return out;
}
