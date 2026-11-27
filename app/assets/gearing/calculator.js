// Same reductions, pitch values and even-link rounding as maxapacity/src/lib/sprocket.ts.
const form = document.getElementById('gearing');
const website = document.getElementById('website');
const pitches = { 420: 12.7, 428: 12.7, 520: 15.875, 525: 15.875 };

function estimate(front, rear, inputs) {
  const ratio = rear / front;
  const wheelRpm = inputs.rpm / inputs.prim / ratio;
  const kmh = (wheelRpm * Math.PI * inputs.dia * 0.0254 * 60) / 1000;
  const centers = inputs.ctr / pitches[inputs.pitch];
  const length = 2 * centers + (front + rear) / 2 + ((rear - front) / (2 * Math.PI)) ** 2 / centers;
  return { ratio, speed: inputs.unit === 'mph' ? kmh / 1.609344 : kmh, chain: 2 * Math.ceil(length / 2) };
}

function change(current, proposed) {
  const percent = (proposed / current - 1) * 100;
  return (percent > 0 ? '+' : '') + percent.toFixed(1) + '%';
}

function render() {
  const valid = form.checkValidity();
  const inputs = Object.fromEntries(new FormData(form));
  const current = estimate(+inputs.cf, +inputs.cr, inputs);
  const proposed = estimate(+inputs.nf, +inputs.nr, inputs);
  for (const [prefix, result] of [
    ['old', current],
    ['new', proposed],
  ]) {
    document.getElementById(prefix + 'Ratio').textContent = valid ? result.ratio.toFixed(2) : '—';
    document.getElementById(prefix + 'Speed').textContent = valid ? result.speed.toFixed(1) : '—';
    document.getElementById(prefix + 'Chain').textContent = valid ? result.chain : '—';
  }
  document.getElementById('speedLabel').textContent =
    'Top speed (' + (inputs.unit === 'mph' ? 'mph' : 'km/h') + ')';
  document.getElementById('delta').textContent = valid
    ? 'Wheel torque ' +
      change(current.ratio, proposed.ratio) +
      ' · Top speed ' +
      change(current.speed, proposed.speed)
    : 'Check the highlighted inputs to compare gearing.';
  website.hidden = !valid;
  website.style.display = valid ? '' : 'none';
  if (valid)
    website.href = 'https://maxapacitybikes.com/tools/sprocket-calculator?' + new URLSearchParams(inputs);
}

form.addEventListener('input', render);
form.addEventListener('submit', (event) => event.preventDefault());
form.addEventListener('reset', () => setTimeout(render, 0));
for (const button of form.querySelectorAll('[data-rear]')) {
  button.addEventListener('click', () => {
    form.elements.nr.value = button.dataset.rear;
    render();
  });
}
// A live calculator URL can carry an edited setup when a connection fails.
for (const [name, value] of new URLSearchParams(location.search)) {
  const field = form.elements.namedItem(name);
  if (!(field instanceof HTMLInputElement || field instanceof HTMLSelectElement)) continue;
  const previous = field.value;
  field.value = value;
  if (!field.value || !field.checkValidity()) field.value = previous;
}
window.addEventListener('pageshow', render);
render();
