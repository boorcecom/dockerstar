(() => {
  'use strict';

  // Base de l'API : vide = même origine. Une application empaquetée (Android) peut
  // définir window.SOLAR_API_BASE = 'https://solar.exemple.fr' avant de charger ce script.
  const API_BASE = window.SOLAR_API_BASE || '';
  const SVG_NS = 'http://www.w3.org/2000/svg';

  const $ = (sel) => document.querySelector(sel);
  const nf0 = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 });
  const nf1 = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 });
  const nf2 = new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

  let TZ = 'Europe/Paris';
  const state = { view: 'day', date: null, range: 30 };

  /* ---------- utilitaires ---------- */

  function h(tag, attrs, ...children) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) el.setAttribute(k, v);
    for (const c of children) el.append(c);
    return el;
  }
  function s(tag, attrs, text) {
    const el = document.createElementNS(SVG_NS, tag);
    for (const [k, v] of Object.entries(attrs || {})) el.setAttribute(k, v);
    if (text !== undefined) el.textContent = text; // textContent : jamais d'HTML issu des données
    return el;
  }

  const fmtCache = new Map();
  function dtf(opts) {
    const key = TZ + JSON.stringify(opts);
    if (!fmtCache.has(key)) fmtCache.set(key, new Intl.DateTimeFormat('fr-FR', { timeZone: TZ, ...opts }));
    return fmtCache.get(key);
  }
  const fmtTime = (sec) => dtf({ hour: '2-digit', minute: '2-digit' }).format(new Date(sec * 1000));
  const fmtPower = (w) => (w >= 1000 ? nf2.format(w / 1000) + ' kW' : nf0.format(w) + ' W');
  const todayStr = () => new Intl.DateTimeFormat('en-CA', { timeZone: TZ }).format(new Date());

  function addDays(str, n) {
    const d = new Date(str + 'T12:00:00Z');
    d.setUTCDate(d.getUTCDate() + n);
    return d.toISOString().slice(0, 10);
  }
  function fmtDateLong(str) {
    return new Date(str + 'T12:00:00Z').toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' });
  }
  function fmtAgo(sec) {
    if (sec < 90) return "à l'instant";
    if (sec >= 86400) return 'il y a ' + Math.floor(sec / 86400) + ' j';
    const min = Math.round(sec / 60); // arrondi sur le total : jamais « 17 h 60 »
    if (min < 60) return 'il y a ' + min + ' min';
    return 'il y a ' + Math.floor(min / 60) + ' h ' + String(min % 60).padStart(2, '0');
  }

  async function api(path) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 15000);
    try {
      const r = await fetch(API_BASE + '/api/v1/' + path, { signal: ctrl.signal });
      if (r.status === 404) return null;
      if (r.status === 429) throw new Error('Trop de requêtes, réessayez dans un instant.');
      if (!r.ok) throw new Error('Erreur du serveur (' + r.status + ').');
      const data = await r.json();
      if (data && data.tz) TZ = data.tz;
      return data;
    } finally {
      clearTimeout(timer);
    }
  }

  // Échelle « ronde » : pas de 1, 2, 5 ou 10 × 10^n, environ `target` graduations.
  function niceScale(max, target = 4) {
    if (!(max > 0)) return { max: 1, step: 0.25 };
    const raw = max / target;
    const mag = 10 ** Math.floor(Math.log10(raw));
    const norm = raw / mag;
    const step = (norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10) * mag;
    return { step, max: Math.ceil(max / step - 1e-9) * step };
  }

  /* ---------- squelette commun d'un graphique ---------- */

  function frame(box, m, heightFor) {
    const W = Math.max(box.clientWidth, 280);
    const H = heightFor(W);
    const svg = s('svg', { viewBox: `0 0 ${W} ${H}`, width: W, height: H, tabindex: '0', role: 'img' });
    return { svg, W, H, iw: W - m.l - m.r, ih: H - m.t - m.b };
  }

  function yAxis(svg, m, f, scale, fmt) {
    for (let v = 0; v <= scale.max + 1e-9; v += scale.step) {
      const y = m.t + f.ih - (v / scale.max) * f.ih;
      svg.append(s('line', { class: v === 0 ? 'axis-line' : 'grid-line', x1: m.l, x2: m.l + f.iw, y1: y, y2: y }));
      svg.append(s('text', { class: 'tick', x: m.l - 6, y: y + 4, 'text-anchor': 'end' }, fmt(v)));
    }
  }

  function placeTooltip(box, tip, x) {
    const w = tip.offsetWidth;
    let left = x + 12;
    if (left + w > box.clientWidth) left = x - w - 12;
    tip.style.left = Math.max(0, left) + 'px';
  }

  function attachPointer(svg, hit, count, m, f, show, hide) {
    // Un seul écouteur : le pointeur trouve la position X la plus proche (jamais besoin de viser la marque).
    const idxAt = (clientX) => {
      const rect = svg.getBoundingClientRect();
      return hit(((clientX - rect.left) / rect.width) * f.W);
    };
    svg.addEventListener('pointermove', (e) => { const i = idxAt(e.clientX); if (i !== null) show(i); });
    svg.addEventListener('pointerdown', (e) => { const i = idxAt(e.clientX); if (i !== null) show(i); });
    svg.addEventListener('pointerleave', (e) => { if (e.pointerType === 'mouse') hide(); });
    let cur = -1;
    svg.addEventListener('focus', () => { if (cur < 0) cur = count - 1; show(cur); });
    svg.addEventListener('blur', hide);
    svg.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowLeft') cur = Math.max(0, (cur < 0 ? count : cur) - 1);
      else if (e.key === 'ArrowRight') cur = Math.min(count - 1, cur + 1);
      else if (e.key === 'Escape') { hide(); return; }
      else return;
      e.preventDefault();
      show(cur);
    });
  }

  /* ---------- courbe de puissance d'une journée ---------- */

  function drawDay(box, data) {
    box.replaceChildren();
    const pts = data.points;
    if (!pts.length) {
      box.append(h('p', { class: 'empty' }, 'Aucune mesure pour ce jour.'));
      return;
    }
    const m = { l: 46, r: 12, t: 22, b: 26 };
    const f = frame(box, m, (W) => Math.max(220, Math.min(340, Math.round(W * 0.55))));
    const { svg } = f;

    const t0 = Math.max(data.day_start, Math.floor((pts[0][0] - 1800) / 3600) * 3600);
    const t1 = Math.min(data.day_end, Math.ceil((pts[pts.length - 1][0] + 1800) / 3600) * 3600);
    const maxW = Math.max(...pts.map((p) => p[1]));
    const scale = niceScale(maxW);
    const X = (t) => m.l + ((t - t0) / (t1 - t0)) * f.iw;
    const Y = (v) => m.t + f.ih - (v / scale.max) * f.ih;

    yAxis(svg, m, f, scale, (v) => nf0.format(v));

    // graduations horaires, assez espacées pour ne pas se chevaucher
    const hours = (t1 - t0) / 3600;
    const stepH = [1, 2, 3, 4, 6].find((n) => (hours / n) * 48 <= f.iw) || 6;
    for (let t = t0; t <= t1; t += stepH * 3600) {
      if (t >= data.day_end) break; // 24:00 = 00:00 du lendemain : pas d'étiquette ambiguë
      svg.append(s('text', { class: 'tick', x: X(t), y: f.H - 6, 'text-anchor': 'middle' }, fmtTime(t)));
    }

    // segments : une interruption de plus de 20 min coupe la courbe
    const segs = [];
    let cur = [];
    pts.forEach((p, i) => {
      if (i && p[0] - pts[i - 1][0] > 1200) { segs.push(cur); cur = []; }
      cur.push(p);
    });
    segs.push(cur);
    const base = Y(0);
    for (const seg of segs) {
      const line = seg.map((p, i) => (i ? 'L' : 'M') + X(p[0]).toFixed(1) + ',' + Y(p[1]).toFixed(1)).join('');
      svg.append(s('path', { class: 'area', d: `${line}L${X(seg[seg.length - 1][0]).toFixed(1)},${base}L${X(seg[0][0]).toFixed(1)},${base}Z` }));
      svg.append(s('path', { class: 'line', d: line }));
    }

    // annotation du pic (une seule étiquette directe)
    const peak = pts.reduce((a, p) => (p[1] > a[1] ? p : a), pts[0]);
    const px = X(peak[0]);
    const anchor = px > f.W - 80 ? 'end' : px < m.l + 60 ? 'start' : 'middle';
    svg.append(s('text', { class: 'annot', x: px, y: Math.max(12, Y(peak[1]) - 8), 'text-anchor': anchor }, fmtPower(peak[1])));

    const cross = s('line', { class: 'cross', y1: m.t, y2: m.t + f.ih, visibility: 'hidden' });
    const dot = s('circle', { class: 'dot', r: 4, visibility: 'hidden' });
    svg.append(cross, dot);

    const tip = h('div', { class: 'tooltip', hidden: '' });
    box.append(svg, tip);
    svg.setAttribute('aria-label', `Puissance au fil de la journée. Pic de ${fmtPower(peak[1])} à ${fmtTime(peak[0])}. Flèches gauche et droite pour parcourir les mesures.`);

    const nearest = (x) => {
      const t = t0 + ((x - m.l) / f.iw) * (t1 - t0);
      let lo = 0, hi = pts.length - 1;
      while (lo < hi) { const mid = (lo + hi) >> 1; if (pts[mid][0] < t) lo = mid + 1; else hi = mid; }
      if (lo > 0 && Math.abs(pts[lo - 1][0] - t) < Math.abs(pts[lo][0] - t)) lo--;
      return lo;
    };
    attachPointer(svg, (x) => (x < m.l - 10 || x > f.W - m.r + 10 ? null : nearest(x)), pts.length, m, f,
      (i) => {
        const [t, w] = pts[i];
        const x = X(t);
        cross.setAttribute('x1', x); cross.setAttribute('x2', x); cross.setAttribute('visibility', 'visible');
        dot.setAttribute('cx', x); dot.setAttribute('cy', Y(w)); dot.setAttribute('visibility', 'visible');
        tip.replaceChildren(
          h('div', {}, h('span', { class: 'key' }), h('strong', {}, fmtPower(w))),
          h('div', { class: 'sub' }, fmtTime(t)));
        tip.hidden = false;
        placeTooltip(box, tip, x);
      },
      () => { cross.setAttribute('visibility', 'hidden'); dot.setAttribute('visibility', 'hidden'); tip.hidden = true; });
  }

  /* ---------- colonnes d'énergie (jours ou mois) ---------- */

  function drawBars(box, bars, unitLabel) {
    box.replaceChildren();
    if (!bars.length) {
      box.append(h('p', { class: 'empty' }, 'Aucune donnée sur cette période.'));
      return;
    }
    const m = { l: 46, r: 12, t: 22, b: 28 };
    const f = frame(box, m, (W) => Math.max(220, Math.min(320, Math.round(W * 0.5))));
    const { svg } = f;
    const maxV = Math.max(...bars.map((b) => b.value));
    const scale = niceScale(maxV);
    const slot = f.iw / bars.length;
    const bw = Math.max(1.5, Math.min(24, slot - 2)); // ≤ 24 px, 2 px d'air entre les colonnes
    const Y = (v) => m.t + f.ih - (v / scale.max) * f.ih;
    const fmtTickV = scale.step < 1 ? (v) => nf1.format(v) : (v) => nf0.format(v);

    yAxis(svg, m, f, scale, fmtTickV);

    const every = Math.max(1, Math.ceil(52 / slot));
    const rects = bars.map((b, i) => {
      const x = m.l + i * slot + (slot - bw) / 2;
      if (i % every === 0) {
        svg.append(s('text', { class: 'tick', x: x + bw / 2, y: f.H - 8, 'text-anchor': 'middle' }, b.label));
      }
      if (b.value <= 0) return null;
      const y = Y(b.value), yb = Y(0), r = Math.min(4, bw / 2, yb - y);
      // arrondi de 4 px côté valeur, base carrée
      const d = `M${x},${yb}L${x},${y + r}Q${x},${y} ${x + r},${y}L${x + bw - r},${y}Q${x + bw},${y} ${x + bw},${y + r}L${x + bw},${yb}Z`;
      const el = s('path', { class: 'bar', d });
      svg.append(el);
      return el;
    });

    // étiquette directe sur la meilleure colonne
    const best = bars.reduce((a, b, i) => (b.value > bars[a].value ? i : a), 0);
    const bx = m.l + best * slot + slot / 2;
    svg.append(s('text', { class: 'annot', x: bx, y: Math.max(12, Y(bars[best].value) - 6),
      'text-anchor': bx > f.W - 40 ? 'end' : bx < m.l + 30 ? 'start' : 'middle' }, nf1.format(bars[best].value)));

    const tip = h('div', { class: 'tooltip', hidden: '' });
    box.append(svg, tip);
    svg.setAttribute('aria-label', `Énergie produite en ${unitLabel}, ${bars.length} colonnes. Meilleure : ${bars[best].full}, ${nf1.format(bars[best].value)} kWh. Flèches pour parcourir.`);

    let active = -1;
    const clear = () => { if (active >= 0 && rects[active]) rects[active].classList.remove('active'); active = -1; };
    attachPointer(svg, (x) => (x < m.l || x > f.W - m.r ? null : Math.min(bars.length - 1, Math.floor((x - m.l) / slot))), bars.length, m, f,
      (i) => {
        clear();
        active = i;
        if (rects[i]) rects[i].classList.add('active');
        const b = bars[i];
        tip.replaceChildren(
          h('div', {}, h('strong', {}, nf1.format(b.value) + ' kWh')),
          h('div', { class: 'sub' }, b.full + (b.extra ? ' · ' + b.extra : '')));
        tip.hidden = false;
        placeTooltip(box, tip, m.l + i * slot + slot / 2);
      },
      () => { clear(); tip.hidden = true; });
  }

  /* ---------- tableaux (accès sans survol) ---------- */

  function fillTable(details, headers, rows) {
    const wrap = details.querySelector('.table-wrap');
    const build = () => {
      const thead = h('thead', {}, h('tr', {}, ...headers.map((t) => h('th', { scope: 'col' }, t))));
      const tbody = h('tbody', {}, ...rows.map((r) => h('tr', {}, ...r.map((c) => h('td', {}, c)))));
      wrap.replaceChildren(h('table', {}, thead, tbody));
    };
    details.onrefresh = build;
    if (details.open) build(); else wrap.replaceChildren();
  }
  for (const id of ['#table-day', '#table-hist']) {
    const d = $(id);
    d.addEventListener('toggle', () => { if (d.open && d.onrefresh) d.onrefresh(); });
  }

  /* ---------- chargement des vues ---------- */

  function showNotice(msg) {
    const n = $('#notice');
    n.hidden = !msg;
    n.textContent = msg || '';
  }

  async function loadKpis() {
    const [cur, day, sum] = await Promise.allSettled([api('current'), api('day'), api('summary')]);
    if (cur.status === 'rejected' && day.status === 'rejected') {
      showNotice('⚠ Impossible de joindre le serveur. Les dernières données connues restent affichées.');
      $('#updated').textContent = 'Hors ligne';
      return;
    }
    const c = cur.status === 'fulfilled' ? cur.value : null;
    const d = day.status === 'fulfilled' ? day.value : null;
    const t = sum.status === 'fulfilled' ? sum.value : null;
    let notice = '';
    if (c) {
      $('#k-power').textContent = fmtPower(c.watts);
      $('#k-power-sub').textContent = fmtTime(c.t) + ' · ' + fmtAgo(c.age_seconds);
      $('#updated').textContent = 'Dernière mesure ' + fmtAgo(c.age_seconds);
      // en plein jour, une mesure ancienne est anormale ; la nuit, l'onduleur est à l'arrêt
      if (c.sun_elevation_deg > 5 && c.age_seconds > 1800) {
        notice = '⚠ Pas de nouvelle mesure ' + fmtAgo(c.age_seconds) + ' alors que le soleil est levé.';
      }
    } else {
      $('#updated').textContent = 'Aucune mesure disponible';
    }
    if (d) {
      $('#k-energy').textContent = nf2.format(d.summary.energy_kwh) + ' kWh';
      $('#k-peak').textContent = d.summary.peak_at ? fmtPower(d.summary.peak_w) : '–';
      $('#k-peak-sub').textContent = d.summary.peak_at ? 'à ' + fmtTime(d.summary.peak_at) : 'pas encore de mesure';
      if (d.summary.sun_max_elevation_deg != null) $('#k-sun').textContent = nf1.format(d.summary.sun_max_elevation_deg) + '°';
    }
    if (t) {
      $('#k-energy-sub').textContent = '7 derniers jours : ' + nf1.format(t.last7_kwh) + ' kWh';
      $('#totals').textContent = `30 jours : ${nf0.format(t.last30_kwh)} kWh · 12 mois : ${nf0.format(t.last365_kwh)} kWh · total : ${nf0.format(t.total_kwh)} kWh`;
    }
    showNotice(notice);
  }

  let daySeq = 0;
  async function loadDay() {
    const box = $('#chart-day');
    const seq = ++daySeq;
    const date = state.date || todayStr();
    $('#day-input').value = date;
    $('#day-next').disabled = date >= todayStr();
    box.classList.add('loading');
    try {
      const data = await api('day?date=' + date);
      if (seq !== daySeq) return; // une requête plus récente a pris le relais
      const isToday = date === todayStr();
      $('#day-title').textContent = isToday ? "Puissance aujourd'hui" : 'Puissance';
      if (data) {
        const sm = data.summary;
        $('#day-sub').textContent = fmtDateLong(data.date) + (sm.peak_at ? ` · ${nf2.format(sm.energy_kwh)} kWh · pic ${fmtPower(sm.peak_w)} à ${fmtTime(sm.peak_at)}` : '');
        drawDay(box, data);
        fillTable($('#table-day'), ['Heure', 'Puissance (W)'], data.points.map((p) => [fmtTime(p[0]), nf0.format(p[1])]));
      } else {
        $('#day-sub').textContent = fmtDateLong(date);
        drawDay(box, { points: [] });
      }
    } catch (err) {
      if (seq === daySeq) showNotice('⚠ ' + err.message);
    } finally {
      if (seq === daySeq) box.classList.remove('loading');
    }
  }

  let histSeq = 0;
  async function loadHistory() {
    const box = $('#chart-hist');
    const seq = ++histSeq;
    const to = todayStr();
    const from = addDays(to, -(state.range - 1));
    box.classList.add('loading');
    try {
      const data = await api(`days?from=${from}&to=${to}`);
      if (seq !== histSeq) return;
      const days = data ? data.days : [];
      let bars, unit, rows, headers;
      if (state.range > 90) {
        // 12 mois : regroupement par mois
        const byMonth = new Map();
        for (const d of days) byMonth.set(d.date.slice(0, 7), (byMonth.get(d.date.slice(0, 7)) || 0) + d.energy_kwh);
        bars = [...byMonth].map(([k, v]) => {
          const dt = new Date(k + '-15T12:00:00Z');
          return {
            value: v,
            label: dt.toLocaleDateString('fr-FR', { month: 'short', timeZone: 'UTC' }).replace('.', ''),
            full: dt.toLocaleDateString('fr-FR', { month: 'long', year: 'numeric', timeZone: 'UTC' }),
          };
        });
        unit = 'mois';
        headers = ['Mois', 'Énergie (kWh)'];
        rows = bars.map((b) => [b.full, nf1.format(b.value)]);
      } else {
        const byDate = new Map(days.map((d) => [d.date, d]));
        bars = [];
        for (let d = from; d <= to; d = addDays(d, 1)) {
          const e = byDate.get(d);
          bars.push({
            value: e ? e.energy_kwh : 0,
            label: d.slice(8) + '/' + d.slice(5, 7),
            full: fmtDateLong(d),
            extra: e ? 'pic ' + fmtPower(e.peak_w) : 'pas de mesure',
          });
        }
        unit = 'jours';
        headers = ['Date', 'Énergie (kWh)', 'Pic (W)'];
        rows = days.slice().reverse().map((d) => [d.date, nf2.format(d.energy_kwh), nf0.format(d.peak_w)]);
      }
      const total = bars.reduce((a, b) => a + b.value, 0);
      $('#hist-title').textContent = 'Énergie produite par ' + (state.range > 90 ? 'mois' : 'jour');
      $('#hist-sub').textContent = `Total sur la période : ${nf0.format(total)} kWh`;
      drawBars(box, bars, unit);
      fillTable($('#table-hist'), headers, rows);
    } catch (err) {
      if (seq === histSeq) showNotice('⚠ ' + err.message);
    } finally {
      if (seq === histSeq) box.classList.remove('loading');
    }
  }

  /* ---------- interactions ---------- */

  function setView(v) {
    state.view = v;
    for (const name of ['day', 'history']) {
      const on = name === v;
      $('#tab-' + name).setAttribute('aria-selected', String(on));
      $('#view-' + name).hidden = !on;
    }
    (v === 'day' ? loadDay : loadHistory)();
  }
  $('#tab-day').addEventListener('click', () => setView('day'));
  $('#tab-history').addEventListener('click', () => setView('history'));

  $('#day-prev').addEventListener('click', () => { state.date = addDays(state.date || todayStr(), -1); loadDay(); });
  $('#day-next').addEventListener('click', () => {
    const next = addDays(state.date || todayStr(), 1);
    state.date = next >= todayStr() ? null : next;
    loadDay();
  });
  $('#day-today').addEventListener('click', () => { state.date = null; loadDay(); });
  $('#day-input').addEventListener('change', (e) => {
    const v = e.target.value;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(v)) return;
    state.date = v >= todayStr() ? null : v;
    loadDay();
  });

  document.querySelectorAll('[data-range]').forEach((btn) => {
    btn.addEventListener('click', () => {
      state.range = Number(btn.dataset.range);
      document.querySelectorAll('[data-range]').forEach((b) => b.setAttribute('aria-pressed', String(b === btn)));
      loadHistory();
    });
  });

  // redessin à la rotation / au redimensionnement (sans refaire d'appel réseau)
  let resizeTimer;
  let lastWidth = window.innerWidth;
  window.addEventListener('resize', () => {
    if (window.innerWidth === lastWidth) return;
    lastWidth = window.innerWidth;
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => (state.view === 'day' ? loadDay() : loadHistory()), 250);
  });

  // rafraîchissement automatique toutes les 60 s, seulement si la page est visible
  function refresh() {
    if (document.hidden) return;
    loadKpis();
    if (state.view === 'day' && state.date === null) loadDay();
  }
  setInterval(refresh, 60000);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });

  // Paramètres d'URL (signets) : ?date=2025-06-21  ou  ?view=history&range=90
  const params = new URLSearchParams(location.search);
  if (/^\d{4}-\d{2}-\d{2}$/.test(params.get('date') || '') && params.get('date') < todayStr()) state.date = params.get('date');
  if (['30', '90', '365'].includes(params.get('range'))) {
    state.range = Number(params.get('range'));
    document.querySelectorAll('[data-range]').forEach((b) => b.setAttribute('aria-pressed', String(Number(b.dataset.range) === state.range)));
  }
  loadKpis();
  setView(params.get('view') === 'history' ? 'history' : 'day');

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').catch(() => {});
  }
})();
