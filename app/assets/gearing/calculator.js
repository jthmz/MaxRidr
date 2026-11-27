// Same reductions, pitch values and even-link rounding as maxapacity/src/lib/sprocket.ts.
const form = document.getElementById('gearing');
const website = document.getElementById('website');
const pitches = { 420: 12.7, 428: 12.7, 520: 15.875, 525: 15.875 };

function estimate(front, rear, inputs) {
  const ratio = front > 0 && rear > 0 ? rear / front : NaN;
  const wheelRpm = inputs.rpm / inputs.prim / ratio;
  const kmh = (wheelRpm * Math.PI * inputs.dia * 0.0254 * 60) / 1000;
  const pitch = inputs.pitch === 'custom' ? +inputs.pitchMm : pitches[inputs.pitch];
  const centers = inputs.ctr / pitch;
  const length = 2 * centers + (front + rear) / 2 + ((rear - front) / (2 * Math.PI)) ** 2 / centers;
  const speed = inputs.rpm > 0 && inputs.dia > 0 && inputs.prim > 0 ? kmh : NaN;
  const chain =
    Number.isFinite(ratio) && centers > 0 && Number.isFinite(centers) ? 2 * Math.ceil(length / 2) : NaN;
  return { ratio, speed: inputs.unit === 'mph' ? speed / 1.609344 : speed, chain };
}

function change(current, proposed) {
  const percent = (proposed / current - 1) * 100;
  return (percent > 0 ? '+' : '') + percent.toFixed(1) + '%';
}

function render() {
  const custom = form.elements.pitch.value === 'custom';
  document.getElementById('pitchLabel').hidden = !custom;
  form.elements.pitchMm.disabled = !custom;
  const valid = form.checkValidity();
  const inputs = Object.fromEntries(new FormData(form));
  const current = estimate(+inputs.cf, +inputs.cr, inputs);
  const proposed = estimate(+inputs.nf, +inputs.nr, inputs);
  for (const [prefix, result] of [
    ['old', current],
    ['new', proposed],
  ]) {
    document.getElementById(prefix + 'Ratio').textContent =
      valid && Number.isFinite(result.ratio) ? result.ratio.toFixed(2) : '—';
    document.getElementById(prefix + 'Speed').textContent =
      valid && Number.isFinite(result.speed) ? result.speed.toFixed(1) : '—';
    document.getElementById(prefix + 'Chain').textContent =
      valid && Number.isFinite(result.chain) ? result.chain : '—';
  }
  document.getElementById('speedLabel').textContent =
    'Top speed (' + (inputs.unit === 'mph' ? 'mph' : 'km/h') + ')';
  document.getElementById('delta').textContent =
    valid && Number.isFinite(proposed.ratio)
      ? 'Wheel torque ' +
        change(current.ratio, proposed.ratio) +
        ' · Top speed ' +
        change(proposed.ratio, current.ratio)
      : 'Enter installed and proposed tooth counts to compare gearing.';
  const websiteSetup = valid && !custom && Number.isFinite(proposed.speed) && Number.isFinite(proposed.chain);
  website.textContent = websiteSetup ? 'Open this setup on the website' : 'Open the website calculator';
  website.href =
    'https://maxapacitybikes.com/tools/sprocket-calculator' +
    (websiteSetup ? '?' + new URLSearchParams(inputs) : '');
}

// Keep one record per bike and a last selection per detected bike or hardware pair.
const prefix = 'maxridr.gearing.v1.';
const context = decodeURIComponent(location.hash.slice(1)) || 'manual';
const detected = 'auto:' + context;
const chooser = document.getElementById('bike');
const addForm = document.getElementById('addBike');
const statusLine = document.getElementById('saved');
const goal = document.getElementById('goal');
const suggest = document.getElementById('suggest');
const profiles = new Map();
let storage;
let active;
let defaultName = context.replaceAll('-', ' ');
if (context === 'manual') defaultName = 'My bike';
if (context === 'demo') defaultName = 'Demo bike';
if (context.startsWith('linked:')) {
  defaultName = context.slice(7).split('|').filter(Boolean).map(decodeURIComponent).join(' / ');
}

try {
  storage = window.localStorage;
  for (let i = 0; i < storage.length; i++) {
    const key = storage.key(i);
    if (!key.startsWith(prefix + 'bike.')) continue;
    try {
      const record = JSON.parse(storage.getItem(key));
      if (record && typeof record.name === 'string')
        profiles.set(key.slice((prefix + 'bike.').length), record);
    } catch {
      /* Ignore a damaged record without discarding other bikes. */
    }
  }
  active = storage.getItem(prefix + 'selection.' + context);
} catch {
  storage = null;
}
if (!profiles.has(detected)) profiles.set(detected, { name: defaultName });
if (!profiles.has(active)) active = detected;

