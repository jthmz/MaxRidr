#!/usr/bin/env node
// Creator Deck (MAXAPACITY Deck): the Mac side of the Car Thing dashboard. No dependencies, Node 22+.
//
//   node server.mjs               run it (drives the Car Thing when it is plugged in)
//   node server.mjs --no-device   only serve the preview at http://127.0.0.1:8791/
//
// How it reaches the Car Thing: `adb forward` to the kiosk browser's debugging port, then one
// DevTools connection. The page is written into the browser from here, state is pushed over that
// connection, and taps come back as console messages. Nothing is written to the device, and
// `adb reverse` is never used (this firmware's adb service dies when it is asked for one).
//
// Everything personal lives in config.json next to this file (see config.example.json).
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { execFile, spawn } from 'node:child_process';
import { createHash, randomBytes, randomInt, timingSafeEqual } from 'node:crypto';
import { StringDecoder } from 'node:string_decoder';
import { fileURLToPath } from 'node:url';
import { qr, qrPath } from './qr.mjs';
import { parseListing, planOffload, signature, targetFor, altName, phoneFolders, shq, sizeText, cleanName } from './offload.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DATA = path.join(HERE, 'data');
const home = p => (typeof p === 'string' && p.startsWith('~/') ? path.join(os.homedir(), p.slice(2)) : p);
const VERSION = '2.2';

// ---------- config ----------
const MAXDIR = '~/Library/Mobile Documents/com~apple~CloudDocs/MAXAPACITY';
const DEFAULTS = {
  brand: 'MAXAPACITY',
  channel: 'https://www.youtube.com/@MAXAPACITY',
  subGoal: 25000,
  boardFile: MAXDIR + '/Dash/state.json',
  roots: { 'OWC 4TB': '/Volumes/OWC 4TB', MAXAPACITY: MAXDIR },   // how paths on the board map to this Mac
  footage: '/Volumes/OWC 4TB',                                     // one folder per video, each with an EDIT folder
  studio: 'http://127.0.0.1:8777',
  studioApp: MAXDIR + '/Apps/MAXAPACITY Studio.app',
  claudeSessions: true, claudeTitleFilter: '', claudeMaxAgeHours: 72,
  fullBrightness: true, nightBrightness: 60,
  soundFile: '/System/Library/Sounds/Glass.aiff',
  ambientAfterSec: 240,
  // the standby screen's backgrounds: pictures from the channel and the website, and short clips cut from finished videos on this Mac
  standbyPictures: true, standbyClips: true, standbyPictureSec: 40, standbyClipEveryMin: 5,
  website: 'https://maxapacitybikes.com',                          // pictures on this page are used (this site's own only); '' turns that off
  pictureFolders: [], clipFolders: [],                             // extra folders of your own pictures / finished videos
  // other screens: with remote on, a phone, tablet or another computer on the same network can show the Deck once it has been
  // paired from the Deck itself. remoteName is an address to show instead of this Mac's IP address, such as "my-mac.local".
  remote: false, remoteName: '',
  // phoneToken: a password for the MAXAPACITY Ride phone app's Deck mode over Wi-Fi (the app sends it with every request). Needs
  // remote on, and at least 8 characters; '' turns it off. Over USB the app needs no token.
  phoneToken: '',
  // usbScreens: an Android phone on a USB cable (USB debugging on, this Mac allowed) can open the Deck in its own browser at
  // http://localhost:8791, with no network needed. The Deck keeps that link up for every such phone while it is plugged in.
  usbScreens: false,
  // usbOffload: copy new photos, videos and ride files off a phone linked over USB (needs usbScreens). offloadTo is the folder they
  // land in ('' means "_PHONE OFFLOAD" on the footage drive), offloadFolders the folders on the phone to take from, and
  // offloadSinceDays how far back to reach the first time a phone is seen. Copy only: nothing on the phone is changed.
  usbOffload: false, offloadTo: '', offloadFolders: ['DCIM'], offloadSinceDays: 3,
  // uploads: let a phone app (or a paired screen) send rides and media to this Mac over the network; they land where the USB
  // offload puts them. uploadMaxGB is the largest single file accepted.
  uploads: false, uploadMaxGB: 8,
  disks: [{ label: 'Mac', path: '/System/Volumes/Data' }, { label: 'OWC 4TB', path: '/Volumes/OWC 4TB' }],
  apps: [
    { key: 'studio', label: 'Studio', sub: 'editor + dash' },
    { key: 'ytstudio', label: 'YT Studio', sub: 'analytics', url: 'https://studio.youtube.com' },
    { key: 'channel', label: 'Channel', sub: '@MAXAPACITY', url: 'https://www.youtube.com/@MAXAPACITY' },
    { key: 'resolve', label: 'Resolve', sub: 'DaVinci', app: 'DaVinci Resolve' },
    { key: 'capcut', label: 'CapCut', sub: 'editor', app: 'CapCut' },
    { key: 'drive', label: 'Footage', sub: 'OWC 4TB', path: '/Volumes/OWC 4TB' },
    { key: 'finished', label: 'Finished', sub: 'videos', path: '/Volumes/OWC 4TB/_FINISHED VIDEOS' },
    { key: 'site', label: 'Website', sub: 'maxapacitybikes.com', url: 'https://maxapacitybikes.com' },
    { key: 'claude', label: 'Claude', sub: 'desktop', app: 'Claude' },
  ],
};
let CFG = DEFAULTS;
try { CFG = { ...DEFAULTS, ...JSON.parse(fs.readFileSync(path.join(HERE, 'config.json'), 'utf8')) }; } catch (e) { /* no config.json: defaults */ }
const PORT = Number(process.env.DECK_PORT) || 8791, DEVTOOLS_PORT = 9223;
const NO_DEVICE = process.argv.includes('--no-device');
const ROOTS = Object.entries(CFG.roots).map(([name, p]) => [name, home(p)]);
const FOOTAGE = home(CFG.footage);

const sleep = ms => new Promise(r => setTimeout(r, ms));
const run = (cmd, args, timeout = 8000) => new Promise(res =>
  execFile(cmd, args, { timeout, maxBuffer: 4 << 20 }, (err, out, errout) => res({ ok: !err, out: String(out || ''), err: String(errout || '') })));
const log = (...a) => console.log(new Date().toTimeString().slice(0, 8), ...a);
const short = (s, n) => { s = String(s == null ? '' : s); return s.length > n ? s.slice(0, n - 1) + '…' : s; };
const exists = p => (p ? fs.promises.access(p).then(() => true, () => false) : Promise.resolve(false));
const readJSON = async f => JSON.parse(await fs.promises.readFile(f, 'utf8'));
const ago = t => { const m = (Date.now() - t) / 60000; return m < 1 ? 'now' : m < 60 ? Math.round(m) + 'm' : m < 1440 ? Math.round(m / 60) + 'h' : Math.round(m / 1440) + 'd'; };
// one refresh at a time per source, and a slow disk or iCloud read never blocks anything else
const guard = fn => { let busy = false; return async () => { if (busy) return; busy = true; try { await fn(); } catch (e) { log(fn.name + ':', e.message || e); } busy = false; }; };

// ---------- settings changed from the device ----------
let settings = { night: false, sound: false };
try { settings = { ...settings, ...JSON.parse(fs.readFileSync(path.join(DATA, 'settings.json'), 'utf8')) }; } catch (e) { /* first run */ }
const saveSettings = () => fs.promises.mkdir(DATA, { recursive: true }).then(() => fs.promises.writeFile(path.join(DATA, 'settings.json'), JSON.stringify(settings))).catch(() => {});

// ---------- alerts: things that just happened, shown as a toast on the device ----------
const alerts = [];
function alert(kind, text, extra) {
  alerts.unshift({ id: Date.now() * 10 + (alerts.length % 10), kind, text: short(text, 150), at: Date.now(), ...(extra || {}) });
  alerts.length = Math.min(alerts.length, 30);
  log('alert:', kind, text);
  if (settings.sound) run('afplay', [CFG.soundFile]);
}

// ---------- the board (Dash/state.json, kept by Claude) ----------
function resolvePath(rel) {
  if (typeof rel !== 'string') return null;
  for (const [name, base] of ROOTS) {
    if (!rel.startsWith(name + '/')) continue;
    const p = path.resolve(base, rel.slice(name.length + 1));
    return p === base || p.startsWith(base + path.sep) ? p : null;
  }
  return null;
}
const ytLink = s => (/https?:\/\/(?:youtu\.be|(?:www\.)?youtube\.com)\/[^\s)"'<>]+/.exec(s || '') || [null])[0];
let boardCache = { mtime: 0, at: 0, data: null, paths: [] }, boardSeen = null;
const refreshBoard = guard(async function board() {
  const f = home(CFG.boardFile);
  const st = await fs.promises.stat(f);
  if (st.mtimeMs === boardCache.mtime && Date.now() - boardCache.at < 60000) return;
  const s = await readJSON(f);
  const arr = k => (Array.isArray(s[k]) ? s[k] : []);
  const paths = arr('inflight').map(i => resolvePath(i.path));
  const there = await Promise.all(paths.map(exists));
  const data = {
    updated: s.updated || '', by: s.updated_by || '',
    owed: arr('owed').map(o => ({
      id: String(o.id || ''), title: short(o.title, 90), status: String(o.status || ''),
      pct: Math.max(0, Math.min(100, Number(o.pct) || 0)), owner: short(o.owner, 20),
      due: short(o.due, 160), next: short(o.next, 420), url: ytLink([o.notes, o.due, o.next].join(' ')),
    })),
    inflight: arr('inflight').map((i, n) => ({ title: short(i.title, 90), detail: short(i.detail, 200), exists: there[n],
      pct: typeof i.pct === 'number' ? Math.max(0, Math.min(100, i.pct)) : null })),
    blocked: arr('blocked_on_joe').map(b => short(typeof b === 'string' ? b : JSON.stringify(b), 900)),
  };
  // what changed since the last look
  const seen = { inflight: new Set(data.inflight.map(i => i.title)), blocked: new Set(data.blocked.map(b => b.slice(0, 70))), status: new Map(data.owed.map(o => [o.id, o.status])) };
  if (boardSeen) {
    let n = 0;
    for (const i of data.inflight) if (!boardSeen.inflight.has(i.title) && n++ < 3) alert('ready', 'Ready for you: ' + i.title);
    for (const b of data.blocked) if (!boardSeen.blocked.has(b.slice(0, 70)) && n++ < 4) alert('you', 'Needs you: ' + short(b, 100));
    for (const o of data.owed) { const was = boardSeen.status.get(o.id); if (was && was !== o.status) alert('video', o.title + ': ' + was + ' → ' + o.status); }
  }
  boardSeen = seen;
  boardCache = { mtime: st.mtimeMs, at: Date.now(), paths, data };
});
const board = () => boardCache.data || { error: 'The board has not loaded yet.' };

// ---------- the Studio server (renders + projects), if it is running ----------
let studio = { up: false, renders: [], projects: [] }, lastProjects = [], renderSeen = new Map();
const refreshStudio = guard(async function studioRefresh() {
  const get = async p => (await fetch(CFG.studio + p, { signal: AbortSignal.timeout(2500) })).json();
  try {
    const r = await get('/renders');
    const pr = await get('/api/projects');
    studio = {
      up: true,
      renders: (Array.isArray(r) ? r : []).map(x => ({ name: short(x.name, 70), pct: Number(x.pct) || 0, state: String(x.state || ''),
        done: x.done || 0, total: x.total || 0, eta: x.eta_min == null ? null : x.eta_min, current: short(x.current, 50) })),
      projects: ((pr && pr.projects) || []).map(x => ({ id: x.id, title: short(x.title, 60), mins: Math.round((x.total || 0) / 60), exported: x.exported || '' })),
    };
    if (studio.projects.length) lastProjects = studio.projects;
    for (const x of studio.renders) {
      const was = renderSeen.get(x.name);
      if (was && was !== x.state && x.state === 'done') alert('render', 'Render finished: ' + x.name);
      if (was && was !== x.state && x.state === 'stalled') alert('render', 'Render stalled: ' + x.name);
      renderSeen.set(x.name, x.state);
    }
  } catch (e) { studio = { up: false, renders: [], projects: [] }; }
});

// ---------- encodes running right now (ffmpeg), whoever started them ----------
let encodes = [], encodeSeen = new Map();
const refreshEncodes = guard(async function encodeScan() {
  const r = await run('ps', ['-axo', 'pid=,etime=,pcpu=,command=']);
  const now = new Map();
  for (const line of r.out.split('\n')) {
    const m = /^\s*(\d+)\s+(\S+)\s+([\d.]+)\s+(.*)$/.exec(line);
    if (!m || !/(^|\/)ffmpeg(\s|$)/.test(m[4].split(' -')[0])) continue;
    const out = (/([^\/]+\.(?:mp4|mov|mkv|m4v|webm|wav|m4a|mp3))\s*$/i.exec(m[4]) || [])[1];
    if (!out) continue;
    const p = m[2].split(/[-:]/).map(Number).reverse();   // [ss, mm, hh, dd]
    now.set(m[1], { name: short(out, 60), secs: (p[0] || 0) + 60 * (p[1] || 0) + 3600 * (p[2] || 0) + 86400 * (p[3] || 0), cpu: Math.round(Number(m[3])) });
  }
  for (const [pid, e] of encodeSeen) if (!now.has(pid) && e.secs >= 60) alert('render', 'Encode finished: ' + e.name);
  encodeSeen = now;
  encodes = [...now.values()].sort((a, b) => b.secs - a.secs).slice(0, 5);
});

// ---------- newest cut of each video (the newest .mp4 in <footage>/<project>/EDIT) ----------
let cuts = {};
const STOP = new Set(['pro', 'max', 'the', 'review', 'video', 'full', 'stock', 'mini', 'and', 'photos', 'long', 'short']);
const toks = s => String(s).toLowerCase().replace(/[^a-z0-9]+/g, ' ').split(' ').filter(t => t.length >= 2 && !STOP.has(t));
const alike = (a, b) => a === b || (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a)));
function projectFor(title) {
  const t = toks(title); let best = null, top = 0;
  for (const p of lastProjects) {
    const pt = [...new Set(toks(p.id + ' ' + p.title))];
    const score = pt.filter(x => t.some(y => alike(x, y))).length;
    if (score > top) { top = score; best = p; }
  }
  return top >= 2 ? best : null;
}
const refreshCuts = guard(async function cutScan() {
  const out = {};
  for (const p of lastProjects) {
    const dir = path.join(FOOTAGE, p.id, 'EDIT');
    let names; try { names = await fs.promises.readdir(dir); } catch (e) { continue; }
    let best = null;
    for (const n of names) {
      if (!/\.(mp4|mov)$/i.test(n) || n.startsWith('.')) continue;
      try { const st = await fs.promises.stat(path.join(dir, n)); if (st.isFile() && (!best || st.mtimeMs > best.mtime)) best = { name: n, mtime: st.mtimeMs }; } catch (e) { /* gone */ }
    }
    out[p.id] = { dir, cut: best };
  }
  cuts = out;
});

