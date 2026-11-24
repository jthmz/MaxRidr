// Checks the Deck's small decisions (rules.mjs). No Mac, Car Thing or network needed:   node --test deck/rules.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { askKey, readAnswers, putAnswer, answersDoc, takeoff, clockMin, isNight, clockText, rideOf, rideText, playerScript, parsePlayer, playerDo } from './rules.mjs';

const H = 3600000;

test('an answer belongs to an ask by the ask\'s own words', () => {
  const a = askKey('blocked_on_joe', '', 'ok to delete old v1/v3/v6 files?');
  assert.equal(a, askKey('blocked_on_joe', '', 'ok to delete old v1/v3/v6 files?'));
  assert.notEqual(a, askKey('blocked_on_joe', '', 'ok to delete old v1/v3/v6/v9 files?'), 'a reworded ask is a new ask');
  assert.notEqual(askKey('next', 'hype', 'post it'), askKey('next', 'r3', 'post it'), 'the same words on another video are another ask');
});

test('answers: one for each ask, the newest first, taken back with anything else', () => {
  const ask = { about: 'blocked_on_joe', id: '', title: '', text: 'ok to delete old files?' }, k = askKey(ask.about, ask.id, ask.text);
  const next = { about: 'next', id: 'hype', title: 'Hype reel', text: 'Joe: post the hype reel' }, k2 = askKey(next.about, next.id, next.text);
  let list = putAnswer([], k, ask, 'not yet', new Date('2026-11-24T07:00:00Z'));
  list = putAnswer(list, k2, next, 'done', new Date('2026-11-24T07:01:00Z'));
  list = putAnswer(list, k, ask, 'ok', new Date('2026-11-24T07:02:00Z'));
  assert.deepEqual(list.map(a => [a.answer, a.ask]), [['ok', 'ok to delete old files?'], ['done', 'Joe: post the hype reel']]);
  assert.equal(list[1].video, 'Hype reel');
  assert.deepEqual(putAnswer(list, k, ask, '').map(a => a.answer), ['done'], 'an answer can be taken back');
  assert.equal(putAnswer(list, k, ask, 'maybe').length, 1, 'only ok, done and not yet are answers');
});

test('the answers file reads back as it was written, and carries no keys', () => {
  const ask = { about: 'next', id: 'r3', title: 'Nachbike R3', text: 'upload the master' }, k = askKey(ask.about, ask.id, ask.text);
  const doc = JSON.parse(JSON.stringify(answersDoc(putAnswer([], k, ask, 'done', new Date('2026-11-24T07:00:00Z')), new Date('2026-11-24T07:00:01Z'))));
  assert.equal(doc.answers.length, 1);
  assert.deepEqual(doc.answers[0], { at: '2026-11-24T07:00:00.000Z', answer: 'done', about: 'next', id: 'r3', video: 'Nachbike R3', ask: 'upload the master' });
  assert.match(doc.about, /never changes the board/);
  assert.equal(readAnswers(doc)[0].key, k, 'the key is worked out again from the ask');
});

test('a damaged or hand-edited answers file loses only what is malformed', () => {
  assert.deepEqual(readAnswers(null), []);
  assert.deepEqual(readAnswers({ answers: 'nope' }), []);
  const kept = readAnswers({ answers: [{ answer: 'ok', about: 'blocked_on_joe', ask: 'a' }, { answer: 'sure', about: 'blocked_on_joe', ask: 'b' }, { answer: 'ok', about: 'elsewhere', ask: 'c' }, null, { answer: 'done', about: 'next', id: 7, ask: 'd' }] });
  assert.deepEqual(kept.map(a => a.ask), ['a', 'd']);
  assert.equal(kept[1].id, '7');
});

// snapshots every five minutes: `usual` views an hour until three hours ago, `now` views an hour since
function history(now, usual, recent, hours = 30) {
  const out = []; let views = 1000;
  for (let t = now - hours * H; t <= now; t += H / 12) { out.push({ t, v: { a: Math.round(views) } }); views += (now - t <= 3 * H ? recent : usual) / 12; }
  return out;
}
test('taking off: well above its own usual rate, and enough to matter', () => {
  const now = Date.parse('2026-11-24T12:00:00Z');
  let h = history(now, 20, 120);
  const up = takeoff(h, 'a', h[h.length - 1].v.a, now);
  assert.ok(up, 'six times the usual rate is taking off');
  assert.ok(Math.abs(up.perHr - 120) <= 6 && Math.abs(up.usual - 20) <= 2, JSON.stringify(up));
  assert.ok(up.times > 5 && up.times < 7);
  h = history(now, 20, 40);
  assert.equal(takeoff(h, 'a', h[h.length - 1].v.a, now), null, 'twice the usual is an ordinary good day');
  h = history(now, 2, 12);
  assert.equal(takeoff(h, 'a', h[h.length - 1].v.a, now), null, 'six times very little is still very little');
  assert.ok(takeoff(h, 'a', h[h.length - 1].v.a, now, { minPerHour: 5 }), 'unless the line is set lower');
  h = history(now, 20, 120);
  assert.equal(takeoff(h, 'a', h[h.length - 1].v.a, now, { times: 10 }), null, 'or the bar is set higher');
});
test('taking off: a new upload, or too little history, says nothing', () => {
  const now = Date.parse('2026-11-24T12:00:00Z');
  let h = history(now, 500, 500, 8);                                         // uploaded eight hours ago, busy the whole time
  assert.equal(takeoff(h, 'a', h[h.length - 1].v.a, now), null);
  h = history(now, 20, 120).filter(x => now - x.t < 1 * H || now - x.t > 20 * H);   // the Mac was asleep for most of the day
  assert.equal(takeoff(h, 'a', h[h.length - 1].v.a, now), null);
  assert.equal(takeoff([], 'a', 5000, now), null);
  assert.equal(takeoff(history(now, 20, 120), 'other', 5000, now), null, 'a video not in the history');
});

