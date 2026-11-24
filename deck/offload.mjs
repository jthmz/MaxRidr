// The parts of the phone offload that need no phone: reading a file listing, deciding what is new, and choosing where each file goes.
// The server (server.mjs) does the talking to the phone and the copying.
import path from 'node:path';

// The listing comes from the phone as lines of "modified-seconds|size-bytes|/sdcard/path". Hidden files and folders are skipped
// (thumbnails, the gallery's trash, files a camera app is still writing), and so are empty files.
export function parseListing(text) {
  const out = [];
  for (const line of String(text).split('\n')) {
    const m = /^(\d+)\|(\d+)\|(\/.+?)\r?$/.exec(line);
    if (!m) continue;
    const file = { mtime: Number(m[1]), size: Number(m[2]), path: m[3] };
    if (!file.size || /\/\./.test(file.path)) continue;
    out.push(file);
  }
  return out;
}

// What still has to be copied: files changed since the starting point that are not already recorded as copied in this exact
// size and date. Oldest first, so an interrupted run leaves no gaps behind it.
export const signature = f => `${f.size}:${f.mtime}`;
export function planOffload(files, manifest) {
  const done = (manifest && manifest.done) || {}, since = (manifest && manifest.since) || 0;
  return files.filter(f => f.mtime >= since && done[f.path] !== signature(f)).sort((a, b) => a.mtime - b.mtime || a.path.localeCompare(b.path));
}

// Where a file lands: <destination>/<phone>/<YYYY-MM-DD it was made>/<its own name>.
export const cleanName = s => String(s).replace(/[\/\\:*?"<>|\x00-\x1f]/g, '_').replace(/\s+/g, ' ').trim().replace(/^\.+/, '') || 'phone';
export function targetFor(dest, phoneName, f) {
  const d = new Date(f.mtime * 1000), day = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  return path.join(dest, cleanName(phoneName), day, cleanName(path.posix.basename(f.path)));
}
// A free name next to one that is taken by a different file: "clip.mp4" -> "clip (2).mp4"
export function altName(target, n) { const e = path.extname(target); return `${target.slice(0, target.length - e.length)} (${n})${e}`; }

// Folder names from config, made safe to hand to the phone: under /sdcard only, no "..".
export function phoneFolders(list) {
  const src = Array.isArray(list) && list.length ? list : ['DCIM'];
  return src.map(f => String(f).split('/').filter(p => p && p !== '.' && p !== '..').join('/')).filter(Boolean).map(f => '/sdcard/' + f);
}
export const shq = s => "'" + String(s).replace(/'/g, "'\\''") + "'";
export const sizeText = b => (b >= 1073741824 ? (b / 1073741824).toFixed(1) + ' GB' : b >= 1048576 ? Math.round(b / 1048576) + ' MB' : Math.max(1, Math.round(b / 1024)) + ' KB');