// ---------- Claude sessions on this Mac: titles and task lists only, never the conversation ----------
const CLAUDE_DIR = path.join(os.homedir(), 'Library/Application Support/Claude');
let claudeFiles = [], claudeFilesAt = 0, claude = [], claudeSeen = new Map(), codeProjects = [];
async function listClaudeFiles() {
  const out = [];
  for (const kind of ['local-agent-mode-sessions', 'claude-code-sessions']) {
    const a = path.join(CLAUDE_DIR, kind);
    for (const x of await fs.promises.readdir(a).catch(() => [])) {
      if (!/^[0-9a-f-]{36}$/.test(x)) continue;
      for (const y of await fs.promises.readdir(path.join(a, x)).catch(() => []))
        for (const f of await fs.promises.readdir(path.join(a, x, y)).catch(() => []))
          if (/^local_[0-9a-f-]+\.json$/.test(f)) out.push({ kind: kind.startsWith('local') ? 'cowork' : 'code', file: path.join(a, x, y, f) });
    }
  }
  return out;
}
const mtime = p => fs.promises.stat(p).then(s => s.mtimeMs, () => 0);
// Code-tab sessions keep their task list only as plan updates inside the session's transcript file.
// That file is scanned for those plan entries and nothing else: no other line is kept, shown or sent anywhere.
const plans = new Map(), transcripts = new Map();            // per cliSessionId
const PLAN_MARKS = ['mcp__ccd_turn__update_plan', 'mcp__ccd_turn__wrap_up', '"TodoWrite"'];
function applyPlan(p, name, input) {
  const item = t => ({ s: short(t.content, 120), a: short(t.activeForm || '', 90), st: String(t.status || 'pending') });
  if (name === 'TodoWrite') { if (Array.isArray(input.todos)) p.items = input.todos.map(item); return; }
  if (name.endsWith('wrap_up')) { for (const t of p.items) t.st = 'completed'; return; }
  if (!name.endsWith('update_plan')) return;
  if (Array.isArray(input.items)) p.items = input.items.map(item);
  for (const n of Array.isArray(input.done) ? input.done : []) if (p.items[n - 1]) p.items[n - 1].st = 'completed';
  if (input.advance) {
    const i = p.items.findIndex(t => t.st === 'in_progress'); if (i >= 0) p.items[i].st = 'completed';
    const j = p.items.findIndex(t => t.st === 'pending'); if (j >= 0) p.items[j].st = 'in_progress';
  }
  if (input.start && p.items[input.start - 1]) p.items[input.start - 1].st = 'in_progress';
}
async function readPlan(cli, file) {
  let p = plans.get(cli);
  if (!p || p.file !== file) { p = { file, offset: 0, items: [], rest: '', dec: new StringDecoder('utf8') }; plans.set(cli, p); }
  const st = await fs.promises.stat(file).catch(() => null);
  if (!st) return p.items;
  if (st.size < p.offset) { p.offset = 0; p.items = []; p.rest = ''; p.dec = new StringDecoder('utf8'); }
  if (st.size === p.offset) return p.items;
  const fh = await fs.promises.open(file, 'r');
  try {
    const buf = Buffer.alloc(1 << 20);
    while (p.offset < st.size) {
      const { bytesRead } = await fh.read(buf, 0, Math.min(buf.length, st.size - p.offset), p.offset);
      if (!bytesRead) break;
      p.offset += bytesRead;
      const lines = (p.rest + p.dec.write(buf.subarray(0, bytesRead))).split('\n');
      p.rest = lines.pop();                                            // an unfinished last line waits for the next read
      for (const line of lines) {
        if (line.length > 150000 || !PLAN_MARKS.some(m => line.includes(m))) continue;
        let d; try { d = JSON.parse(line); } catch (e) { continue; }
        if (d.type !== 'assistant' || d.isSidechain || !d.message || !Array.isArray(d.message.content)) continue;
        for (const b of d.message.content) if (b && b.type === 'tool_use' && b.input && typeof b.name === 'string') applyPlan(p, b.name, b.input);
      }
    }
  } finally { await fh.close(); }
  return p.items;
}
const refreshClaude = guard(async function claudeScan() {
  if (!CFG.claudeSessions) return;
  if (Date.now() - claudeFilesAt > 60000) {
    claudeFiles = await listClaudeFiles(); claudeFilesAt = Date.now();
    const pr = path.join(os.homedir(), '.claude/projects');
    codeProjects = (await fs.promises.readdir(pr).catch(() => [])).slice(0, 200).map(n => path.join(pr, n));
  }
  const maxAge = CFG.claudeMaxAgeHours * 3600000, filter = CFG.claudeTitleFilter ? new RegExp(CFG.claudeTitleFilter, 'i') : null;
  const out = [];
  for (const { kind, file } of claudeFiles) {
    const fm = await mtime(file);
    if (!fm || Date.now() - fm > maxAge) continue;
    let d; try { d = await readJSON(file); } catch (e) { continue; }
    if (d.isArchived || !d.title || (filter && !filter.test(d.title))) continue;
    const cli = d.cliSessionId, base = file.slice(0, -5);
    const taskDir = !cli ? null : kind === 'cowork' ? path.join(base, '.claude/tasks', cli) : path.join(os.homedir(), '.claude/tasks', cli);
    const tasks = []; let newest = 0;
    for (const f of (taskDir ? await fs.promises.readdir(taskDir).catch(() => []) : []).filter(n => /^\d+\.json$/.test(n)).slice(0, 300)) {
      try { const t = await readJSON(path.join(taskDir, f)); tasks.push({ id: Number(t.id) || 0, s: short(t.subject, 120), a: short(t.activeForm || '', 90), st: t.status }); newest = Math.max(newest, await mtime(path.join(taskDir, f))); } catch (e) { /* mid-write */ }
    }
    tasks.sort((a, b) => a.id - b.id);
    let transcript = 0;                                   // its modified time, and for Code-tab sessions its plan entries (see readPlan)
    if (cli && kind === 'cowork') transcript = await mtime(path.join(base, '.claude/projects/session', cli + '.jsonl'));
    else if (cli) {
      let tf = transcripts.get(cli);
      if (!tf) for (const pd of codeProjects) { const f = path.join(pd, cli + '.jsonl'); if (await mtime(f)) { tf = f; transcripts.set(cli, f); break; } }
      if (tf) {
        transcript = await mtime(tf);
        if (!tasks.length) try { (await readPlan(cli, tf)).forEach((t, i) => tasks.push({ id: i + 1, s: t.s, a: t.a, st: t.st })); } catch (e) { /* unreadable right now */ }
      }
    }
    const last = Math.max(Number(d.lastActivityAt) || 0, fm, newest, transcript);
    if (Date.now() - last > maxAge) continue;
    const live = tasks.filter(t => t.st !== 'deleted'), done = live.filter(t => t.st === 'completed').length;
    const act = live.find(t => t.st === 'in_progress'), next = live.find(t => t.st === 'pending');
    out.push({ key: path.basename(base), title: short(d.title, 60), kind, last, working: Date.now() - last < 120000,
      done, total: live.length, active: act ? (act.a || act.s) : '', next: next ? next.s : '',
      tasks: live.slice(-40).map(t => ({ s: t.s, st: t.st })) });
  }
  out.sort((a, b) => (b.working - a.working) || (b.last - a.last));
  for (const s of out) {
    const was = claudeSeen.get(s.key);
    if (was) {
      let n = 0;
      for (const t of s.tasks) if (t.st === 'completed' && was.open.has(t.s) && n++ < 3) alert('claude', 'Claude finished: ' + t.s);
      if (was.working && !s.working && was.since && Date.now() - was.since > 180000) alert('claude', 'Claude stopped working in ' + s.title + ' (may be waiting on you)');
    }
    claudeSeen.set(s.key, { open: new Set(s.tasks.filter(t => t.st !== 'completed').map(t => t.s)), working: s.working, since: s.working ? ((was && was.working && was.since) || Date.now()) : 0 });
  }
  claude = out.slice(0, 6);
});

