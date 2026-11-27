// Run with CHROME_BIN pointing to Chrome or Chromium. Pass an extracted assets directory
// to exercise the calculator shipped in an APK, or omit it to test the source assets.
import { execFileSync } from 'node:child_process';
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

const source = process.argv[2] || fileURLToPath(new URL('../../app/assets/', import.meta.url));
const directory = mkdtempSync(path.join(tmpdir(), 'maxridr-gearing-'));
const chrome = process.env.CHROME_BIN || 'chromium';
const page = path.join(directory, 'gearing/index.html');
cpSync(path.join(source, 'gearing'), path.dirname(page), { recursive: true });
const template = readFileSync(page, 'utf8');

function launch(context, checks) {
  const harness = `<script>
    function check(ok, message) { if (!ok) throw Error(message); }
    function set(name, value) {
      form.elements[name].value = value;
      form.dispatchEvent(new Event('input'));
    }
    function choose(id) {
      chooser.value = id; chooser.dispatchEvent(new Event('change'));
    }
    try { ${checks}; document.title = 'PASS'; }
    catch (e) { document.title = 'FAIL ' + e.message; }
  </script>`;
  writeFileSync(page, template.replace('</body>', harness + '</body>'));
  const url = pathToFileURL(page);
  url.hash = context;
  const html = execFileSync(
    chrome,
    [
      '--headless',
      '--disable-gpu',
      '--no-first-run',
      '--disable-background-networking',
      '--host-resolver-rules=MAP * ~NOTFOUND',
      '--user-data-dir=' + path.join(directory, 'profile'),
      '--dump-dom',
      url.href,
    ],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
  );
  assert.equal(html.match(/<title>(.*?)<\/title>/)?.[1], 'PASS');
}

try {
  launch(
    'talaria-mx4',
    `
    check(!form.elements.cf.value, 'invented tooth count');
    set('cf',14); set('cr',48); set('rpm',2800); set('dia',22.5);
    set('pitch','custom'); set('pitchMm',12.7); set('ctr',560); set('goal','pull');
    suggest.click();
    check(form.elements.nr.value === '50', 'pull suggestion');
    check(document.getElementById('oldChain').textContent === '120', 'custom pitch estimate');
  `,
  );
  launch(
    'sur-ron-ultra-bee',
    `
    check(!form.elements.cf.value, 'leaked previous bike');
    set('cf',13); set('cr',52); set('goal','speed'); suggest.click(); set('unit','kmh');
    check(document.getElementById('newSpeed').textContent === '—', 'invented speed');
  `,
  );
  launch(
    'talaria-mx4',
    `
    check(form.elements.cf.value === '14' && form.elements.nr.value === '50', 'lost first bike');
    check(form.elements.pitchMm.value === '12.7' && goal.value === 'pull', 'lost drivetrain');
    set('cr',''); // An unfinished edit must not overwrite the last valid setup.
  `,
  );
  launch(
    'sur-ron-ultra-bee',
    `
    check(form.elements.nr.value === '50' && form.elements.unit.value === 'kmh', 'lost second bike');
    check(goal.value === 'speed' && !form.elements.rpm.value, 'leaked advanced settings');
  `,
  );
  launch(
    '',
    `
    choose('add'); document.getElementById('bikeName').value = 'My cargo build';
    addForm.requestSubmit(); set('cf',42); set('cr',16); set('nf',42); set('nr',18);
  `,
  );
  launch(
    '',
    `
    check(chooser.selectedOptions[0].text === 'My cargo build' && form.elements.cf.value === '42', 'lost custom bike');
    choose('auto:talaria-mx4');
    check(form.elements.cr.value === '48', 'partial edit overwrote saved profile');
  `,
  );
  console.log(
    'Passed six offline launches covering automatic and manual bike switching, saves, and gearing suggestions.',
  );
} finally {
  rmSync(directory, { recursive: true, force: true });
}
