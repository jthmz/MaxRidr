// A small QR code maker for short text such as a web address: byte mode, versions 1 to 6 (up to 106 bytes),
// error correction level M. No dependencies.
//   qr('http://192.168.1.20:8791/#123456')  ->  { n, rows }   rows[y][x] is true for a dark module; null if the text is too long
//   qrPath(q)                                 ->  one SVG path (a bar per run of dark modules), drawn in a viewBox of n by n
const EC = [null, [10, 1], [16, 1], [26, 1], [18, 2], [24, 2], [16, 4]];   // per version: error-correction codewords per block, number of blocks
const TOTAL = [0, 26, 44, 70, 100, 134, 172];                               // codewords in the whole symbol

// Reed-Solomon over GF(256) with the QR polynomial 0x11D
function mul(x, y) { let z = 0; for (let i = 7; i >= 0; i--) { z = (z << 1) ^ ((z >>> 7) * 0x11D); z ^= ((y >>> i) & 1) * x; } return z; }
function divisor(deg) {
  const r = new Array(deg).fill(0); r[deg - 1] = 1;
  let root = 1;
  for (let i = 0; i < deg; i++) {
    for (let j = 0; j < deg; j++) { r[j] = mul(r[j], root); if (j + 1 < deg) r[j] ^= r[j + 1]; }
    root = mul(root, 2);
  }
  return r;
}
function remainder(data, div) {
  const r = div.map(() => 0);
  for (const b of data) { const f = b ^ r.shift(); r.push(0); div.forEach((c, i) => { r[i] ^= mul(c, f); }); }
  return r;
}

// the standard's four penalty rules, used to pick the mask that scans best
function penalty(d, n) {
  let p = 0, darkCount = 0;
  const lines = [];
  for (let i = 0; i < n; i++) { let r = '', c = ''; for (let j = 0; j < n; j++) { r += d[i][j] ? '1' : '0'; c += d[j][i] ? '1' : '0'; } lines.push(r, c); }
  for (const s of lines) {
    for (const m of s.match(/0{5,}|1{5,}/g) || []) p += m.length - 2;
    for (let i = 0; i + 11 <= n; i++) { const w = s.substr(i, 11); if (w === '10111010000' || w === '00001011101') p += 40; }
  }
  for (let y = 0; y < n - 1; y++) for (let x = 0; x < n - 1; x++) { const c = d[y][x]; if (c === d[y][x + 1] && c === d[y + 1][x] && c === d[y + 1][x + 1]) p += 3; }
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (d[y][x]) darkCount++;
  return p + Math.floor(Math.abs(darkCount * 100 / (n * n) - 50) / 5) * 10;
}