// ---------- YouTube: public numbers only (channel page + upload feed), with a little history ----------
let yt = { ok: false }, history = [];
try { history = JSON.parse(fs.readFileSync(path.join(DATA, 'history.json'), 'utf8')); } catch (e) { /* first run */ }
const unx = s => String(s).replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#0?39;|&apos;/g, "'").replace(/&amp;/g, '&');
const subsNum = s => { const m = /^([\d.,]+)([KM]?)$/.exec(s || ''); return m ? Math.round(parseFloat(m[1].replace(/,/g, '')) * (m[2] === 'M' ? 1e6 : m[2] === 'K' ? 1e3 : 1)) : null; };
const refreshYT = guard(async function youtube() {
  try {
    const html = await (await fetch(CFG.channel, { headers: { 'User-Agent': 'Mozilla/5.0', 'Accept-Language': 'en-US' }, signal: AbortSignal.timeout(20000) })).text();
    const subs = (/"content":"([\d.,]+[KM]?) subscribers"/.exec(html) || [])[1];
    const count = (/"content":"([\d.,]+[KM]?) videos"/.exec(html) || [])[1];
    const cid = (/"externalId":"(UC[\w-]{22})"/.exec(html) || [])[1];
    if (!subs || !cid) throw new Error('channel page did not parse');
    const xml = await (await fetch('https://www.youtube.com/feeds/videos.xml?channel_id=' + cid, { signal: AbortSignal.timeout(20000) })).text();
    const videos = xml.split('<entry>').slice(1).map(e => ({
      id: (/<yt:videoId>([^<]+)/.exec(e) || [])[1],
      title: short(unx((/<title>([^<]*)/.exec(e) || [])[1] || ''), 80),
      at: Date.parse((/<published>([^<]+)/.exec(e) || [])[1]),
      views: Number((/statistics views="(\d+)"/.exec(e) || [])[1] || 0),
      likes: Number((/starRating count="(\d+)"/.exec(e) || [])[1] || 0),
      short: /\/shorts\//.test(e),
    })).filter(v => v.id);
    if (yt.subs && yt.subs !== subs) alert('subs', 'Subscribers: ' + subs, { celebrate: (subsNum(subs) || 0) > (subsNum(yt.subs) || 0), big: subs });
    yt = { ok: true, subs, count: count || '', videos, fetched: Date.now() };
    history.push({ t: Date.now(), subs, v: Object.fromEntries(videos.map(v => [v.id, v.views])) });
    history = history.filter(h => Date.now() - h.t < 8 * 86400000);
    await fs.promises.mkdir(DATA, { recursive: true });
    await fs.promises.writeFile(path.join(DATA, 'history.json'), JSON.stringify(history));
  } catch (e) { yt = { ...yt, ok: !!yt.videos, error: String(e.message || e) }; throw e; }
});
// views gained by a video since the oldest snapshot inside the last `hours` (and how long that span really is)
function gain(id, hours) {
  const nowV = (yt.videos || []).find(v => v.id === id); if (!nowV) return null;
  const base = history.find(h => Date.now() - h.t <= hours * 3600000 && h.v && h.v[id] != null);
  if (!base) return null;
  return { d: Math.max(0, nowV.views - base.v[id]), h: (Date.now() - base.t) / 3600000 };
}
function channel() {
  const v = yt.videos || [], n = subsNum(yt.subs);
  let day = 0, span = 0;
  const vids = v.slice(0, 10).map(x => {
    const g24 = gain(x.id, 24), g3 = gain(x.id, 3);
    if (g24) { day += g24.d; span = Math.max(span, g24.h); }
    return { id: x.id, title: x.title, views: x.views, likes: x.likes, short: x.short, age: ago(x.at),
      perHr: g3 && g3.h >= 0.3 ? Math.round(g3.d / g3.h) : null };
  });
  for (const x of v.slice(10)) { const g = gain(x.id, 24); if (g) day += g.d; }
  let goal = CFG.subGoal; while (n != null && goal <= n) goal += 5000;
  return { ok: yt.ok, subs: yt.subs || '', count: yt.count || '', videos: vids,
    recentViews: v.reduce((a, x) => a + x.views, 0), recentCount: v.length,
    day, daySpan: Math.round(span * 10) / 10, goal, goalPct: n == null ? 0 : Math.max(0, Math.min(100, Math.round(100 * (n - (goal - 5000)) / 5000))), toGoal: n == null ? null : goal - n };
}

// ---------- standby media: pictures and short clips behind the ambient screen ----------
// Pictures: the channel's thumbnails, the pictures on the website, and any folders named in config.
// Clips: short silent excerpts of finished videos on this Mac, cut into JPEG frames, because the
// Car Thing's browser has no video decoder at all. ffmpeg prepares everything here; it is kept in data/media.
const MEDIA = path.join(DATA, 'media'), PICS = path.join(MEDIA, 'pics'), CLIPS = path.join(MEDIA, 'clips');
const PIC_MAX = 90, CLIP_MAX = 8, CLIP_FPS = 12, SHOTS = 3, SHOT_SEC = 3.4;   // 12 a second is what the Car Thing can decode without skipping
const VIDEO = /\.(mp4|mov|m4v|mkv)$/i, IMAGE = /\.(jpe?g|png|webp)$/i;
const num = n => String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
let FFMPEG = null, FFPROBE = null, pics = [], clips = [], picBag = [], mediaHold = 0, siteHost = '';
const picFailed = new Set(), clipFailed = new Set();
try { pics = JSON.parse(fs.readFileSync(path.join(MEDIA, 'pics.json'), 'utf8')).filter(p => p && p.file && fs.existsSync(path.join(PICS, p.file))); } catch (e) { /* none yet */ }
try {
  for (const id of fs.readdirSync(CLIPS)) {
    try { const c = JSON.parse(fs.readFileSync(path.join(CLIPS, id, 'meta.json'), 'utf8')); if (c.id === id && c.n > 0) clips.push(c); } catch (e) { /* half-made: the clip maker removes it */ }
  }
} catch (e) { /* none yet */ }
try { siteHost = CFG.website ? new URL(CFG.website).host : ''; } catch (e) { /* not a URL */ }
async function findTool(name) {
  for (const d of new Set([...String(process.env.PATH || '').split(':'), '/opt/homebrew/bin', '/usr/local/bin'])) if (d && await exists(path.join(d, name))) return path.join(d, name);
  return null;
}
async function probe(file) {                                  // size as displayed (rotation applied) and length
  const r = await run(FFPROBE, ['-v', 'error', '-select_streams', 'v:0', '-show_streams', '-show_format', '-of', 'json', file], 20000);
  try {
    const j = JSON.parse(r.out), s = (j.streams || [])[0];
    if (!s || !s.width || !s.height) return null;
    const rot = Number((s.tags && s.tags.rotate) || ((s.side_data_list || []).find(x => x.rotation != null) || {}).rotation || 0);
    const turned = Math.abs(rot) % 180 === 90;
    return { w: turned ? s.height : s.width, h: turned ? s.width : s.height, dur: Number(j.format && j.format.duration) || 0 };
  } catch (e) { return null; }
}
// fill a W×H frame edge to edge; a tall picture or video keeps the band a little above its middle, where the subject usually is
const fit = (info, W, H) => `scale=${W}:${H}:force_original_aspect_ratio=increase,crop=${W}:${H}:(iw-ow)/2:(ih-oh)*${info.w / info.h >= 1.2 ? 0.5 : 0.4}`;
async function makePic(src, out, bars) {
  const info = await probe(src);
  if (!info || info.w < 480 || info.h < 300) return false;   // logos and icons are not backgrounds
  if (bars) info.h = Math.round(info.w * 9 / 16);              // a 4:3 thumbnail with black bars: keep its 16:9 middle
  const r = await run(FFMPEG, ['-hide_banner', '-loglevel', 'error', '-y', '-i', src, '-frames:v', '1', '-vf', (bars ? 'crop=iw:iw*9/16,' : '') + fit(info, 800, 480), '-q:v', '4', '-pix_fmt', 'yuvj420p', out], 30000);
  return r.ok && await exists(out);
}
async function download(url, file, max = 16 << 20) {
  const r = await fetch(url, { headers: { 'User-Agent': 'Mozilla/5.0' }, signal: AbortSignal.timeout(25000) });
  if (!r.ok || Number(r.headers.get('content-length') || 0) > max) return false;
  const buf = Buffer.from(await r.arrayBuffer());
  if (!buf.length || buf.length > max) return false;
  await fs.promises.writeFile(file, buf);
  return true;
}
const refreshPics = guard(async function pictures() {
  if (!FFMPEG || !CFG.standbyPictures) return;
  const cands = [], live = { yt: false, site: false, file: true };
  for (const v of (yt.videos || []).slice(0, 15)) {
    const b = 'https://i.ytimg.com/vi/' + v.id + '/'; live.yt = true;
    cands.push({ key: 'yt:' + v.id, kind: 'yt', cap: v.title, urls: [[b + (v.short ? 'oar2.jpg' : 'maxresdefault.jpg')], [b + 'hq720.jpg'], [b + 'hqdefault.jpg', true]] });
  }
  if (CFG.website) try {
    const site = new URL(CFG.website), seen = new Set();
    const html = await (await fetch(site, { headers: { 'User-Agent': 'Mozilla/5.0' }, signal: AbortSignal.timeout(25000) })).text();
    for (const tag of html.match(/<img\b[^>]*>/gi) || []) {
      const src = (/\bsrc="([^"]+)"/i.exec(tag) || [])[1]; if (!src) continue;
      let u; try { u = new URL(unx(src), site); } catch (e) { continue; }
      if (u.host !== site.host || seen.has(u.href) || !IMAGE.test(u.pathname)) continue;          // this site's own pictures only
      if (/logo|lockup|icon|badge|avatar|sprite|[-_]mark\b/i.test(u.pathname)) continue;
      seen.add(u.href);
      const alt = unx((/\balt="([^"]*)"/i.exec(tag) || [])[1] || '').split(' — ')[0].trim();
      cands.push({ key: 'site:' + u.href, kind: 'site', cap: short(alt, 70), urls: [[u.href]] });
      if (seen.size >= 60) break;
    }
    live.site = seen.size > 0;
  } catch (e) { log('pictures: the website could not be read:', e.message || e); }
  for (const dir of (CFG.pictureFolders || []).map(home))
    for (const n of (await fs.promises.readdir(dir).catch(() => [])).filter(n => IMAGE.test(n) && !n.startsWith('.')).slice(0, 60))
      cands.push({ key: 'file:' + path.join(dir, n), kind: 'file', cap: n.replace(IMAGE, '').replace(/[_-]+/g, ' '), file: path.join(dir, n) });
  await fs.promises.mkdir(PICS, { recursive: true });
  const have = new Map(pics.map(p => [p.key, p])), next = []; let made = 0;
  for (const c of cands) {
    if (next.length >= PIC_MAX) break;
    const old = have.get(c.key);
    if (old && await exists(path.join(PICS, old.file))) { next.push({ ...old, cap: c.cap }); continue; }
    if (picFailed.has(c.key)) continue;
    const name = createHash('sha1').update(c.key).digest('hex').slice(0, 16) + '.jpg', tmp = path.join(MEDIA, 'tmp-' + name);
    let ok = false;
    try {
      if (c.file) ok = await makePic(c.file, path.join(PICS, name), false);
      else for (const [url, bars] of c.urls) if (await download(url, tmp) && await makePic(tmp, path.join(PICS, name), bars)) { ok = true; break; }
    } catch (e) { /* skip this one */ }
    fs.promises.unlink(tmp).catch(() => {});
    if (ok) { next.push({ key: c.key, file: name, cap: c.cap, kind: c.kind }); made++; } else picFailed.add(c.key);
  }
  const got = new Set(next.map(p => p.key));
  for (const p of pics) if (!live[p.kind] && !got.has(p.key) && next.length < PIC_MAX) next.push(p);   // a source that is offline keeps its pictures
  pics = next; picBag = [];
  const keep = new Set(pics.map(p => p.file));
  for (const f of await fs.promises.readdir(PICS).catch(() => [])) if (!keep.has(f)) fs.promises.unlink(path.join(PICS, f)).catch(() => {});
  await fs.promises.writeFile(path.join(MEDIA, 'pics.json'), JSON.stringify(pics));
  if (made) log(`standby pictures: ${pics.length} ready (${made} new)`);
});
function nextPic() {
  if (!pics.length) return null;
  if (!picBag.length) { picBag = pics.map(p => p.key); for (let i = picBag.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [picBag[i], picBag[j]] = [picBag[j], picBag[i]]; } }
  const key = picBag.pop();
  return pics.find(p => p.key === key) || pics[0];
}
const picSub = p => { const v = p.kind === 'yt' && (yt.videos || []).find(x => 'yt:' + x.id === p.key); return v ? num(v.views) + ' views' : p.kind === 'site' ? siteHost : ''; };