test('night by the clock, across midnight and not', () => {
  const at = (h, m = 0) => new Date(2026, 10, 24, h, m);
  assert.equal(clockMin('22:00'), 1320); assert.equal(clockMin('7'), 420); assert.equal(clockMin(' 07:30 '), 450);
  for (const bad of ['', null, undefined, '25:00', '7:60', 'ten', '7pm']) assert.equal(clockMin(bad), null, String(bad));
  assert.equal(isNight('22:00', '07:00', at(21, 59)), false);
  assert.equal(isNight('22:00', '07:00', at(22, 0)), true);
  assert.equal(isNight('22:00', '07:00', at(3)), true);
  assert.equal(isNight('22:00', '07:00', at(6, 59)), true);
  assert.equal(isNight('22:00', '07:00', at(7, 0)), false);
  assert.equal(isNight('01:00', '06:00', at(0, 30)), false);
  assert.equal(isNight('01:00', '06:00', at(5)), true);
  assert.equal(isNight('', '07:00', at(5)), null, 'no schedule');
  assert.equal(isNight('07:00', '07:00', at(5)), null, 'a night of no length is no schedule');
  assert.equal(clockText('22:00'), '10 PM'); assert.equal(clockText('07:30'), '7:30 AM'); assert.equal(clockText('0'), '12 AM'); assert.equal(clockText('12:05'), '12:05 PM');
});

test('the last ride, in the units its rider reads', () => {
  const sent = { app: 'MAXAPACITY Ride', units: 'mi', file: 'ride_2026-11-23_1712.gpx', start: 1795478000000, distanceM: 19955, movingMs: 1490000, elapsedMs: 1620000, topMps: 18.33, climbM: 96, stops: 2, leanLeft: 38, leanRight: 41, battery: 64 };
  const r = rideOf(sent, 'km');
  assert.deepEqual(r, { start: 1795478000000, end: 1795478000000 + 1620000, dist: '12.4', unit: 'mi', top: 41, speed: 'mph', left: 38, right: 41, battery: 64 });
  assert.equal(rideText(r), '12.4 mi · top 41 mph · lean 38° left, 41° right · battery 64%');
  const km = rideOf({ ...sent, units: 'km' }, 'mi');
  assert.equal(km.dist + ' ' + km.unit + ' ' + km.top + ' ' + km.speed, '20.0 km 66 km/h');
  const old = rideOf({ start: 1795478000000, distanceM: 19955, topMps: 18.33 }, 'mi');     // from an app that sent neither units, lean nor battery
  assert.equal(old.unit, 'mi'); assert.equal(old.battery, null);
  assert.equal(rideText(old), '12.4 mi · top 41 mph');
  assert.equal(rideOf({ ...sent, battery: 250 }, 'mi').battery, 100);
  for (const not of [null, 'x', {}, { start: 1 }, { distanceM: 0, start: 1 }, { distanceM: 5 }]) assert.equal(rideOf(not, 'mi'), null);
});

test('what a music player said', () => {
  assert.deepEqual(parsePlayer('playing\tMidnight City\tM83\n'), { playing: true, title: 'Midnight City', artist: 'M83' });
  assert.deepEqual(parsePlayer('paused\tA song\t\n'), { playing: false, title: 'A song', artist: '' });
  for (const quiet of ['stopped\n', '', null, 'execution error: Not authorised to send Apple events (-1743)']) assert.equal(parsePlayer(quiet), null);
  assert.match(playerScript('Spotify'), /^tell application "Spotify"\n/);
  assert.equal(playerDo('Music', 'next'), 'tell application "Music" to next track');
  assert.equal(playerDo('Spotify', 'prev'), 'tell application "Spotify" to previous track');
  assert.equal(playerDo('Spotify', 'anything else'), 'tell application "Spotify" to playpause');
});
