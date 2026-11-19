'use strict';
// Prix carburants : rendu côté client à partir de /api/v1/prices.
// Pour une appli hébergée ailleurs (ex. Android/Capacitor) : window.CARBU_API_BASE = 'https://…'.
(() => {
  const API = (window.CARBU_API_BASE || '') + '/api/v1/prices';
  const LEVELS = { good: 'Fiable', medium: 'À vérifier', low: 'Ancien' };
  const $ = (id) => document.getElementById(id);
  const fmtPrice = (v) => v.toFixed(3).replace('.', ',');
  const nf = new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 1, maximumFractionDigits: 1 });

  let data = null;
  let fuel = safeGet('fuel') || 'gazole';
  let hideLow = safeGet('hideLow') === '1';

  function safeGet(k) { try { return localStorage.getItem('carbu.' + k); } catch { return null; } }
  function safeSet(k, v) { try { localStorage.setItem('carbu.' + k, v); } catch { /* stockage indisponible */ } }

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
    return e;
  }

  // « il y a 3 h », « il y a 2 j » : durée en heures -> texte.
  function ago(hours) {
    if (hours < 1) return "il y a moins d'1 h";
    if (hours < 48) return 'il y a ' + Math.round(hours) + ' h';
    return 'il y a ' + Math.round(hours / 24) + ' j';
  }
  function agoDate(iso, now) { return ago((now - new Date(iso)) / 3600000); }
  function inDate(iso, now) {
    const h = (new Date(iso) - now) / 3600000;
    if (h <= 0) return 'imminent';
    if (h < 1) return 'dans ' + Math.max(1, Math.round(h * 60)) + ' min';
    return h < 48 ? 'dans ' + Math.round(h) + ' h' : 'dans ' + Math.round(h / 24) + ' j';
  }

  function renderTabs() {
    const box = $('fuel-tabs');
    box.replaceChildren();
    for (const f of data.fuels) {
      const b = el('button', '', f.label);
      b.setAttribute('role', 'tab');
      b.setAttribute('aria-selected', String(f.key === fuel));
      b.addEventListener('click', () => { fuel = f.key; safeSet('fuel', fuel); render(); });
      box.append(b);
    }
  }

  function badge(country) { return el('span', 'badge' + (country === 'BE' ? ' be' : ''), country); }

  function reliabilityCell(p) {
    const r = el('span', 'rel');
    r.append(el('span', 'dot ' + p.level));
    r.append(document.createTextNode(`${LEVELS[p.level]} · ${p.reliability} · ${ago(p.age_hours)}${p.origin === 'manual' ? ' · saisi à la main' : ''}`));
    return r;
  }

  function renderList() {
    const fuelInfo = data.fuels.find((f) => f.key === fuel);
    $('list-title').textContent = fuelInfo ? fuelInfo.label : fuel;
    const withPrice = data.stations.filter((s) => s.prices[fuel] && !(hideLow && s.prices[fuel].level === 'low'));
    const without = data.stations.filter((s) => !s.prices[fuel]);
    withPrice.sort((a, b) => a.prices[fuel].price - b.prices[fuel].price);
    without.sort((a, b) => (a.distance_km ?? 1e9) - (b.distance_km ?? 1e9));

    const ol = $('list');
    ol.replaceChildren();
    for (const s of [...withPrice, ...without]) {
      const p = s.prices[fuel];
      const li = el('li', p ? (p.level === 'low' ? 'low' : '') : 'none');
      if (p && data.best[fuel] === s.id) li.classList.add('best');

      const name = el('div', 'name');
      name.append(badge(s.country), document.createTextNode(s.name));
      if (p && data.best[fuel] === s.id) name.append(el('span', 'tag-best', 'Le moins cher'));
      li.append(name);

      const meta = el('div', 'meta');
      if (s.distance_km != null) meta.append(document.createTextNode(`${nf.format(s.distance_km)} km du Doulieu · `));
      if (p) meta.append(reliabilityCell(p));
      else meta.append(document.createTextNode(s.country === 'BE' && fuel === 'e85' ? 'E85 non vendu en Belgique' : 'Prix non disponible'));
      li.append(meta);

      const price = el('div', 'price');
      if (p) { price.append(document.createTextNode(fmtPrice(p.price)), el('small', '', ' €/L')); }
      else price.textContent = '–';
      li.append(price);
      ol.append(li);
    }
    if (!ol.children.length) ol.append(el('li', 'none', 'Aucune station à afficher.'));
  }

  function renderMatrix() {
    const wrap = $('matrix');
    wrap.replaceChildren();
    const t = el('table');
    const head = el('tr');
    head.append(el('th', '', 'Station'));
    for (const f of data.fuels) head.append(el('th', '', f.label.split(' ')[0]));
    const thead = el('thead');
    thead.append(head);
    t.append(thead);
    const tb = el('tbody');
    for (const s of data.stations) {
      const tr = el('tr');
      const n = el('td');
      n.append(badge(s.country), document.createTextNode(s.name));
      tr.append(n);
      for (const f of data.fuels) {
        const p = s.prices[f.key];
        const td = el('td', p && data.best[f.key] === s.id ? 'best' : '');
        if (p) {
          td.append(el('span', 'dot ' + p.level), document.createTextNode(' ' + fmtPrice(p.price)));
          td.title = `${LEVELS[p.level]} (${p.reliability}) · ${ago(p.age_hours)}`;
        } else td.textContent = '–';
        tr.append(td);
      }
      tb.append(tr);
    }
    t.append(tb);
    wrap.append(t);
  }

  function renderSources() {
    const now = new Date(data.generated_at);
    const box = $('sources');
    box.replaceChildren();
    const notices = [];
    for (const [key, s] of Object.entries(data.sources)) {
      let txt = s.label + ' : ';
      txt += s.last_success ? 'relevé ' + agoDate(s.last_success, now) : 'aucun relevé pour le moment';
      if (s.next_run) txt += ' · prochain passage ' + inDate(s.next_run, now);
      box.append(el('p', '', txt));
      if (!s.ok) notices.push(`La dernière mise à jour de la source « ${s.label} » a échoué : les prix affichés peuvent être anciens.`);
    }
    box.append(el('p', '', 'Pas d\'historique conservé : seuls les derniers prix connus sont affichés.'));
    const n = $('notice');
    n.hidden = notices.length === 0;
    n.textContent = notices.join(' ');
  }

  function render() {
    renderTabs(); renderList(); renderMatrix(); renderSources();
    const t = new Date(data.generated_at);
    $('updated').textContent = 'Autour de ' + data.origin.name + ' · affiché à ' +
      t.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
  }

  async function load() {
    try {
      const res = await fetch(API, { cache: 'no-cache' });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      data = await res.json();
      render();
    } catch {
      const n = $('notice');
      n.hidden = false;
      n.textContent = data ? 'Impossible de rafraîchir : affichage des dernières données reçues.' : 'Impossible de charger les prix.';
      $('updated').textContent = 'Hors ligne';
    }
  }

  document.addEventListener('DOMContentLoaded', () => {
    const cb = $('hide-low');
    cb.checked = hideLow;
    cb.addEventListener('change', () => { hideLow = cb.checked; safeSet('hideLow', hideLow ? '1' : '0'); if (data) renderList(); });
    load();
    setInterval(load, 10 * 60 * 1000);
    document.addEventListener('visibilitychange', () => { if (!document.hidden) load(); });
    if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
  });
})();