// clips: the newest export in each project's EDIT folder, plus everything in the finished-videos folders
let clipSrc = [], clipSrcAt = 0;
async function clipSources() {
  const out = [], vids = async d => (await fs.promises.readdir(d).catch(() => [])).filter(n => VIDEO.test(n) && !n.startsWith('.'));
  const size = async f => fs.promises.stat(f).then(s => (s.isFile() ? { size: s.size, m: s.mtimeMs } : null), () => null);
  for (const d of [path.join(FOOTAGE, '_FINISHED VIDEOS'), ...(CFG.clipFolders || []).map(home)])
    for (const n of await vids(d)) { const s = await size(path.join(d, n)); if (s && s.size > 20e6) out.push({ file: path.join(d, n), label: n.replace(VIDEO, '').replace(/[_-]+/g, ' ').trim() }); }
  for (const proj of await fs.promises.readdir(FOOTAGE).catch(() => [])) {
    if (proj.startsWith('.') || proj.startsWith('_')) continue;
    const d = path.join(FOOTAGE, proj, 'EDIT'), st = [];
    for (const n of await vids(d)) { const s = await size(path.join(d, n)); if (s && s.size > 20e6) st.push({ n, m: s.m }); }
    const pick = st.filter(x => /master|proof|final|export/i.test(x.n)).sort((a, b) => b.m - a.m)[0] || st.sort((a, b) => b.m - a.m)[0];
    if (pick) out.push({ file: path.join(d, pick.n), label: proj });
  }
  return out;
}
async function makeClip(src) {
  const info = await probe(src.file);
  if (!info || info.dur < 30) return false;
  const id = Date.now().toString(36), dir = path.join(CLIPS, id);
  await fs.promises.mkdir(dir, { recursive: true });
  let n = 0;
  for (let k = 0; k < SHOTS; k++) {                             // three short shots from different parts of the video
    const at = info.dur * (0.06 + 0.86 * (k + Math.random()) / SHOTS);
    await run('nice', ['-n', '15', FFMPEG, '-hide_banner', '-loglevel', 'error', '-y', '-threads', '2', '-ss', at.toFixed(2), '-i', src.file, '-t', String(SHOT_SEC), '-an', '-sn',
      '-vf', fit(info, 640, 384) + ',fps=' + CLIP_FPS, '-q:v', '10', '-pix_fmt', 'yuvj420p', '-start_number', String(n + 1), path.join(dir, '%04d.jpg')], 180000);
    n = (await fs.promises.readdir(dir)).filter(f => /^\d{4}\.jpg$/.test(f)).length;
  }
  if (n < CLIP_FPS * 4) { await fs.promises.rm(dir, { recursive: true, force: true }); return false; }
  const c = { id, label: src.label, src: src.file, n, fps: CLIP_FPS, made: Date.now(), shown: 0 };
  await fs.promises.writeFile(path.join(dir, 'meta.json'), JSON.stringify(c));
  clips.push(c); log(`standby clips: made "${c.label}" (${n} frames), ${clips.length} ready`);
  return true;
}
const refreshClips = guard(async function clipMaker() {
  if (!FFMPEG || !CFG.standbyClips) return;
  const ids = new Set(clips.map(c => c.id));
  for (const d of await fs.promises.readdir(CLIPS).catch(() => [])) if (!ids.has(d)) await fs.promises.rm(path.join(CLIPS, d), { recursive: true, force: true });
  if (encodes.length || os.loadavg()[0] / os.cpus().length > 0.7) return;                       // stay out of the way of real work
  if (Date.now() - clipSrcAt > 1800000 || !clipSrc.length) { clipSrc = await clipSources(); clipSrcAt = Date.now(); }
  const ok = clipSrc.filter(s => !clipFailed.has(s.file));
  if (!ok.length) return;
  if (clips.length >= CLIP_MAX) {                                // full: swap out a clip once it has been played three times
    const old = clips.filter(c => c.shown >= 3).sort((a, b) => a.made - b.made)[0];
    if (!old) return;
    clips = clips.filter(c => c !== old);
    await fs.promises.rm(path.join(CLIPS, old.id), { recursive: true, force: true });
  }
  const used = new Set(clips.map(c => c.src)), fresh = ok.filter(s => !used.has(s.file)), pool = fresh.length ? fresh : ok;
  const src = pool[Math.floor(Math.random() * pool.length)];
  if (!(await makeClip(src))) clipFailed.add(src.file);
});

// what the standby screen asked for: one picture, or one clip sent as batches of frames
let device = null, mediaJob = 0;
const mediaOn = () => Date.now() > mediaHold;
async function sendMedia(kind) {
  const dev = device, job = ++mediaJob;
  if (!dev) return;
  const call = (fn, ...args) => dev.eval(`(window.__ct&&__ct.${fn}?(__ct.${fn}(${args.map(js).join(',')}),1):0)`);
  if (!mediaOn()) return call('nomedia');
  if (kind === 'clip' && clips.length && CFG.standbyClips) {
    const c = clips.slice().sort((a, b) => a.shown - b.shown || a.made - b.made)[0], dir = path.join(CLIPS, c.id);
    await call('clip', { id: c.id, cap: c.label, fps: c.fps, n: c.n });
    for (let i = 1; i <= c.n; i += 6) {
      if (job !== mediaJob || device !== dev) return;           // the screen woke up, or the link changed
      const part = [];
      for (let k = i; k < i + 6 && k <= c.n; k++) part.push((await fs.promises.readFile(path.join(dir, String(k).padStart(4, '0') + '.jpg'))).toString('base64'));
      await call('frames', c.id, part);
    }
    await call('clipEnd', c.id);
    c.shown++; fs.promises.writeFile(path.join(dir, 'meta.json'), JSON.stringify(c)).catch(() => {});
    return log(`standby: played a clip from "${c.label}"`);
  }
  const p = CFG.standbyPictures ? nextPic() : null;
  if (!p) return call('nomedia');
  await call('pic', { id: p.file, cap: p.cap, sub: picSub(p), b64: (await fs.promises.readFile(path.join(PICS, p.file))).toString('base64') });
}
// after a clip: collect the page's garbage, then have the Car Thing's browser empty its caches (it has very little memory)
async function tidyDevice() {
  const dev = device; if (!dev) return;
  await dev.gc();
  try {
    const ver = await (await fetch(`http://127.0.0.1:${DEVTOOLS_PORT}/json/version`, { signal: AbortSignal.timeout(3000) })).json();
    if (!ver.webSocketDebuggerUrl) return;
    await new Promise(res => {
      const ws = new WebSocket(ver.webSocketDebuggerUrl), done = () => { try { ws.close(); } catch (e) { /* closed */ } res(); };
      ws.onopen = () => ws.send(JSON.stringify({ id: 1, method: 'Memory.simulatePressureNotification', params: { level: 'critical' } }));
      ws.onmessage = done; ws.onerror = done; setTimeout(done, 3000);
    });
  } catch (e) { /* not essential */ }
}

// ---------- the Mac: volume, disks, load ----------
let vol = { v: null, muted: false }, volPending = null, volBusy = false, disks = [];
async function readVol() {
  if (volBusy || volPending != null) return;
  const r = await run('osascript', ['-e', 'get volume settings']);
  const m = /output volume:(\d+|missing value).*output muted:(true|false|missing value)/.exec(r.out);
  if (m && volPending == null) vol = { v: /^\d+$/.test(m[1]) ? +m[1] : null, muted: m[2] === 'true' };
}
async function setVol(v) {
  volPending = vol.v = Math.max(0, Math.min(100, Math.round(v)));
  if (volBusy) return;
  volBusy = true;
  while (volPending != null) { const t = volPending; volPending = null; await run('osascript', ['-e', `set volume output volume ${t}`]); }
  volBusy = false;
}
const refreshDisks = guard(async function diskScan() {
  const out = [];
  for (const d of CFG.disks) {
    const r = await run('df', ['-k', home(d.path)]);
    const m = /\n\S.*?\s+(\d+)\s+(\d+)\s+(\d+)\s+\d+%/.exec(r.out);
    out.push(m ? { label: d.label, ok: true, totalGB: Math.round(m[1] / 1048576), freeGB: Math.round(m[3] / 1048576), pct: Math.round(100 * (1 - m[3] / m[1])) } : { label: d.label, ok: false });
  }
  disks = out;
});

// ---------- actions coming from the device ----------
const OPENABLE = new Set(['.mp4', '.mov', '.m4v', '.m4a', '.mp3', '.wav', '.png', '.jpg', '.jpeg', '.pdf', '.md', '.txt', '.csv']);
async function openPath(p) {
  if (!(await exists(p))) return false;
  const dir = (await fs.promises.stat(p)).isDirectory();
  await run('open', dir || OPENABLE.has(path.extname(p).toLowerCase()) ? [p] : ['-R', p]);   // scripts and unknown files are shown in Finder, never run
  return true;
}
let deviceSerial = null, reinject = false;
// trusted is true for the Car Thing and for this Mac's own preview, false for a paired screen elsewhere on the network
async function act(a, trusted) {
  if (!a || typeof a !== 'object') return;
  if (a.t === 'key') return;
  if (a.t === 'pair' || a.t === 'pairStop' || a.t === 'forget') {          // only the Deck itself adds or removes other screens
    if (!trusted) return;
    if (a.t === 'pair') return openPairing();
    if (a.t === 'pairStop') { pairing = null; return; }
    return forgetScreen(a.id);
  }
  if (a.t === 'drive') {                                                     // a window on this Mac saying what it shows, for the other screens to copy
    if (!trusted || !a.v || typeof a.v !== 'object') return;
    const sh = a.v.sheet && typeof a.v.sheet === 'object' ? { kind: String(a.v.sheet.kind || '').slice(0, 12), arg: String(a.v.sheet.arg == null ? '' : a.v.sheet.arg).slice(0, 200) } : null;
    const v = { tab: Math.max(0, Math.min(3, Math.round(Number(a.v.tab)) || 0)), sheet: sh, amb: !!a.v.amb, sc: Math.max(0, Math.min(1, Number(a.v.sc) || 0)) }, s = JSON.stringify(v);
    if (!driveView) log('screens: a window on this Mac is now controlling the other screens');
    if (!driveView || driveView.s !== s) driveView = { n: Date.now(), s, v, cid: String(a.cid || '').slice(0, 16) };
    driveView.at = Date.now();
    return;
  }
  if (a.t === 'driveStop') { if (trusted && driveView) { driveView = null; log('screens: control of the other screens is off'); } return; }
  if (a.t === 'usbOpen') {                                                   // open the Deck in the browser of a phone that is on USB
    const p = trusted && usbPhones.find(k => k.id === a.id);
    if (!p) return;
    log(`usb: opening the Deck on ${p.name}`);
    return run('adb', ['-s', p.serial, 'shell', `input keyevent KEYCODE_WAKEUP; am start -a android.intent.action.VIEW -d http://localhost:${PORT}/`]);
  }
  if (a.t === 'screen') {                                                    // a browser saying what it is, once: kept in the log for troubleshooting
    return log(`screen (${trusted ? 'this Mac or a phone on USB' : 'paired'}): ${Number(a.w) || 0}x${Number(a.h) || 0}${a.touch ? ', touch' : ''}${a.full ? ', full screen' : ''}, stay-awake ${short(a.wake, 40)}`);
  }
  if (a.t === 'media') return sendMedia(a.kind === 'clip' ? 'clip' : 'pic').catch(e => { log('standby media:', e.message || e); if (device) device.eval('window.__ct&&__ct.nomedia&&__ct.nomedia()'); });
  if (a.t === 'mediaDone' || a.t === 'mediaStop') { mediaJob++; return tidyDevice(); }
  if (a.t !== 'vol') log('action:', JSON.stringify(a));
  if (a.t === 'vol' && vol.v != null) return setVol(vol.v + (Number(a.d) || 0));
  if (a.t === 'mute') { vol.muted = !vol.muted; return run('osascript', ['-e', `set volume output muted ${vol.muted}`]); }
  if (a.t === 'app') {
    const x = CFG.apps.find(k => k.key === a.key);
    if (!x) return;
    if (x.key === 'studio') return run('open', [studio.up ? CFG.studio + '/' : home(CFG.studioApp)]);
    if (x.url) return run('open', [x.url]);
    if (x.app) return run('open', ['-a', x.app]);
    if (x.path) return openPath(home(x.path));
  }
  if (a.t === 'inflight') return openPath((boardCache.paths || [])[a.i]);
  if (a.t === 'owed') { const o = (board().owed || []).find(k => k.id === a.id); if (o && o.url) return run('open', [o.url]); }
  if (a.t === 'cut' || a.t === 'folder') {
    const o = (board().owed || []).find(k => k.id === a.id), p = o && projectFor(o.title), c = p && cuts[p.id];
    if (!c) return;
    return openPath(a.t === 'cut' && c.cut ? path.join(c.dir, c.cut.name) : c.dir);
  }
  if (a.t === 'video') { const v = (yt.videos || []).find(k => k.id === a.id); if (v) return run('open', ['https://www.youtube.com/watch?v=' + v.id]); }
  if (a.t === 'night') { settings.night = !!a.on; saveSettings(); if (deviceSerial) setBrightness(deviceSerial); return; }
  if (a.t === 'sound') { settings.sound = !!a.on; saveSettings(); if (settings.sound) run('afplay', [CFG.soundFile]); return; }
  if (a.t === 'reload') { reinject = true; return; }
}