function write(key, value) {
  try {
    if (!storage) return false;
    storage.setItem(prefix + key, value);
    return true;
  } catch {
    return false;
  }
}

function showBikes() {
  chooser.replaceChildren();
  for (const [id, profile] of profiles) chooser.add(new Option(profile.name, id));
  chooser.add(new Option('Add another bike…', 'add'));
  chooser.value = active;
}

function applyInputs(inputs) {
  for (const [name, value] of Object.entries(inputs || {})) {
    const field = form.elements.namedItem(name);
    if (!field || !field.name || !(field instanceof HTMLInputElement || field instanceof HTMLSelectElement))
      continue;
    const before = field.value;
    field.value = value;
    if (!field.value || !field.checkValidity()) field.value = before;
  }
}

function suggestion() {
  const rear = form.elements.cr;
  const front = form.elements.cf;
  const step = goal.value === 'pull' ? 2 : -2;
  const teeth = rear.valueAsNumber + step;
  const available = goal.value && front.checkValidity() && rear.checkValidity() && teeth >= 1 && teeth <= 300;
  suggest.hidden = !available;
  document.getElementById('suggestion').textContent = available
    ? 'Compare a two-tooth rear change with the same front. Check chain length, fit and clearance before fitting.'
    : goal.value
      ? 'Enter current tooth counts within the comparison range.'
      : '';
  if (available) suggest.textContent = 'Try ' + front.value + 'T front / ' + teeth + 'T rear';
}

function restore() {
  // Reset all inputs before applying the next bike so omitted fields cannot leak across profiles.
  for (const input of form.querySelectorAll('input[name]')) input.value = input.defaultValue;
  for (const select of form.querySelectorAll('select[name]')) {
    select.selectedIndex = Math.max(
      0,
      Array.from(select.options).findIndex((option) => option.defaultSelected),
    );
  }
  const profile = profiles.get(active);
  applyInputs(profile.inputs);
  goal.value = profile.goal || '';
  statusLine.textContent = profile.inputs
    ? 'Loaded setup for ' + profile.name + '.'
    : 'Enter your tooth counts to save a setup for ' + profile.name + '.';
  render();
  suggestion();
}

function edited() {
  render();
  suggestion();
  if (!form.checkValidity()) {
    statusLine.textContent = 'Complete the highlighted inputs to save these changes.';
    return;
  }
  const profile = profiles.get(active);
  profile.inputs = Object.fromEntries(new FormData(form));
  profile.goal = goal.value;
  statusLine.textContent = write('bike.' + active, JSON.stringify(profile))
    ? 'Saved on this phone for ' + profile.name + '.'
    : 'This setup could not be saved on this phone.';
}

function switchBike(id) {
  active = id;
  addForm.hidden = true;
  showBikes();
  restore();
  if (!write('selection.' + context, id)) statusLine.textContent = 'This bike selection could not be saved.';
}
chooser.addEventListener('change', () => {
  if (chooser.value !== 'add') return switchBike(chooser.value);
  chooser.value = active;
  addForm.hidden = false;
  document.getElementById('bikeName').focus();
});
document.getElementById('cancelBike').onclick = () => {
  addForm.hidden = true;
};
addForm.addEventListener('submit', (event) => {
  event.preventDefault();
  const field = document.getElementById('bikeName');
  const name = field.value.trim();
  if (!name) {
    field.focus();
    return;
  }
  const existing = [...profiles].find(([, p]) => p.name.toLowerCase() === name.toLowerCase());
  const id = existing ? existing[0] : 'named:' + name.toLowerCase();
  if (!existing) profiles.set(id, { name });
  const saved = write('bike.' + id, JSON.stringify(profiles.get(id)));
  field.value = '';
  switchBike(id);
  if (!saved) statusLine.textContent = 'This bike could not be saved on this phone.';
});
form.addEventListener('input', edited);
form.addEventListener('submit', (event) => event.preventDefault());
form.addEventListener('reset', (event) => {
  event.preventDefault();
  const profile = profiles.get(active);
  delete profile.inputs;
  delete profile.goal;
  const saved = write('bike.' + active, JSON.stringify(profile));
  restore();
  if (!saved) statusLine.textContent = 'The reset could not be saved on this phone.';
});
suggest.addEventListener('click', () => {
  form.elements.nf.value = form.elements.cf.value;
  form.elements.nr.value = form.elements.cr.valueAsNumber + (goal.value === 'pull' ? 2 : -2);
  edited();
});
for (const button of form.querySelectorAll('[data-rear]')) {
  button.addEventListener('click', () => {
    form.elements.nr.value = button.dataset.rear;
    edited();
  });
}
showBikes();
restore();
if (location.search && !profiles.get(active).inputs) {
  applyInputs(Object.fromEntries(new URLSearchParams(location.search)));
  render();
  suggestion();
}