const MASK = [
  (x, y) => (x + y) % 2 === 0, (x, y) => y % 2 === 0, (x, y) => x % 3 === 0, (x, y) => (x + y) % 3 === 0,
  (x, y) => (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0, (x, y) => (x * y) % 2 + (x * y) % 3 === 0,
  (x, y) => ((x * y) % 2 + (x * y) % 3) % 2 === 0, (x, y) => ((x + y) % 2 + (x * y) % 3) % 2 === 0,
];

export function qr(text) {
  const bytes = [...Buffer.from(String(text), 'utf8')];
  let ver = 0;
  for (let v = 1; v <= 6 && !ver; v++) if (bytes.length <= TOTAL[v] - EC[v][0] * EC[v][1] - 2) ver = v;
  if (!ver) return null;
  const [ecLen, blocks] = EC[ver], dataLen = TOTAL[ver] - ecLen * blocks, n = ver * 4 + 17;

  // the data: mode 0100 (bytes), an 8-bit length, the bytes, a terminator, then the standard padding
  const bits = [];
  const push = (val, len) => { for (let i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1); };
  push(4, 4); push(bytes.length, 8); bytes.forEach(b => push(b, 8));
  push(0, Math.min(4, dataLen * 8 - bits.length));
  while (bits.length % 8) bits.push(0);
  const data = [];
  for (let i = 0; i < bits.length; i += 8) data.push(parseInt(bits.slice(i, i + 8).join(''), 2));
  for (let pad = 0xEC; data.length < dataLen; pad ^= 0xEC ^ 0x11) data.push(pad);

  // split into blocks, add error correction to each, interleave
  const size = dataLen / blocks, div = divisor(ecLen), parts = [];
  for (let b = 0; b < blocks; b++) { const d = data.slice(b * size, (b + 1) * size); parts.push({ d, e: remainder(d, div) }); }
  const out = [];
  for (let i = 0; i < size; i++) parts.forEach(p => out.push(p.d[i]));
  for (let i = 0; i < ecLen; i++) parts.forEach(p => out.push(p.e[i]));

  // the fixed patterns
  const dark = Array.from({ length: n }, () => new Array(n).fill(false));
  const fixed = Array.from({ length: n }, () => new Array(n).fill(false));
  const set = (x, y, d) => { dark[y][x] = !!d; fixed[y][x] = true; };
  for (let i = 0; i < n; i++) { set(6, i, i % 2 === 0); set(i, 6, i % 2 === 0); }                      // timing lines
  const finder = (cx, cy) => {
    for (let dy = -4; dy <= 4; dy++) for (let dx = -4; dx <= 4; dx++) {
      const x = cx + dx, y = cy + dy, dist = Math.max(Math.abs(dx), Math.abs(dy));
      if (x >= 0 && x < n && y >= 0 && y < n) set(x, y, dist !== 2 && dist !== 4);
    }
  };
  finder(3, 3); finder(n - 4, 3); finder(3, n - 4);
  if (ver > 1) {                                                                                        // versions 2 to 6 have one alignment pattern
    const c = n - 7;
    for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) set(c + dx, c + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
  }
  const format = mask => {                                                                              // level M is 00, then the mask number
    let rem = mask;
    for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    const b = ((mask << 10) | rem) ^ 0x5412, bit = i => ((b >>> i) & 1) === 1;
    for (let i = 0; i <= 5; i++) set(8, i, bit(i));
    set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8));
    for (let i = 9; i < 15; i++) set(14 - i, 8, bit(i));
    for (let i = 0; i < 8; i++) set(n - 1 - i, 8, bit(i));
    for (let i = 8; i < 15; i++) set(8, n - 15 + i, bit(i));
    set(8, n - 8, true);
  };
  format(0);                                                                                            // reserves those modules before the data goes in

  // the data, in the standard zigzag from the bottom right
  let k = 0;
  for (let right = n - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (let vert = 0; vert < n; vert++) for (let j = 0; j < 2; j++) {
      const x = right - j, up = ((right + 1) & 2) === 0, y = up ? n - 1 - vert : vert;
      if (!fixed[y][x] && k < out.length * 8) { dark[y][x] = ((out[k >>> 3] >>> (7 - (k & 7))) & 1) === 1; k++; }
    }
  }

  // try all eight masks, keep the one with the lowest penalty
  const flip = m => { for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (!fixed[y][x] && MASK[m](x, y)) dark[y][x] = !dark[y][x]; };
  let best = 0, low = Infinity;
  for (let m = 0; m < 8; m++) { flip(m); format(m); const p = penalty(dark, n); if (p < low) { low = p; best = m; } flip(m); }
  flip(best); format(best);
  return { n, rows: dark, version: ver, mask: best, codewords: out, ecLen, blocks };
}

export function qrPath(q) {
  let d = '';
  for (let y = 0; y < q.n; y++) for (let x = 0; x < q.n; x++) {
    if (!q.rows[y][x]) continue;
    let w = 1;
    while (x + w < q.n && q.rows[y][x + w]) w++;
    d += `M${x} ${y}h${w}v1h-${w}z`;
    x += w - 1;
  }
  return d;
}