// ---------- other screens: a phone, tablet or another computer on this network ----------
// Off unless config.json has "remote": true. A new screen is paired once: the Deck (or the preview on this Mac) shows a 6-digit
// code for five minutes, the new screen sends it back, and from then on it carries a long random key in a cookie. Only a hash of
// each key is kept. The Car Thing and this Mac are always trusted, and only they can show a code or forget a screen.
const SCREENS_FILE = path.join(DATA, 'screens.json'), COOKIE = 'deck_screen', PAIR_MS = 300000, MAX_SCREENS = 8;
let screens = [], screensDirty = false, pairing = null, lanIP = null;
try { screens = JSON.parse(fs.readFileSync(SCREENS_FILE, 'utf8')).filter(s => s && s.id && s.hash && s.name); } catch (e) { /* none paired yet */ }
const saveScreens = () => fs.promises.mkdir(DATA, { recursive: true })
  .then(() => fs.promises.writeFile(SCREENS_FILE, JSON.stringify(screens, null, 1), { mode: 0o600 })).catch(e => log('screens: could not save:', e.message || e));
const sha = s => createHash('sha256').update(s).digest('hex');
const privateNet = a => /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.)/.test(a || '');   // home and office networks only, never the open internet
// This Mac's address for other screens. A Mac can be on two networks at once (a cable to one router, Wi-Fi from another), and
// phones and tablets are on the Wi-Fi, so the Wi-Fi address is the one handed out when there is one. The address of the network
// the Mac reaches the internet through is kept as a second choice, shown under it.
let lanAlt = null, wifiDev;
const refreshLan = guard(async function lan() {
  if (wifiDev === undefined) wifiDev = (/Hardware Port: Wi-Fi\s*\nDevice:\s*(\S+)/.exec((await run('networksetup', ['-listallhardwareports'])).out) || [])[1] || null;
  const r = await run('route', ['-n', 'get', 'default']), via = (/interface:\s*(\S+)/.exec(r.out) || [])[1], nets = os.networkInterfaces();
  const pick = name => ((nets[name] || []).find(a => a.family === 'IPv4' && !a.internal && privateNet(a.address)) || {}).address || null;
  const wifi = wifiDev ? pick(wifiDev) : null;
  let main = via ? pick(via) : null;
  for (const name of Object.keys(nets)) if (!main) main = pick(name);
  const before = lanIP;
  lanIP = wifi || main; lanAlt = wifi && main && main !== wifi ? main : null;
  if (lanIP !== before && CFG.remote) log(`screens: other screens open http://${lanIP || '(no network)'}:${PORT}${lanAlt ? ` (Wi-Fi), or http://${lanAlt}:${PORT} on the wired network` : ''}`);
});
const remoteUrl = () => { const h = String(CFG.remoteName || '').trim() || lanIP; return h ? `http://${h}:${PORT}` : null; };
function openPairing() {
  if (!CFG.remote) return;
  const code = String(randomInt(0, 1000000)).padStart(6, '0'), url = remoteUrl(), q = url && qr(`${url}/#${code}`);
  pairing = { code, exp: Date.now() + PAIR_MS, tries: 0, qr: q ? { n: q.n, d: qrPath(q) } : null };
  log('screens: showing a pairing code for 5 minutes');
}
const screenName = (ua, touch) => (/iPad/.test(ua) || (/Macintosh/.test(ua) && touch) ? 'iPad' : /iPhone/.test(ua) ? 'iPhone'
  : /Android/.test(ua) ? (/Mobile/.test(ua) ? 'Android phone' : 'Android tablet') : /CrOS/.test(ua) ? 'Chromebook'
  : /Macintosh/.test(ua) ? 'Mac' : /Windows/.test(ua) ? 'Windows PC' : /Linux/.test(ua) ? 'Linux computer' : 'Browser');
function tryPair(body, req) {
  if (pairing && Date.now() > pairing.exp) pairing = null;
  if (!pairing) return { ok: false, why: 'closed' };
  const code = String((body && body.code) || '').replace(/\D/g, '');
  if (code.length !== 6 || !timingSafeEqual(Buffer.from(code), Buffer.from(pairing.code))) {
    if (++pairing.tries >= 5) { pairing = null; log('screens: five wrong codes, the pairing code is withdrawn'); return { ok: false, why: 'closed' }; }
    return { ok: false, why: 'wrong' };
  }
  pairing = null;
  const key = randomBytes(32).toString('base64url');
  const base = body && body.app ? cleanName(String(body.name || 'Ride app')).slice(0, 30) : screenName(String(req.headers['user-agent'] || ''), !!(body && body.touch));
  let name = base, k = 1;
  while (screens.some(s => s.name === name)) name = `${base} ${++k}`;
  screens.push({ id: randomBytes(4).toString('hex'), name, hash: sha(key), added: Date.now(), seen: Date.now() });
  while (screens.length > MAX_SCREENS) screens.splice(screens.indexOf(screens.reduce((a, b) => (a.seen <= b.seen ? a : b))), 1);   // the one unseen for longest makes room
  saveScreens();
  alert('screen', `${name} is now paired as a screen`);
  return { ok: true, key, name };
}
function forgetScreen(id) {
  const n = screens.length;
  screens = id === 'all' ? [] : screens.filter(s => s.id !== id);
  if (screens.length !== n) { saveScreens(); log(`screens: forgot ${n - screens.length}, ${screens.length} still paired`); }
}
function screenFor(req) {                                                     // the paired screen a request comes from, if any
  const m = /(?:^|;\s*)deck_screen=([A-Za-z0-9_-]{30,90})(?:;|$)/.exec(String(req.headers.cookie || '')) || /^()?([A-Za-z0-9_-]{30,90})$/.exec(String(req.headers['x-deck-token'] || ''));
  if (!m) return null;
  if (m[2]) m[1] = m[2];                                                      // the key came in the header an app uses, not in a cookie
  const h = sha(m[1]), s = screens.find(k => k.hash === h);
  if (!s) return null;
  if (Date.now() - s.seen > 60000) { s.seen = Date.now(); screensDirty = true; }
  req.deckKey = m[1];
  return s;
}
const cookieFor = key => `${COOKIE}=${key}; Max-Age=31536000; Path=/; HttpOnly; SameSite=Strict`;
setInterval(() => { if (screensDirty) { screensDirty = false; saveScreens(); } }, 300000);
// Android phones on USB. With "usbScreens" on, each one gets the Deck's port mapped onto the phone's own localhost (adb reverse),
// so its browser reaches the Deck at http://localhost:PORT with no network at all. A request that arrives that way counts as this
// Mac's own: the phone is cabled to it and was allowed to debug by hand. The mapping is lost whenever the cable is unplugged or
// even jostled, so it is put back every few seconds.
// This is NEVER done to the Car Thing, whose adb service crashes when asked for a reverse mapping. A device is treated as a phone
// only if it is not the Car Thing by name, is not the device the Deck is driving, and answers the way Android does.
const CAR_THING = /spotify-car-thing|Car_Thing|superbird/i;
let usbPhones = [];
// what the controlling window on this Mac is showing; forgotten a minute after that window stops reporting (closed, or asleep)
let driveView = null;
const driveInfo = () => { if (driveView && Date.now() - driveView.at > 60000) { driveView = null; log('screens: the controlling window went quiet, other screens are on their own again'); } return driveView ? { n: driveView.n, v: driveView.v, cid: driveView.cid } : null; };
const refreshUsb = guard(async function usb() {
  const d = await run('adb', ['devices', '-l']), found = [];
  for (const line of (d.ok ? d.out : '').split('\n')) {
    if (!/\sdevice\s/.test(line) || CAR_THING.test(line)) continue;
    const serial = line.split(/\s+/)[0];
    if (!serial || serial === deviceSerial) continue;
    let p = usbPhones.find(k => k.serial === serial);
    if (!p) {
      const a = await run('adb', ['-s', serial, 'shell', 'getprop ro.build.version.sdk; getprop ro.product.model']);
      const [sdk, model] = a.out.split('\n').map(x => x.trim());
      if (!a.ok || !/^\d+$/.test(sdk || '')) continue;                         // not Android: left alone
      p = { serial, id: sha(serial).slice(0, 8), name: short(model || 'Android phone', 30), linked: false };
    }
    const r = await run('adb', ['-s', serial, 'reverse', `tcp:${PORT}`, `tcp:${PORT}`]);
    if (!r.ok) continue;
    if (!p.linked) { p.linked = true; log(`usb: ${p.name} is linked; its browser can open http://localhost:${PORT}/`); }
    if (CFG.usbOffload && !p.offloading && Date.now() - (p.offloadAt || 0) > 120000) {   // on plug-in, then every two minutes while it stays plugged in
      p.offloading = true;
      offloadPhone(p).catch(e => log('offload:', e.message || e)).then(() => { p.offloading = false; p.offloadAt = Date.now(); });
    }
    found.push(p);
  }
  for (const p of usbPhones) if (!found.includes(p)) log(`usb: ${p.name} is no longer on USB`);
  usbPhones = found;
});
// ---------- offload: copy new photos, videos and ride files off an Android phone on USB ----------
// Copy only. Nothing on the phone is changed or deleted, nothing on this Mac is overwritten, and a file counts as done only once
// the copy is the same size as the original. What has been copied is remembered per phone in data/offload/, so a file is never
// fetched twice and an interrupted run carries on where it stopped.
const OFFLOAD_DIR = path.join(DATA, 'offload'), OFFLOAD_SPARE = 20 * 1073741824;   // always leave 20 GB free where the files land
let offloads = [];
const offloadDest = () => home(String(CFG.offloadTo || '').trim()) || path.join(FOOTAGE, '_PHONE OFFLOAD');
const adbRun = (args, timeout) => new Promise(res => execFile('adb', args, { timeout, maxBuffer: 64 << 20 }, (err, out, errout) => res({ ok: !err, out: String(out || ''), err: String(errout || '') })));
async function offloadPhone(p) {
  const job = { id: p.id, name: `Offload from ${p.name}`, state: 'looking', pct: 0, line: 'Looking for new files' };
  const show = (state, line, pct) => { Object.assign(job, { state, line }, pct == null ? {} : { pct }); if (!offloads.includes(job)) offloads = offloads.filter(j => j.id !== p.id).concat(job); };
  const hide = () => { offloads = offloads.filter(j => j !== job); };
  const dest = offloadDest(), drive = path.dirname(dest), mf = path.join(OFFLOAD_DIR, p.id + '.json');
  if (!(await exists(drive))) return show('waiting', `Waiting for ${path.basename(drive) || drive} to be plugged in`);
  let m = null;
  const save = async () => { await fs.promises.mkdir(OFFLOAD_DIR, { recursive: true }); await fs.promises.writeFile(mf, JSON.stringify(m)); };
  try { m = await readJSON(mf); } catch (e) { /* first time this phone is seen */ }
  if (!m || typeof m.done !== 'object') {
    m = { phone: p.name, since: Math.floor(Date.now() / 1000) - Math.max(0, Number(CFG.offloadSinceDays) || 0) * 86400, done: {} };
    await save(); log(`offload: first time for ${p.name}, taking files from the last ${Math.max(0, Number(CFG.offloadSinceDays) || 0)} day(s) on`);
  }
  const ls = await adbRun(['-s', p.serial, 'shell', `find ${phoneFolders(CFG.offloadFolders).map(shq).join(' ')} -type f -exec stat -c '%Y|%s|%n' {} + 2>/dev/null`], 180000);
  const todo = planOffload(parseListing(ls.out), m), total = todo.reduce((s, f) => s + f.size, 0);
  if (!todo.length) return hide();
  const df = /\n\S.*?\s+\d+\s+\d+\s+(\d+)\s+\d+%/.exec((await run('df', ['-k', drive])).out), free = df ? Number(df[1]) * 1024 : 0;
  if (free < total + OFFLOAD_SPARE) return show('waiting', `Not enough room: ${sizeText(total)} to copy, ${sizeText(free)} free`);
  log(`offload: ${p.name} has ${todo.length} new file(s), ${sizeText(total)}, going to ${dest}`);
  let doneBytes = 0, copied = 0, copiedBytes = 0, failed = 0, gone = false;
  for (let i = 0; i < todo.length && !gone; i++) {
    const f = todo[i], first = targetFor(dest, p.name, f);
    let target = first, here = await fs.promises.stat(target).catch(() => null), part = 0;
    for (let n = 2; here && here.size !== f.size; n++) { target = altName(first, n); here = await fs.promises.stat(target).catch(() => null); }   // a different file already has that name
    const tick = () => show('copying', `${i + 1} of ${todo.length} files · ${sizeText(doneBytes + part)} of ${sizeText(total)} · ${path.basename(target)}`, Math.min(99, Math.floor(100 * (doneBytes + part) / total)));
    tick();
    if (!here) {                                                              // not here yet: copy to a .part file, check the size, then give it its name
      const tmp = target + '.part';
      await fs.promises.mkdir(path.dirname(target), { recursive: true });
      const watch = setInterval(() => fs.promises.stat(tmp).then(s => { part = s.size; tick(); }, () => {}), 1000);
      const r = await adbRun(['-s', p.serial, 'pull', '-a', f.path, tmp], 120000 + Math.ceil(f.size / 500000) * 1000);
      clearInterval(watch); part = 0;
      const got = await fs.promises.stat(tmp).catch(() => null);
      if (!r.ok || !got || got.size !== f.size) {
        await fs.promises.unlink(tmp).catch(() => {});
        if (!(await adbRun(['-s', p.serial, 'get-state'], 5000)).ok) { gone = true; break; }
        failed++; doneBytes += f.size; log(`offload: could not copy ${path.posix.basename(f.path)} (${(r.err || 'size did not match').trim().split('\n').pop().slice(0, 120)}); it is tried again next time`);
        continue;
      }
      await fs.promises.rename(tmp, target); copied++; copiedBytes += f.size;
    }
    doneBytes += f.size; m.done[f.path] = signature(f);
    if (i % 5 === 4) await save();
  }
  await save();
  if (copied) alert('offload', `${p.name}: ${copied} file${copied === 1 ? '' : 's'} offloaded (${sizeText(copiedBytes)})`);
  if (gone) { log(`offload: ${p.name} was unplugged after ${copied} of ${todo.length} files; the rest follow next time`); show('waiting', `Unplugged after ${copied} of ${todo.length} files. The rest follow when the phone is back.`); }
  else { log(`offload: ${p.name} done, ${copied} copied (${sizeText(copiedBytes)})${failed ? `, ${failed} failed` : ''}, in ${dest}`); show('done', `${copied} file${copied === 1 ? '' : 's'} copied, ${sizeText(copiedBytes)}${failed ? ` · ${failed} could not be copied` : ''}`, 100); }
  setTimeout(hide, 600000);
}
// ---------- upload: a phone app (or any screen that is let in) sends rides and media to this Mac over the network ----------
// With "uploads" on, a sender first asks which of its files are wanted (POST /api/upload/check), then sends each one as the plain
// body of PUT /api/upload?name=...&mtime=... . Files land in the same place as the USB offload: rides in <phone>/Rides, everything
// else in <phone>/<date>. Only known media and ride file types are taken, a file is only stored and never opened or run, nothing
// is overwritten, and a file counts only when all of it arrived.
const UP_TYPES = /\.(jpe?g|png|heic|heif|dng|gif|webp|mp4|mov|m4v|3gp|gpx|fit|tcx|kml|json|csv|txt)$/i, UP_RIDE = /\.(gpx|fit|tcx|kml|json|csv)$/i;
const upMax = () => Math.max(1, Number(CFG.uploadMaxGB) || 8) * 1073741824;
const upDevice = (given, who) => cleanName(String(given || who || 'phone')).slice(0, 40);
const upTarget = (device, name, mtime, kind) => (kind === 'ride' || (kind !== 'media' && UP_RIDE.test(name))
  ? path.join(offloadDest(), cleanName(device), 'Rides', cleanName(name)) : targetFor(offloadDest(), device, { mtime, path: '/' + name }));
const upRefusal = (name, size) => (!CFG.uploads ? [403, 'uploads are switched off on this Deck'] : !name || !UP_TYPES.test(name) ? [415, 'that kind of file is not accepted']
  : !(size > 0) ? [411, 'the request has to say how long the file is'] : size > upMax() ? [413, 'the file is larger than this Deck accepts (' + sizeText(upMax()) + ')'] : null);
async function upRoom(size) {                                              // is the drive there, and is there room with 20 GB to spare
  const drive = path.dirname(offloadDest());
  if (!(await exists(drive))) return 'the drive the files go to is not plugged in';
  const df = /\n\S.*?\s+\d+\s+\d+\s+(\d+)\s+\d+%/.exec((await run('df', ['-k', drive])).out);
  return df && Number(df[1]) * 1024 >= size + OFFLOAD_SPARE ? null : 'not enough room on the drive the files go to';
}
async function uploadCheck(o, who) {                                        // which of these files does the Deck still want
  const device = upDevice(o && o.device, who), need = [], have = [], refused = [];
  for (const f of (Array.isArray(o && o.files) ? o.files : []).slice(0, 5000)) {
    const name = cleanName(f && f.name), size = Number(f && f.size), no = upRefusal(name, size);
    if (no) { refused.push({ name: String(f && f.name), why: no[1] }); continue; }
    const st = await fs.promises.stat(upTarget(device, name, Math.round(Number(f.mtime)) || 0, f.kind)).catch(() => null);
    (st && st.size === size ? have : need).push(String(f.name));
  }
  return { ok: true, need, have, refused };
}
let upTally = null;                                                         // files that have just arrived, summed up into one alert when the sender goes quiet
function upDone(device, size) {
  if (!upTally || upTally.device !== device) upTally = { device, n: 0, bytes: 0, t: null };
  const t = upTally; t.n++; t.bytes += size; clearTimeout(t.t);
  t.t = setTimeout(() => { if (upTally === t) upTally = null; alert('offload', device + ': ' + t.n + ' file' + (t.n === 1 ? '' : 's') + ' received over the network (' + sizeText(t.bytes) + ')'); }, 6000);
}
async function receiveUpload(req, reply, who) {
  const q = new URL(req.url, 'http://x').searchParams, name = cleanName(q.get('name') || ''), size = Number(req.headers['content-length']);
  const mtime = Math.round(Number(q.get('mtime'))) || Math.floor(Date.now() / 1000), device = upDevice(q.get('device'), who);
  const refuse = (code, why) => { reply(code, { ok: false, why }); req.resume(); setTimeout(() => req.destroy(), 3000); };   // do not sit through a body that is not wanted
  const no = upRefusal(name, size);
  if (no) return refuse(no[0], no[1]);
  const full = await upRoom(size);
  if (full) return refuse(507, full);
  const first = upTarget(device, name, mtime, q.get('kind'));
  let target = first, here = await fs.promises.stat(target).catch(() => null);
  if (here && here.size === size) { reply(200, { ok: true, saved: false, why: 'already here' }); return req.resume(); }
  for (let n = 2; here; n++) { target = altName(first, n); here = await fs.promises.stat(target).catch(() => null); }   // a different file has that name: keep both
  await fs.promises.mkdir(path.dirname(target), { recursive: true });
  const tmp = target + '.' + randomBytes(4).toString('hex') + '.part', out = fs.createWriteStream(tmp);
  const i = Math.round(Number(q.get('i'))) || 0, n = Math.round(Number(q.get('n'))) || 0;
  const job = { id: 'up:' + device, name: 'Upload from ' + device, state: 'copying', pct: 0, line: '' };
  offloads = offloads.filter(j => j.id !== job.id).concat(job);
  let got = 0, shown = 0, over = false;
  const fail = why => { if (over) return; over = true; out.destroy(); fs.promises.unlink(tmp).catch(() => {}); Object.assign(job, { state: 'waiting', line: name + ': ' + why }); setTimeout(() => { offloads = offloads.filter(j => j !== job); }, 60000); log('upload: ' + device + ' ' + name + ': ' + why); };
  req.on('data', c => {
    got += c.length;
    if (got > size) { fail('more arrived than was announced'); return req.destroy(); }
    if (Date.now() - shown > 400) { shown = Date.now(); Object.assign(job, { pct: Math.min(99, Math.floor(100 * got / size)), line: (n ? i + ' of ' + n + ' files · ' : '') + sizeText(got) + ' of ' + sizeText(size) + ' · ' + name }); }
  });
  req.on('aborted', () => fail('the connection dropped part-way'));
  req.on('error', () => fail('the connection dropped part-way'));
  out.on('error', e => { fail('could not be written (' + (e.code || e.message) + ')'); if (!res_sent(reply)) reply(500, { ok: false, why: 'the Deck could not write the file' }); });
  out.on('finish', async () => {
    if (over) return;
    if (got !== size) { fail('arrived incomplete'); return reply(400, { ok: false, why: 'the file arrived incomplete' }); }
    over = true;
    await fs.promises.utimes(tmp, mtime, mtime).catch(() => {});
    await fs.promises.rename(tmp, target);
    Object.assign(job, { state: 'done', pct: 100, line: (n ? i + ' of ' + n + ' files · ' : '') + path.basename(target) + ' received' });
    setTimeout(() => { offloads = offloads.filter(j => j !== job); }, 600000);
    log('upload: ' + device + ' sent ' + path.basename(target) + ' (' + sizeText(size) + ')'); upDone(device, size);
    reply(200, { ok: true, saved: true, name: path.basename(target) });
  });
  req.pipe(out);
}
const res_sent = reply => reply.sent && reply.sent();
// what a screen is told about other screens: a paired screen learns only its own name, the Deck itself gets the code and the list
function remoteInfo(screen) {
  if (screen) return { me: screen.name };
  if (pairing && Date.now() > pairing.exp) pairing = null;
  return { on: !!CFG.remote, url: CFG.remote ? remoteUrl() : null, alt: CFG.remote && lanAlt && !String(CFG.remoteName || '').trim() ? `http://${lanAlt}:${PORT}` : null,
    code: pairing ? pairing.code : null,
    left: pairing ? Math.max(0, Math.ceil((pairing.exp - Date.now()) / 1000)) : 0, qr: pairing ? pairing.qr : null,
    usb: usbPhones.map(p => ({ id: p.id, name: p.name })),
    screens: screens.map(s => ({ id: s.id, name: s.name, seen: ago(s.seen), fresh: Date.now() - s.added < 90000 })) };
}

// ---------- the one object the screen draws from ----------
function snapshot(screen) {
  const now = new Date(), b = board();
  const owed = (b.owed || []).map(o => {
    const p = projectFor(o.title), c = p && cuts[p.id];
    return { ...o, cut: c && c.cut ? { name: short(c.cut.name, 46), age: ago(c.cut.mtime) } : null, folder: !!c };
  });
  return {
    v: VERSION, brand: CFG.brand, ambientAfter: CFG.ambientAfterSec,
    clock: now.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' }),
    date: now.toLocaleDateString('en-US', { weekday: 'short', day: 'numeric', month: 'short' }),
    board: b.owed ? { ...b, owed } : b,
    studio: { up: studio.up, renders: studio.renders }, encodes,
    claude: claude.map(s => ({ key: s.key, title: s.title, kind: s.kind, working: s.working, ago: ago(s.last), done: s.done, total: s.total, active: s.active, next: s.next, tasks: s.tasks })),
    yt: channel(),
    alerts: alerts.slice(0, 12).map(a => ({ id: a.id, kind: a.kind, text: a.text, ago: ago(a.at), celebrate: !!a.celebrate, big: a.big || '' })),
    media: { pics: CFG.standbyPictures && mediaOn() ? pics.length : 0, clips: CFG.standbyClips && mediaOn() ? clips.length : 0,
      picSec: Math.max(15, Number(CFG.standbyPictureSec) || 40), clipMin: Math.max(1, Number(CFG.standbyClipEveryMin) || 5) },
    vol, disks, load: Math.min(100, Math.round(20 * os.loadavg()[0] / os.cpus().length) * 5), remote: remoteInfo(screen), drive: driveInfo(), uploads: !!CFG.uploads, offload: offloads.map(j => ({ name: j.name, state: j.state, pct: j.pct, line: j.line })),
    settings, apps: CFG.apps.map(a => ({ key: a.key, label: a.label, sub: a.sub, icon: a.icon || KEYICON[a.key] || (a.url ? 'globe' : a.path ? 'folder' : 'app') })),
  };
}
// Launch icons: an app entry may name one with "icon" (film, chart, play, wheels, cut, drive, done, folder, globe, spark, app)
const KEYICON = { studio: 'film', ytstudio: 'chart', channel: 'play', resolve: 'wheels', capcut: 'cut', drive: 'drive', finished: 'done', site: 'globe', claude: 'spark' };
const LS = String.fromCharCode(0x2028), PS = String.fromCharCode(0x2029);
const js = o => JSON.stringify(o).split(LS).join('\\u2028').split(PS).join('\\u2029');   // safe inside a script on an old browser

// ---------- the Car Thing link ----------
const link = { status: NO_DEVICE ? 'off (--no-device)' : 'searching' };
async function deviceLoop() {
  for (;;) {
    try { await connectOnce(); } catch (e) { link.status = 'error: ' + (e.message || e); }
    deviceSerial = null;
    await sleep(3000);
  }
}
async function connectOnce() {
  const d = await run('adb', ['devices', '-l']);
  if (!d.ok) { link.status = 'adb not available'; return; }
  const line = d.out.split('\n').find(l => /\sdevice\s/.test(l) && CAR_THING.test(l));
  if (!line) { link.status = 'no Car Thing on USB'; return; }
  const serial = line.split(/\s+/)[0];
  const f = await run('adb', ['-s', serial, 'forward', `tcp:${DEVTOOLS_PORT}`, 'tcp:2222']);
  if (!f.ok) throw new Error('port forward failed');
  const pages = await (await fetch(`http://127.0.0.1:${DEVTOOLS_PORT}/json`, { signal: AbortSignal.timeout(4000) })).json();
  const page = pages.find(p => p.type === 'page');
  if (!page) throw new Error('kiosk browser has no page yet');
  deviceSerial = serial;
  await session(page.webSocketDebuggerUrl, serial);
}
// Brightness, but never during boot: stopping the Car Thing's backlight service while it is still starting leaves the screen dark.
async function setBrightness(serial) {
  if (!CFG.fullBrightness && !settings.night) return true;
  const level = settings.night ? Math.max(5, Math.min(255, CFG.nightBrightness)) : 255;
  const r = await run('adb', ['-s', serial, 'shell',
    `u=$(cut -d. -f1 /proc/uptime); if [ "$u" -gt 75 ]; then supervisorctl stop backlight >/dev/null 2>&1; echo ${level} > /sys/class/backlight/aml-bl/brightness; echo applied; fi`]);
  return /applied/.test(r.out);
}
// Device health: every 20 s read backlight, panel and display-driver state so a black screen leaves evidence in the log.
let lastHealth = '', lastHealthLog = 0, lastUptime = 0, lastFb = -1;
async function health(serial) {
  const want = settings.night ? Math.max(5, Math.min(255, CFG.nightBrightness)) : 255;
  const r = await run('adb', ['-s', serial, 'shell',
    'echo "up=$(cut -d. -f1 /proc/uptime) bl=$(cat /sys/class/backlight/aml-bl/brightness) pw=$(cat /sys/class/backlight/aml-bl/bl_power) lcd=$(cat /sys/class/lcd/enable | tr -dc 0-9) mute=$(cat /sys/class/lcd/mute | tr -dc 0-9) fb=$(dmesg | grep -c "fb: unsupport") blsvc=$(supervisorctl status backlight | tr -s " " | cut -d" " -f2) chrome=$(pidof chrome | wc -w) mem=$(grep MemAvailable /proc/meminfo | tr -dc 0-9) free=$(grep MemFree /proc/meminfo | tr -dc 0-9)"']);
  if (!r.ok) return;
  const h = {}; r.out.trim().split(/\s+/).forEach(kv => { const i = kv.indexOf('='); if (i > 0) h[kv.slice(0, i)] = kv.slice(i + 1); });
  const up = Number(h.up) || 0, fb = Number(h.fb) || 0;
  if (lastUptime && up < lastUptime) log(`health: the Car Thing rebooted (uptime was ${lastUptime}s, now ${up}s)`);
  lastUptime = up;
  const key = [h.pw, h.lcd, h.mute, h.blsvc, h.chrome].join(' ');
  // the backlight level moves with the room light and the display-error count rises whenever something animates: neither is news
  if (key !== lastHealth || Date.now() - lastHealthLog > 600000) {
    log(`health: ${r.out.trim()}${lastFb >= 0 && fb > lastFb ? ` (display errors +${fb - lastFb} since the last line)` : ''}`);
    lastHealth = key; lastHealthLog = Date.now(); lastFb = fb;
  }
  if (lastFb < 0) lastFb = fb;
  const free = Number(h.free) || 0;
  if (free && free < 40000 && mediaOn()) {                      // the Car Thing is short of memory: no pictures or clips for half an hour
    log(`health: only ${Math.round(free / 1024)} MB free on the Car Thing, pausing standby pictures and clips`);
    mediaHold = Date.now() + 1800000; mediaJob++; tidyDevice();
  }
  if (!CFG.fullBrightness && !settings.night && up > 75 && h.blsvc !== 'RUNNING') {
    log('health: handing the backlight back to the Car Thing (stock auto-brightness)');
    await run('adb', ['-s', serial, 'shell', 'supervisorctl start backlight >/dev/null 2>&1']);
  }
  if ((CFG.fullBrightness || settings.night) && up > 75 && (Number(h.bl) !== want || h.pw !== '0' || h.blsvc === 'RUNNING')) {
    log(`health: backlight was ${h.bl} (power ${h.pw}, service ${h.blsvc}), setting it back to ${want}`);
    await run('adb', ['-s', serial, 'shell', `supervisorctl stop backlight >/dev/null 2>&1; echo 0 > /sys/class/backlight/aml-bl/bl_power; echo ${want} > /sys/class/backlight/aml-bl/brightness`]);
  }
}
function session(url, serial) {
  return new Promise(resolve => {
    const ws = new WebSocket(url);
    const pending = new Map();
    let id = 0, alive = true, timer = null, busy = false, lastSent = '', lastPush = 0, bright = false, brightAt = 0, healthAt = 0;
    const cmd = (method, params = {}) => new Promise(res => {
      const n = ++id; pending.set(n, res);
      try { ws.send(JSON.stringify({ id: n, method, params })); } catch (e) { pending.delete(n); return res({ error: { message: 'send failed' } }); }
      setTimeout(() => { if (pending.delete(n)) res({ error: { message: 'timeout' } }); }, 6000);
    });
    const inject = async () => {
      await cmd('Page.navigate', { url: 'about:blank' });
      await sleep(500);
      const html = await fs.promises.readFile(path.join(HERE, 'ui.html'), 'utf8');
      await cmd('Runtime.evaluate', { expression: `document.open();document.write(${js(html)});document.close();1` });
      lastSent = ''; log('dashboard written to the Car Thing');
    };
    const tick = async () => {
      if (busy || !alive) return;
      busy = true;
      try {
        if (!bright && Date.now() - brightAt > 15000) { brightAt = Date.now(); bright = await setBrightness(serial); }
        if (Date.now() - healthAt > 20000) { healthAt = Date.now(); await health(serial); }
        if (reinject) { reinject = false; await inject(); }
        const s = js(snapshot());
        if (s !== lastSent || Date.now() - lastPush > 3000) {
          const r = await cmd('Runtime.evaluate', { expression: `(window.__ct?(__ct.push(${s}),'ok'):'gone')`, returnByValue: true });
          const v = r.result && r.result.result && r.result.result.value;
          if (v === 'ok') { lastSent = s; lastPush = Date.now(); } else if (v === 'gone') await inject();
        }
      } finally { busy = false; }
    };
    const end = () => { if (!alive) return; alive = false; clearInterval(timer); if (device === dev) device = null; mediaJob++; link.status = 'link lost, retrying'; log('Car Thing link lost'); resolve(); };
    const dev = { eval: expression => cmd('Runtime.evaluate', { expression, returnByValue: true }), gc: () => cmd('HeapProfiler.collectGarbage') };
    ws.onopen = async () => {
      link.status = 'connected'; log('Car Thing connected'); device = dev;
      await cmd('Runtime.enable'); await cmd('Page.enable');
      await inject();
      timer = setInterval(tick, 400);
    };
    ws.onmessage = ev => {
      let m; try { m = JSON.parse(ev.data); } catch (e) { return; }
      if (m.id && pending.has(m.id)) { const r = pending.get(m.id); pending.delete(m.id); return r(m); }
      if (m.method === 'Runtime.consoleAPICalled') {
        const v = m.params.args && m.params.args[0] && m.params.args[0].value;
        if (typeof v === 'string' && v.startsWith('__CT__')) { try { act(JSON.parse(v.slice(6)), true); } catch (e) { /* not ours */ } }
      }
    };
    ws.onclose = end; ws.onerror = end;
  });
}

// ---------- local preview + the same API for a normal browser ----------
// Who may ask: this Mac always (the preview), and with "remote" on, a screen on the same private network that has been paired.
// For trying the pairing flow on one computer: started with DECK_TEST_REMOTE=1, http://localhost:PORT is treated as a screen
// elsewhere on the network (it has to be paired), while http://127.0.0.1:PORT stays the trusted preview.
const TEST_REMOTE = process.env.DECK_TEST_REMOTE === '1';
const LOOPBACK = /^(127\.0\.0\.1|::1|::ffff:127\.0\.0\.1)$/, HOST_LOCAL = /^(127\.0\.0\.1|localhost|\[::1\])(:\d+)?$/i, HOST_LAN = /^(\d{1,3}(\.\d{1,3}){3}|[a-z0-9-]+\.local)(:\d+)?$/i;
const escHtml = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
// Over HTTP the page gets a head that suits phones and tablets (fit to the screen, home-screen icon and name). The copy written
// into the Car Thing keeps the plain head that is in ui.html.
const DEVICE_VIEWPORT = '<meta name="viewport" content="width=800,height=480,initial-scale=1,maximum-scale=1,user-scalable=no">';
const WEB_HEAD = '<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">\n' +
  '<meta name="theme-color" content="#02060a"><meta name="apple-mobile-web-app-capable" content="yes"><meta name="mobile-web-app-capable" content="yes">\n' +
  '<meta name="apple-mobile-web-app-status-bar-style" content="black"><meta name="apple-mobile-web-app-title" content="Deck">\n' +
  '<link rel="apple-touch-icon" href="/icon-180.png"><link rel="icon" href="/icon-192.png"><link rel="manifest" href="/manifest.webmanifest">';
// Each page served over HTTP is told which build of ui.html it is (the file's modified time) and says so whenever it asks for
// state. A page from an older build is answered with "load yourself again", so a phone left showing the Deck for days still
// picks up a new version without anyone touching it.
let uiStamp = '0';
const readUiStamp = () => fs.promises.stat(path.join(HERE, 'ui.html')).then(s => { uiStamp = String(Math.round(s.mtimeMs)); }, () => {});
const webPage = h => h.replace(DEVICE_VIEWPORT, () => WEB_HEAD).replace(/<title>[^<]*<\/title>/, () => `<title>${escHtml(CFG.brand)} Deck</title>`)
  .replace("var UI = '';", () => `var UI = '${uiStamp}';`);
// The Ride phone app does not pair with a code: it sends the phoneToken from config.json in an x-deck-token header instead.
const APP_SCREEN = { id: 'app', name: 'Ride app' };
const tokenOk = req => {
  const want = Buffer.from(String(CFG.phoneToken || '')), got = Buffer.from(String(req.headers['x-deck-token'] || ''));
  return want.length >= 8 && got.length === want.length && timingSafeEqual(got, want);
};
const readBody = (req, max, done) => { let b = ''; req.on('data', c => { b += c; if (b.length > max) req.destroy(); }); req.on('end', () => done(b)); };
http.createServer((req, res) => {
  const send = (code, type, body, extra) => { res.writeHead(code, { 'Content-Type': type, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', ...(extra || {}) }); res.end(body); };
  const json = (code, o, extra) => send(code, 'application/json', JSON.stringify(o), extra);
  const file = (name, type, extra, fill) => fs.promises.readFile(path.join(HERE, name)).then(b => send(200, type, fill ? fill(String(b)) : b, extra), () => send(500, 'text/plain', name + ' missing'));
  const from = String(req.socket.remoteAddress || ''), host = String(req.headers.host || ''), url = String(req.url || '').split('?')[0];
  const loop = LOOPBACK.test(from), asScreen = TEST_REMOTE && loop && /^localhost(:\d+)?$/i.test(host), local = loop && !asScreen;
  // A request from this Mac has to be addressed to this Mac by name, so a web page elsewhere cannot point its own address here and
  // read the Deck through the browser. A request from the network has to come from a private address and name this Mac directly.
  const named = String(CFG.remoteName || '').trim().toLowerCase();
  const allowed = local ? HOST_LOCAL.test(host) : asScreen ? !!CFG.remote
    : CFG.remote && privateNet(from.replace(/^::ffff:/, '')) && (HOST_LAN.test(host) || (named && host.toLowerCase().split(':')[0] === named));
  if (!allowed) return send(403, 'text/plain', 'not allowed');
  // anything that changes something has to come from the Deck's own page, not from another site open in the same browser
  if (req.method !== 'GET' && req.headers.origin && req.headers.origin !== 'http://' + host) return send(403, 'text/plain', 'not allowed');
  if (req.method === 'GET' && /^\/icon-(180|192|512)\.png$/.test(url)) return fs.promises.readFile(path.join(HERE, url.slice(1))).then(b => send(200, 'image/png', b, { 'Cache-Control': 'max-age=86400' }), () => send(404, 'text/plain', 'not found'));
  if (req.method === 'GET' && url === '/manifest.webmanifest') return json(200, { name: `${CFG.brand} Deck`, short_name: 'Deck', start_url: '/', display: 'fullscreen', orientation: 'landscape',
    background_color: '#02060a', theme_color: '#02060a', icons: [192, 512].map(n => ({ src: `/icon-${n}.png`, sizes: `${n}x${n}`, type: 'image/png' })) });
  const screen = local ? null : (screenFor(req) || (tokenOk(req) ? APP_SCREEN : null));
  if (!local && !screen) {                                                    // a screen that has not been paired gets the pairing page and nothing else
    if (req.method === 'POST' && url === '/api/pair') return readBody(req, 512, b => {
      let o = null; try { o = JSON.parse(b); } catch (e) { /* counted as a wrong code */ }
      const r = tryPair(o, req);
      // a browser keeps its key in a cookie; an app ("app": true) is handed the key and sends it back as x-deck-token
      return r.ok ? json(200, o && o.app ? { ok: true, name: r.name, key: r.key } : { ok: true, name: r.name }, { 'Set-Cookie': cookieFor(r.key) }) : json(200, { ok: false, why: r.why });
    });
    if (req.method === 'GET' && (url === '/' || url === '/index.html')) return file('pair.html', 'text/html; charset=utf-8', null, h => h.split('__BRAND__').join(escHtml(CFG.brand)));
    return json(401, { error: 'not paired' });
  }
  if (req.method === 'GET' && (url === '/' || url === '/index.html')) return readUiStamp().then(() => file('ui.html', 'text/html; charset=utf-8', screen && req.deckKey ? { 'Set-Cookie': cookieFor(req.deckKey) } : null, webPage));
  if (req.method === 'GET' && url === '/api/state') {
    // "ui" is the page's build. A different one, or none at all from a page in a browser (a page from before pages said so),
    // gets the same answer an unpaired screen gets, which makes the page load itself again. Other programs asking are not affected.
    const ui = new URL(req.url, 'http://x').searchParams.get('ui');
    if (ui == null ? req.headers['sec-fetch-site'] === 'same-origin' : (ui !== '' && ui !== uiStamp)) return json(401, { error: 'reload' });
    return json(200, { ...snapshot(screen), link: link.status });
  }
  if (req.method === 'POST' && url === '/api/action') return readBody(req, 4096, b => { try { act(JSON.parse(b), local); } catch (e) { /* ignore */ } json(200, { ok: true }); });
  if (req.method === 'POST' && url === '/api/pair') return json(200, { ok: true, name: screen ? screen.name : 'This Mac' });   // already let in
  const who = screen ? screen.name : 'this Mac';
  if (req.method === 'POST' && url === '/api/upload/check') return readBody(req, 1048576, b => { let o = null; try { o = JSON.parse(b); } catch (e) { /* treated as an empty list */ }
    uploadCheck(o, who).then(r => json(200, r), e => json(500, { ok: false, why: String(e.message || e) })); });
  if ((req.method === 'PUT' || req.method === 'POST') && url === '/api/upload') {
    const reply = (code, o) => { if (!res.headersSent) json(code, o); }; reply.sent = () => res.headersSent;
    return receiveUpload(req, reply, who).catch(e => { log('upload:', e.message || e); reply(500, { ok: false, why: 'the Deck could not take the file' }); });
  }
  if (req.method === 'GET' && url === '/api/media') {                         // the same pictures and clips for a normal browser
    const kind = new URL(req.url, 'http://x').searchParams.get('kind');
    if (kind === 'clip' && clips.length && CFG.standbyClips) {
      const c = clips[Math.floor(Math.random() * clips.length)];
      return send(200, 'application/json', JSON.stringify({ kind: 'clip', id: c.id, cap: c.label, fps: c.fps, n: c.n, frames: Array.from({ length: c.n }, (_, i) => `/media/clips/${c.id}/${String(i + 1).padStart(4, '0')}.jpg`) }));
    }
    const p = CFG.standbyPictures ? nextPic() : null;
    return send(200, 'application/json', JSON.stringify(p ? { kind: 'pic', id: p.file, cap: p.cap, sub: picSub(p), src: '/media/pics/' + p.file } : { kind: 'none' }));
  }
  const mm = req.method === 'GET' && /^\/media\/(pics\/[0-9a-f]{16}\.jpg|clips\/[0-9a-z]+\/\d{4}\.jpg)$/.exec(url);
  if (mm) return fs.promises.readFile(path.join(MEDIA, mm[1])).then(b => send(200, 'image/jpeg', b), () => send(404, 'text/plain', 'not found'));
  if (local && req.method === 'POST' && url === '/api/test-alert') { alert('claude', 'Test alert from the Mac'); return json(200, { ok: true }); }
  send(404, 'text/plain', 'not found');
}).listen(PORT, CFG.remote ? '0.0.0.0' : '127.0.0.1', () => log(`${CFG.brand} Deck ${VERSION}: preview at http://127.0.0.1:${PORT}/` +
  (CFG.remote ? `, and open to paired screens on this network (${screens.length} paired)` : '')));

const every = (fn, ms) => { fn(); setInterval(fn, ms); };
every(readUiStamp, 2000);
if (CFG.remote) every(refreshLan, 10000);
// Announces the Deck on the network (Bonjour), so the Ride app can find it without anyone typing an address.
if (CFG.remote && !process.env.DECK_PORT) (async () => {
  await run('pkill', ['-f', '^dns-sd -R .* _maxdeck\\._tcp local']);                  // one left behind by an earlier run
  let ad = null;
  const announce = () => { ad = spawn('dns-sd', ['-R', `${CFG.brand} Deck`, '_maxdeck._tcp', 'local', String(PORT), 'v=' + VERSION], { stdio: 'ignore' }); ad.on('error', () => {}); ad.on('exit', () => { ad = null; setTimeout(announce, 30000); }); };
  announce();
  const stop = () => { if (ad) ad.kill(); process.exit(0); };
  process.on('SIGTERM', stop); process.on('SIGINT', stop);
})();
if (CFG.usbScreens && !NO_DEVICE) every(refreshUsb, 4000);
every(refreshBoard, 2000);
every(refreshStudio, 5000);
every(refreshEncodes, 5000);
every(refreshClaude, 4000);
every(refreshYT, 300000);
every(readVol, 3000);
every(refreshDisks, 60000);
setTimeout(() => every(refreshCuts, 60000), 6000);   // after the first project list has arrived
(async () => {
  FFMPEG = await findTool('ffmpeg'); FFPROBE = await findTool('ffprobe');
  if (!FFMPEG || !FFPROBE) { FFMPEG = null; return log('standby pictures and clips need ffmpeg, which was not found (brew install ffmpeg)'); }
  await fs.promises.mkdir(PICS, { recursive: true }); await fs.promises.mkdir(CLIPS, { recursive: true });
  setTimeout(() => every(refreshPics, 6 * 3600000), 20000);                  // after the first YouTube read
  setTimeout(() => every(refreshClips, 90000), 45000);
})();
if (!NO_DEVICE) deviceLoop();
