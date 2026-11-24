// The Deck's small decisions, kept apart from server.mjs so they can be checked without a Mac, a Car Thing or the network:
// which ask on the board an answer belongs to, whether a video is taking off, whether it is night by the schedule, how the
// last ride reads, and what a music player said. server.mjs does the reading and writing around them.
//   node --test deck/rules.test.mjs
import { createHash } from 'node:crypto';

// ---------- answers to asks on the board ----------
// An ask is an item in the board's blocked_on_joe list, or a video's next step. An answer is kept with the ask's own words,
// so it stops applying by itself the moment the board's keeper rewrites or removes the ask.
export const ANSWERS = ['ok', 'done', 'not yet'];
export const askKey = (about, id, text) => createHash('sha1').update([about, id, text].join('\n')).digest('hex').slice(0, 12);
const ABOUT = 'Answers given on the Deck to asks on the board (state.json in this folder). The Deck writes this file and never ' +
  'changes the board. "ask" is the ask word for word as the board had it: an item in blocked_on_joe, or the "next" step of the ' +
  'video named in "video". "answer" is ok, done or not yet. An answer applies only while the board still has that ask, so once ' +
  'an ask is dealt with, take it off the board.';
// what an answers file holds, newest first, with anything malformed left out
export function readAnswers(doc) {
  const list = doc && Array.isArray(doc.answers) ? doc.answers : [];
  return list.filter(a => a && ANSWERS.includes(a.answer) && typeof a.ask === 'string' && (a.about === 'blocked_on_joe' || a.about === 'next'))
    .slice(0, 200).map(a => ({ at: String(a.at || ''), answer: a.answer, about: a.about, ...(a.about === 'next' ? { id: String(a.id || ''), video: String(a.video || '') } : {}), ask: a.ask,
      key: askKey(a.about, a.about === 'next' ? String(a.id || '') : '', a.ask) }));
}
// the list after one ask is answered; any other answer (such as '') takes the earlier one back
export function putAnswer(list, key, ask, answer, now = new Date()) {
  const rest = list.filter(a => a.key !== key);
  if (!ANSWERS.includes(answer)) return rest;
  return [{ at: now.toISOString(), answer, about: ask.about, ...(ask.about === 'next' ? { id: ask.id, video: ask.title } : {}), ask: ask.text, key }, ...rest].slice(0, 200);
}
export const answersDoc = (list, now = new Date()) => ({ about: ABOUT, updated: now.toISOString(), answers: list.map(({ key, ...a }) => a) });

// ---------- a video taking off ----------
// True when the views a video gained per hour over the last few hours are well above what it gained per hour over the day
// before that. `history` is the list of snapshots ({t, v: {id: views}}), oldest first. A video too new to have a day of history
// says nothing: every upload is busy on its first day.
export function takeoff(history, id, views, now, opt = {}) {
  const recentH = opt.recentHours || 3, baseH = opt.baseHours || 24, times = Number(opt.times) > 1 ? Number(opt.times) : 3, min = Number(opt.minPerHour) >= 0 ? Number(opt.minPerHour) : 30;
  const oldestIn = h => history.find(x => now - x.t <= h * 3600000 && x.v && x.v[id] != null);
  const a = oldestIn(recentH), b = oldestIn(recentH + baseH);
  if (!a || !b) return null;
  const ah = (now - a.t) / 3600000, bh = (a.t - b.t) / 3600000;
  if (ah < recentH * 0.6 || bh < baseH * 0.5) return null;                 // too little history on either side to say
  const rate = (views - a.v[id]) / ah, usual = (a.v[id] - b.v[id]) / bh;
  if (rate < min || rate < times * Math.max(usual, 1)) return null;
  return { perHr: Math.round(rate), usual: Math.round(usual), times: usual >= 1 ? Math.round(10 * rate / usual) / 10 : null };
}

// ---------- night by the clock ----------
// "22:00" or "7" as minutes past midnight; null when it is not a time of day
export const clockMin = s => { const m = /^\s*(\d{1,2})(?::(\d{2}))?\s*$/.exec(String(s == null ? '' : s)); return m && +m[1] < 24 && +(m[2] || 0) < 60 ? +m[1] * 60 + +(m[2] || 0) : null; };
// is `date` inside the night that runs from one time to the other (usually across midnight); null when there is no schedule
export function isNight(from, to, date) {
  const a = clockMin(from), b = clockMin(to);
  if (a == null || b == null || a === b) return null;
  const t = date.getHours() * 60 + date.getMinutes();
  return a < b ? t >= a && t < b : t >= a || t < b;
}
export const clockText = s => { const m = clockMin(s); if (m == null) return ''; const h = Math.floor(m / 60), mm = m % 60; return (h % 12 || 12) + (mm ? ':' + String(mm).padStart(2, '0') : '') + (h < 12 ? ' AM' : ' PM'); };

// ---------- the last ride ----------
// A ride's numbers as the Ride app sends them beside its track, turned into what the screen shows. null when it is not one.
export function rideOf(s, fallbackUnits) {
  if (!s || typeof s !== 'object' || !(Number(s.distanceM) > 0) || !(Number(s.start) > 0)) return null;
  const n = k => (Number.isFinite(Number(s[k])) ? Number(s[k]) : 0), km = (s.units === 'km' || s.units === 'mi' ? s.units : fallbackUnits) === 'km';
  const dist = n('distanceM') / (km ? 1000 : 1609.344), bat = s.battery == null ? NaN : Number(s.battery);
  return { start: n('start'), end: n('start') + n('elapsedMs'), dist: dist < 100 ? dist.toFixed(1) : String(Math.round(dist)), unit: km ? 'km' : 'mi',
    top: Math.round(n('topMps') * (km ? 3.6 : 2.23694)), speed: km ? 'km/h' : 'mph', left: Math.max(0, Math.round(n('leanLeft'))), right: Math.max(0, Math.round(n('leanRight'))),
    battery: Number.isFinite(bat) ? Math.max(0, Math.min(100, Math.round(bat))) : null };
}
export const rideText = r => `${r.dist} ${r.unit} · top ${r.top} ${r.speed}` + (r.left || r.right ? ` · lean ${r.left}° left, ${r.right}° right` : '') + (r.battery == null ? '' : ` · battery ${r.battery}%`);

// ---------- music ----------
// Asked only of a player that is already open (asking one that is not would start it). Spotify and Music both understand this.
export const PLAYERS = ['Spotify', 'Music'];
export const playerScript = app => [`tell application "${app}"`, 'try', 'if player state is stopped then return "stopped"', 'set s to "paused"', 'if player state is playing then set s to "playing"',
  'return s & tab & (name of current track) & tab & (artist of current track)', 'on error', 'return "stopped"', 'end try', 'end tell'].join('\n');
export function parsePlayer(out) {
  const [state, title, artist] = String(out == null ? '' : out).replace(/\r?\n$/, '').split('\t');
  if (state !== 'playing' && state !== 'paused') return null;
  return { playing: state === 'playing', title: String(title || '').trim(), artist: String(artist || '').trim() };
}
export const playerDo = (app, what) => `tell application "${app}" to ${what === 'next' ? 'next track' : what === 'prev' ? 'previous track' : 'playpause'}`;
