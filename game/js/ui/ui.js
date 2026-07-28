// DOM layer: screens, HUD, garage, leaderboards, settings.
// Everything reads from the game object and calls back into it — no state of
// its own beyond what is being displayed.

import { CHAPTERS, ALL_EVENTS, isEventUnlocked, isChapterUnlocked, evaluate, objectiveText, careerProgress } from '../game/career.js';
import { TRACKS, TRACK_ORDER } from '../world/tracks.js';
import {
  PARTS, PART_CATEGORIES, SETUP_SLIDERS, ASSIST_SLIDERS, findPart,
  computeStats, setupDefaults, BODY_KITS, RIM_STYLES, PAINT_FINISH, VINYLS,
} from '../game/tuning.js';
import { WEATHER } from '../render/environment.js';
import { formatTime, formatScore } from '../game/scoring.js';
import { CAMERA_LABELS, CAMERA_MODES } from '../game/camera.js';
import { clamp, DEG } from '../core/math.js';

const $ = (id) => document.getElementById(id);

const PAINT_SWATCHES = [
  ['Super white II', [0.92, 0.93, 0.95]],
  ['Toning black', [0.045, 0.045, 0.055]],
  ['Dark red mica', [0.42, 0.045, 0.07]],
  ['Silver metallic', [0.62, 0.64, 0.68]],
  ['Champagne gold', [0.72, 0.62, 0.42]],
  ['Midnight purple', [0.22, 0.08, 0.36]],
  ['Bayside blue', [0.06, 0.22, 0.55]],
  ['Kaiser green', [0.08, 0.34, 0.22]],
  ['Sunrise orange', [0.85, 0.32, 0.05]],
  ['Zoku pink', [0.92, 0.22, 0.52]],
  ['Primer grey', [0.28, 0.29, 0.31]],
  ['Chrome', [0.85, 0.87, 0.9]],
];

const RIM_SWATCHES = [
  ['Polished', [0.78, 0.79, 0.83]],
  ['Gunmetal', [0.28, 0.30, 0.34]],
  ['Bronze', [0.55, 0.36, 0.15]],
  ['Gold', [0.82, 0.68, 0.22]],
  ['White', [0.9, 0.9, 0.92]],
  ['Black', [0.07, 0.07, 0.08]],
];

const toHex = (c) => '#' + c.map((v) => Math.round(clamp(v, 0, 1) * 255).toString(16).padStart(2, '0')).join('');

export class UI {
  constructor(game) {
    this.game = game;
    this.screens = {};
    for (const el of document.querySelectorAll('.screen')) this.screens[el.id.replace('screen-', '')] = el;
    this.hud = $('hud');
    this.touch = $('touch');
    this.current = 'loading';
    this.garageTab = 'parts';
    this.boardTrack = 'shibuya';
    this.freeConfig = {
      track: 'shibuya', hour: 22.5, weather: 'clear', timeScale: 0,
      duration: 0, ghost: false,
    };
    this.flashTimer = 0;
    this.bankTimer = 0;
    this.bindStatic();
    this.cacheHud();
  }

  /* -------------------------------------------------------------- screens */

  setScreen(name) {
    for (const [key, el] of Object.entries(this.screens)) {
      el.classList.toggle('is-active', key === name);
    }
    this.current = name;
    document.body.classList.toggle('in-menu', name !== 'none');
  }

  hideScreens() {
    for (const el of Object.values(this.screens)) el.classList.remove('is-active');
    this.current = 'none';
  }

  showHud(show) {
    this.hud.hidden = !show;
    this.touch.hidden = !show || !this.game.useTouchControls;
    document.body.classList.toggle('in-run', show);
  }

  toast(message, ms = 2200) {
    const el = $('toast');
    el.textContent = message;
    el.hidden = false;
    clearTimeout(this._toastTimer);
    this._toastTimer = setTimeout(() => { el.hidden = true; }, ms);
  }

  setLoading(progress, status) {
    $('load-bar').style.width = `${Math.round(progress * 100)}%`;
    if (status) $('load-status').textContent = status;
  }

  showError(title, body) {
    $('error-title').textContent = title;
    $('error-body').textContent = body;
    this.setScreen('error');
  }

  /* --------------------------------------------------------------- static */

  bindStatic() {
    for (const el of document.querySelectorAll('[data-nav]')) {
      el.addEventListener('click', () => this.game.navigate(el.dataset.nav));
    }
    for (const el of document.querySelectorAll('#main-menu .menu__item')) {
      el.addEventListener('click', () => this.game.navigate(el.dataset.action));
    }
    for (const el of this.screens.pause.querySelectorAll('[data-action]')) {
      el.addEventListener('click', () => this.game.pauseAction(el.dataset.action));
    }
    for (const el of this.screens.results.querySelectorAll('[data-action]')) {
      el.addEventListener('click', () => this.game.resultAction(el.dataset.action));
    }
    for (const el of document.querySelectorAll('.garage__tabs .tab')) {
      el.addEventListener('click', () => {
        this.garageTab = el.dataset.tab;
        for (const t of document.querySelectorAll('.garage__tabs .tab')) {
          t.classList.toggle('is-active', t === el);
        }
        this.renderGarage();
      });
    }

    // Free-drift controls.
    const hour = $('free-hour');
    hour.addEventListener('input', () => {
      this.freeConfig.hour = parseFloat(hour.value);
      $('free-hour-label').textContent = hourLabel(this.freeConfig.hour);
      this.game.previewConditions(this.freeConfig);
    });
    const ts = $('free-timescale');
    ts.addEventListener('input', () => {
      this.freeConfig.timeScale = parseFloat(ts.value);
      $('free-timescale-label').textContent = this.freeConfig.timeScale === 0
        ? 'frozen' : `${this.freeConfig.timeScale.toFixed(2)} h/s`;
      this.game.previewConditions(this.freeConfig);
    });
    const dur = $('free-duration');
    dur.addEventListener('input', () => {
      this.freeConfig.duration = parseFloat(dur.value);
      $('free-duration-label').textContent = this.freeConfig.duration === 0
        ? 'no limit' : `${Math.round(this.freeConfig.duration / 60 * 10) / 10} min`;
    });
    $('free-ghost').addEventListener('change', (e) => { this.freeConfig.ghost = e.target.checked; });
    $('free-start').addEventListener('click', () => this.game.startFreeDrift(this.freeConfig));

    $('board-save-endpoint').addEventListener('click', () => {
      const url = $('board-endpoint').value.trim();
      this.game.setEndpoint(url);
    });
    $('board-clear-endpoint').addEventListener('click', () => {
      $('board-endpoint').value = '';
      this.game.setEndpoint('');
    });
    $('board-name').addEventListener('change', (e) => {
      this.game.setDriverName(e.target.value.trim().toUpperCase() || 'NO NAME');
    });
  }

  /* ------------------------------------------------------------------ HUD */

  cacheHud() {
    this.el = {
      score: $('hud-score'), pending: $('hud-pending'), mult: $('hud-mult'),
      bank: $('hud-bank'), timer: $('hud-timer'), goal: $('hud-goal'),
      clock: $('hud-clock'), weather: $('hud-weather'), track: $('hud-track'),
      speed: $('hud-speed'), unit: $('hud-unit'), gear: $('hud-gear'),
      boost: $('hud-boost'), rpm: $('hud-rpm'), tacho: $('tacho-bar'),
      angle: $('hud-angle'), needle: $('angle-needle'), flash: $('hud-flash'),
      music: $('hud-music'), tyres: $('hud-tyres'), knob: $('steer-knob'),
    };
  }

  updateHud(g, dt) {
    const e = this.el;
    const v = g.vehicle;
    const t = v.telemetry;
    const scorer = g.scorer;
    const imperial = g.profile.data.settings.units === 'imperial';

    e.score.textContent = formatScore(scorer.total);
    const pending = Math.round(scorer.pending * scorer.multiplier);
    e.pending.textContent = pending > 0 ? `+${formatScore(pending)}` : '';
    e.mult.textContent = `×${scorer.multiplier.toFixed(1)}`;
    e.mult.classList.toggle('is-hot', scorer.multiplier >= 3);

    const speed = imperial ? t.speedKmh * 0.621371 : t.speedKmh;
    e.speed.textContent = Math.round(Math.max(0, speed));
    e.unit.textContent = imperial ? 'mph' : 'km/h';
    e.gear.textContent = t.gear < 0 ? 'R' : t.gear === 0 ? 'N' : String(t.gear);
    e.rpm.textContent = Math.round(t.rpm);
    e.boost.textContent = t.boost.toFixed(2);
    e.tacho.style.width = `${clamp(t.rpm / v.setup.limiter, 0, 1) * 100}%`;

    const deg = Math.round(Math.abs(t.slipDeg));
    e.angle.textContent = deg;
    e.needle.style.left = `${clamp(50 + (t.slipDeg / 90) * 50, 2, 98)}%`;

    const hottest = Math.max(...t.tireTemp.slice(2));
    const wear = Math.max(...t.tireWear.slice(2));
    e.tyres.innerHTML = `TYRE <b>${Math.round(hottest)}°</b>`;
    e.tyres.classList.toggle('is-hot', hottest > 118 || wear > 0.7);

    e.clock.textContent = g.env.clockString();
    e.weather.textContent = WEATHER[g.env.weatherName].label;
    e.track.textContent = g.track ? g.track.name : '';

    if (g.mode === 'career' && g.event) {
      e.goal.textContent = objectiveText(g.event);
    } else {
      e.goal.textContent = g.lapTimer.bestLap !== null
        ? `Best lap ${formatTime(g.lapTimer.bestLap)}` : 'Free drift';
    }
    e.timer.textContent = g.timeLimit
      ? formatTime(Math.max(0, g.timeLimit - g.runTime))
      : formatTime(g.runTime);

    // Score events surface as flashes.
    while (scorer.events.length) {
      const ev = scorer.events.shift();
      if (ev.type === 'bank') {
        e.bank.textContent = `BANKED +${formatScore(ev.amount)} (×${ev.multiplier.toFixed(1)})`;
        this.bankTimer = 2.2;
      } else if (ev.type === 'fail') {
        this.flash(ev.reason === 'SPUN' ? 'SPUN OUT' : 'CONTACT', false);
        e.bank.textContent = `LOST ${formatScore(ev.lost)}`;
        this.bankTimer = 2.0;
      } else if (ev.type === 'zone') {
        this.flash('CLIPPING ZONE', true);
      } else if (ev.type === 'transition' && ev.multiplier >= 2.5) {
        this.flash(`LINKED ×${ev.multiplier.toFixed(1)}`, true);
      }
    }
    this.bankTimer = Math.max(0, this.bankTimer - dt);
    if (this.bankTimer <= 0) e.bank.textContent = '';

    this.flashTimer = Math.max(0, this.flashTimer - dt);
    e.flash.classList.toggle('is-on', this.flashTimer > 0);

    if (g.audio.music && g.audio.music.playing) {
      e.music.textContent = `♪ ${g.audio.music.currentTrackName}`;
    } else e.music.textContent = '';

    if (this.game.useTouchControls && e.knob) {
      e.knob.style.transform = `translateX(${g.input.state.steer * 42}%)`;
    }
  }

  flash(text, good) {
    this.el.flash.textContent = text;
    this.el.flash.classList.toggle('is-good', !!good);
    this.flashTimer = 1.1;
  }

  /* --------------------------------------------------------------- career */

  renderCareer() {
    const profile = this.game.profile;
    const list = $('career-list');
    list.innerHTML = '';
    const p = careerProgress(profile);
    $('career-progress').textContent = `${p.done}/${p.total} · ${p.stars}★`;

    for (const chapter of CHAPTERS) {
      const wrap = document.createElement('div');
      wrap.className = 'chapter';
      const unlocked = isChapterUnlocked(chapter, profile);
      wrap.innerHTML = `<div class="chapter__head">
        <h3>${chapter.name}</h3><span>${chapter.nameJp}</span>
      </div><p class="muted">${chapter.blurb}</p>`;

      for (const event of chapter.events) {
        const ev = { ...event, chapter: chapter.id };
        const open = unlocked && isEventUnlocked(ev, profile);
        const done = profile.data.completed[event.id];
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = `event${open ? '' : ' is-locked'}${done ? ' is-done' : ''}`;
        btn.innerHTML = `
          <b>${event.name}</b>
          <span class="stars">${done ? '★'.repeat(done.stars) + '☆'.repeat(3 - done.stars) : ''}</span>
          <small>${event.brief}</small>
          <span class="event__goal">${objectiveText(event)} · ${TRACKS[event.track].name}
            · ${WEATHER[event.weather].label} · ${hourLabel(event.hour)}
            · ${Math.round(event.duration / 60)} min · ${formatScore(event.reward)} CR</span>`;
        if (open) btn.addEventListener('click', () => this.game.startCareerEvent(event.id));
        else btn.disabled = true;
        wrap.appendChild(btn);
      }
      list.appendChild(wrap);
    }
  }

  /* ----------------------------------------------------------- free drift */

  renderFree() {
    const grid = $('track-grid');
    grid.innerHTML = '';
    const unlocked = this.game.profile.data.unlockedTracks;
    for (const id of TRACK_ORDER) {
      const t = TRACKS[id];
      const open = unlocked.includes(id);
      const card = document.createElement('button');
      card.type = 'button';
      card.className = `track-card${this.freeConfig.track === id ? ' is-active' : ''}${open ? '' : ' is-locked'}`;
      card.innerHTML = `
        <span class="jp">${t.nameJp}</span>
        <b>${t.name}</b>
        <small>${t.blurb}</small>
        <span class="meta">${'●'.repeat(t.difficulty)}${'○'.repeat(3 - t.difficulty)} ·
          ${open ? 'unlocked' : 'complete the previous chapter'}</span>`;
      if (open) {
        card.addEventListener('click', () => {
          this.freeConfig.track = id;
          this.freeConfig.hour = TRACKS[id].defaultTime;
          this.freeConfig.weather = TRACKS[id].defaultWeather;
          $('free-hour').value = String(this.freeConfig.hour);
          $('free-hour-label').textContent = hourLabel(this.freeConfig.hour);
          this.renderFree();
          this.game.previewTrack(id, this.freeConfig);
        });
      } else card.disabled = true;
      grid.appendChild(card);
    }

    const weather = $('free-weather');
    weather.innerHTML = '';
    for (const [key, w] of Object.entries(WEATHER)) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = `chip-btn${this.freeConfig.weather === key ? ' is-active' : ''}`;
      b.textContent = w.label;
      b.addEventListener('click', () => {
        this.freeConfig.weather = key;
        this.renderFree();
        this.game.previewConditions(this.freeConfig);
      });
      weather.appendChild(b);
    }
    $('free-hour-label').textContent = hourLabel(this.freeConfig.hour);
    $('free-ghost').checked = this.freeConfig.ghost;
  }

  /* --------------------------------------------------------------- garage */

  renderGarage() {
    const game = this.game;
    const build = game.profile.data.build;
    const stats = computeStats(build);
    $('garage-credits').textContent = formatScore(game.profile.credits);

    const s = $('garage-stats');
    s.innerHTML = '';
    const cells = [
      ['Power', stats.power, 'hp @ ' + stats.powerRpm],
      ['Torque', stats.torque, 'Nm @ ' + stats.torqueRpm],
      ['Mass', stats.mass, 'kg'],
      ['P/W', stats.powerToWeight, 'hp/t'],
      ['Balance', stats.balance + '%', 'front'],
      ['Lock', stats.lock + '°', 'steering'],
      ['Boost', stats.boost, 'bar'],
      ['Top', stats.topSpeed, 'km/h'],
    ];
    for (const [label, value, note] of cells) {
      const d = document.createElement('div');
      d.className = 'stat';
      d.innerHTML = `<span>${label}</span><b>${value}</b><i>${note}</i>`;
      s.appendChild(d);
    }

    const panel = $('garage-panel');
    panel.innerHTML = '';
    if (this.garageTab === 'parts') this.renderParts(panel, build);
    else if (this.garageTab === 'setup') this.renderSetup(panel, build);
    else this.renderStyle(panel, build);
  }

  renderParts(panel, build) {
    const game = this.game;
    for (const cat of PART_CATEGORIES) {
      const group = document.createElement('div');
      group.className = 'part-group';
      group.innerHTML = `<h4>${cat.label}</h4>`;
      for (const p of PARTS[cat.key]) {
        const fitted = build.parts[cat.key] === p.id;
        const owned = game.owns(cat.key, p.id);
        const affordable = owned || game.profile.credits >= p.price;
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = `part${fitted ? ' is-fitted' : ''}${affordable ? '' : ' is-locked'}`;
        btn.innerHTML = `<b>${p.name}</b>
          <span class="price">${fitted ? 'FITTED' : owned ? 'OWNED' : formatScore(p.price) + ' CR'}</span>
          ${p.note ? `<small>${p.note}</small>` : ''}`;
        btn.addEventListener('click', () => game.fitPart(cat.key, p.id));
        group.appendChild(btn);
      }
      panel.appendChild(group);
    }
  }

  renderSetup(panel, build) {
    const game = this.game;
    const defaults = setupDefaults(build);
    const tune = build.tune || {};

    const reset = document.createElement('button');
    reset.type = 'button';
    reset.className = 'secondary';
    reset.textContent = 'Reset to part defaults';
    reset.addEventListener('click', () => { game.resetTune(); });
    panel.appendChild(reset);

    const group = document.createElement('div');
    group.className = 'panel';
    group.innerHTML = '<h3>Setup sheet</h3>';
    for (const slider of SETUP_SLIDERS) {
      const value = tune[slider.key] ?? defaults[slider.key];
      const label = document.createElement('label');
      label.className = 'field';
      const fmt = (v) => (slider.step < 1 ? Number(v).toFixed(2) : Math.round(v));
      label.innerHTML = `<span>${slider.label}<b>${fmt(value)}${slider.unit}</b></span>`;
      const input = document.createElement('input');
      input.type = 'range';
      input.min = String(slider.min);
      input.max = String(slider.max);
      input.step = String(slider.step);
      input.value = String(clamp(value, slider.min, slider.max));
      input.addEventListener('input', () => {
        const v = parseFloat(input.value);
        label.querySelector('b').textContent = `${fmt(v)}${slider.unit}`;
        game.setTune(slider.key, v);
      });
      label.appendChild(input);
      if (slider.hint) {
        const hint = document.createElement('p');
        hint.className = 'muted';
        hint.textContent = slider.hint;
        label.appendChild(hint);
      }
      group.appendChild(label);
    }
    panel.appendChild(group);

    const assists = document.createElement('div');
    assists.className = 'panel';
    assists.innerHTML = '<h3>Assists</h3><p class="muted">All of these are off by default. '
      + 'Countersteer assist helps a thumb catch a slide; the other two make the car duller.</p>';
    for (const slider of ASSIST_SLIDERS) {
      const value = build.assists?.[slider.key] ?? 0;
      const label = document.createElement('label');
      label.className = 'field';
      label.innerHTML = `<span>${slider.label}<b>${Math.round(value * 100)}%</b></span>`;
      const input = document.createElement('input');
      input.type = 'range';
      input.min = String(slider.min); input.max = String(slider.max); input.step = String(slider.step);
      input.value = String(value);
      input.addEventListener('input', () => {
        const v = parseFloat(input.value);
        label.querySelector('b').textContent = `${Math.round(v * 100)}%`;
        game.setAssist(slider.key, v);
      });
      label.appendChild(input);
      assists.appendChild(label);
    }
    const auto = document.createElement('label');
    auto.className = 'toggle';
    auto.innerHTML = `<input type="checkbox" ${build.autoShift !== false ? 'checked' : ''}>
      <span>Automatic gearbox</span>`;
    auto.querySelector('input').addEventListener('change', (e) => game.setAutoShift(e.target.checked));
    assists.appendChild(auto);
    panel.appendChild(assists);
  }

  renderStyle(panel, build) {
    const game = this.game;
    const style = build.style;

    const swatchRow = (title, list, current, onPick) => {
      const box = document.createElement('div');
      box.className = 'panel';
      box.innerHTML = `<h3>${title}</h3>`;
      const row = document.createElement('div');
      row.className = 'swatches';
      for (const [name, color] of list) {
        const b = document.createElement('button');
        b.type = 'button';
        b.className = 'swatch' + (sameColor(color, current) ? ' is-active' : '');
        b.style.background = toHex(color);
        b.title = name;
        b.setAttribute('aria-label', name);
        b.addEventListener('click', () => { onPick(color); this.renderGarage(); });
        row.appendChild(b);
      }
      box.appendChild(row);
      return box;
    };

    const chipRow = (title, entries, current, onPick, note) => {
      const box = document.createElement('div');
      box.className = 'panel';
      box.innerHTML = `<h3>${title}</h3>${note ? `<p class="muted">${note}</p>` : ''}`;
      const row = document.createElement('div');
      row.className = 'chips';
      for (const [key, label] of entries) {
        const b = document.createElement('button');
        b.type = 'button';
        b.className = 'chip-btn' + (current === key ? ' is-active' : '');
        b.textContent = label;
        b.addEventListener('click', () => { onPick(key); this.renderGarage(); });
        row.appendChild(b);
      }
      box.appendChild(row);
      return box;
    };

    panel.appendChild(swatchRow('Paint', PAINT_SWATCHES, style.paintColor,
      (c) => game.setStyle('paintColor', c)));
    panel.appendChild(chipRow('Finish',
      Object.entries(PAINT_FINISH).map(([k, v]) => [k, v.label]), style.finish,
      (k) => game.setStyle('finish', k)));
    panel.appendChild(chipRow('Body kit',
      Object.entries(BODY_KITS).map(([k, v]) => [k, v.label]), style.kit,
      (k) => game.setStyle('kit', k),
      'Wide arches widen the track and add drag; wings add rear downforce.'));
    panel.appendChild(chipRow('Wheels',
      Object.entries(RIM_STYLES).map(([k, v]) => [k, v.label]), style.rim,
      (k) => game.setStyle('rim', k)));
    panel.appendChild(swatchRow('Wheel finish', RIM_SWATCHES, style.rimColor,
      (c) => game.setStyle('rimColor', c)));
    panel.appendChild(chipRow('Vinyl',
      Object.entries(VINYLS).map(([k, v]) => [k, v.label]), style.vinyl,
      (k) => game.setStyle('vinyl', k)));
    panel.appendChild(swatchRow('Vinyl colour', PAINT_SWATCHES, style.vinylColor,
      (c) => game.setStyle('vinylColor', c)));
    panel.appendChild(swatchRow('Calipers', RIM_SWATCHES.concat([['Red', [0.72, 0.12, 0.1]]]),
      style.caliperColor, (c) => game.setStyle('caliperColor', c)));

    const extras = document.createElement('div');
    extras.className = 'panel';
    extras.innerHTML = '<h3>Details</h3>';
    const tint = document.createElement('label');
    tint.className = 'field';
    tint.innerHTML = `<span>Window tint<b>${Math.round(style.tint * 100)}%</b></span>`;
    const tintInput = document.createElement('input');
    tintInput.type = 'range'; tintInput.min = '0'; tintInput.max = '0.95'; tintInput.step = '0.05';
    tintInput.value = String(style.tint);
    tintInput.addEventListener('input', () => {
      tint.querySelector('b').textContent = `${Math.round(parseFloat(tintInput.value) * 100)}%`;
      game.setStyle('tint', parseFloat(tintInput.value));
    });
    tint.appendChild(tintInput);
    extras.appendChild(tint);

    for (const [key, label] of [['cage', 'Roll cage'], ['wingCarbon', 'Carbon wing']]) {
      const t = document.createElement('label');
      t.className = 'toggle';
      t.innerHTML = `<input type="checkbox" ${style[key] ? 'checked' : ''}><span>${label}</span>`;
      t.querySelector('input').addEventListener('change', (e) => {
        game.setStyle(key, e.target.checked);
        this.renderGarage();
      });
      extras.appendChild(t);
    }
    panel.appendChild(extras);
  }

  /* ---------------------------------------------------------- leaderboard */

  async renderBoards() {
    const game = this.game;
    const chips = $('board-tracks');
    chips.innerHTML = '';
    for (const id of TRACK_ORDER) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'chip-btn' + (this.boardTrack === id ? ' is-active' : '');
      b.textContent = TRACKS[id].name;
      b.addEventListener('click', () => { this.boardTrack = id; this.renderBoards(); });
      chips.appendChild(b);
    }
    $('board-name').value = game.profile.data.name;
    $('board-endpoint').value = game.leaderboard.remote?.endpoint || '';

    const list = $('board-list');
    list.innerHTML = '<li class="muted">Loading…</li>';
    const { source, entries } = await game.leaderboard.top(this.boardTrack, 'free', 20);
    $('board-source').textContent = source === 'online' ? 'Online'
      : source === 'offline-fallback' ? 'Offline (server unreachable)' : 'Offline — this device + rivals';
    list.innerHTML = '';
    if (!entries.length) {
      list.innerHTML = '<li class="muted">No runs recorded yet.</li>';
      return;
    }
    entries.forEach((entry, i) => {
      const li = document.createElement('li');
      if (entry.local && entry.name === game.profile.data.name) li.classList.add('is-me');
      li.innerHTML = `<span class="rank">${String(i + 1).padStart(2, '0')}</span>
        <span>${escapeHtml(entry.name)}
          <span class="tagline">${entry.rival ? 'offline rival' : entry.local ? 'this device' : 'online'}
          ${entry.angle ? ` · ${entry.angle}° avg` : ''}</span></span>
        <span class="score">${formatScore(entry.score)}</span>`;
      list.appendChild(li);
    });
  }

  /* -------------------------------------------------------------- settings */

  renderSettings() {
    const game = this.game;
    const s = game.profile.data.settings;
    const panel = $('settings-panel');
    panel.innerHTML = '';

    const section = (title) => {
      const el = document.createElement('div');
      el.className = 'panel';
      el.innerHTML = `<h3>${title}</h3>`;
      panel.appendChild(el);
      return el;
    };

    const chips = (parent, entries, current, onPick) => {
      const row = document.createElement('div');
      row.className = 'chips';
      for (const [key, label] of entries) {
        const b = document.createElement('button');
        b.type = 'button';
        b.className = 'chip-btn' + (current === key ? ' is-active' : '');
        b.textContent = label;
        b.addEventListener('click', () => { onPick(key); this.renderSettings(); });
        row.appendChild(b);
      }
      parent.appendChild(row);
    };

    const slider = (parent, label, value, min, max, step, onInput, fmt = (v) => Math.round(v * 100) + '%') => {
      const l = document.createElement('label');
      l.className = 'field';
      l.innerHTML = `<span>${label}<b>${fmt(value)}</b></span>`;
      const input = document.createElement('input');
      input.type = 'range';
      input.min = String(min); input.max = String(max); input.step = String(step);
      input.value = String(value);
      input.addEventListener('input', () => {
        const v = parseFloat(input.value);
        l.querySelector('b').textContent = fmt(v);
        onInput(v);
      });
      l.appendChild(input);
      parent.appendChild(l);
    };

    const controls = section('Controls');
    chips(controls, [['touch', 'Touch pad'], ['tilt', 'Tilt']], s.steerMode,
      (k) => game.setSteerMode(k));
    slider(controls, 'Steering sensitivity', s.sensitivity, 0.5, 1.6, 0.05,
      (v) => game.setSetting('sensitivity', v, () => { game.input.sensitivity = v; }),
      (v) => v.toFixed(2) + '×');
    const calib = document.createElement('button');
    calib.type = 'button';
    calib.className = 'secondary';
    calib.textContent = 'Calibrate tilt (hold the phone how you drive)';
    calib.addEventListener('click', () => game.calibrateTilt());
    controls.appendChild(calib);
    const vib = document.createElement('label');
    vib.className = 'toggle';
    vib.innerHTML = `<input type="checkbox" ${s.vibrate ? 'checked' : ''}><span>Haptics</span>`;
    vib.querySelector('input').addEventListener('change', (e) => {
      game.setSetting('vibrate', e.target.checked, () => { game.input.vibrate = e.target.checked; });
    });
    controls.appendChild(vib);

    const camera = section('Camera');
    chips(camera, CAMERA_MODES.map((m) => [m, CAMERA_LABELS[m]]), s.cameraMode,
      (k) => game.setSetting('cameraMode', k, () => game.camera.setMode(k)));

    const video = section('Graphics');
    chips(video, [['low', 'Low'], ['medium', 'Medium'], ['high', 'High']], s.quality,
      (k) => game.setQuality(k));
    const stats = document.createElement('p');
    stats.className = 'muted';
    stats.id = 'settings-perf';
    stats.textContent = 'Lower quality drops the render scale, shadow size and draw distance.';
    video.appendChild(stats);

    const audio = section('Audio');
    slider(audio, 'Master', s.masterVolume, 0, 1, 0.05, (v) => game.setSetting('masterVolume', v, () => game.audio.setMaster(v)));
    slider(audio, 'Engine & tires', s.sfxVolume, 0, 1, 0.05, (v) => game.setSetting('sfxVolume', v, () => game.audio.setSfx(v)));
    slider(audio, 'Music', s.musicVolume, 0, 1, 0.05, (v) => game.setSetting('musicVolume', v, () => game.audio.setMusic(v)));
    const trackBtn = document.createElement('button');
    trackBtn.type = 'button';
    trackBtn.className = 'secondary';
    trackBtn.textContent = 'Next track';
    trackBtn.addEventListener('click', () => game.nextMusicTrack());
    audio.appendChild(trackBtn);

    const display = section('Display');
    chips(display, [['metric', 'km/h'], ['imperial', 'mph']], s.units,
      (k) => game.setSetting('units', k));
    chips(display, [['right', 'HUD right'], ['left', 'HUD left']], s.hudSide,
      (k) => game.setSetting('hudSide', k, () => document.body.classList.toggle('hud-left', k === 'left')));

    const data = section('Profile');
    const info = document.createElement('p');
    info.className = 'muted';
    const st = game.profile.data.stats;
    const lv = game.profile.levelProgress();
    info.innerHTML = `Level ${lv.level} · ${lv.current}/${lv.needed} rep<br>
      ${formatScore(st.runs)} runs · ${formatScore(st.distance / 1000)} km driven ·
      best single drift ${formatScore(st.bestDrift)}`;
    data.appendChild(info);
    const wipe = document.createElement('button');
    wipe.type = 'button';
    wipe.className = 'secondary';
    wipe.textContent = 'Erase all progress';
    wipe.addEventListener('click', () => {
      if (confirm('Erase the profile, garage and every leaderboard entry on this device?')) {
        game.wipeProfile();
      }
    });
    data.appendChild(wipe);
  }

  /* --------------------------------------------------------------- results */

  showResults(result) {
    $('result-kicker').textContent = result.kicker;
    $('result-title').textContent = result.title;
    $('result-score').textContent = formatScore(result.score);
    $('result-stars').textContent = result.stars !== undefined
      ? '★'.repeat(result.stars) + '☆'.repeat(3 - result.stars) : '';
    const grid = $('result-grid');
    grid.innerHTML = '';
    for (const [label, value, note] of result.cells) {
      const d = document.createElement('div');
      d.className = 'stat';
      d.innerHTML = `<span>${label}</span><b>${value}</b><i>${note || ''}</i>`;
      grid.appendChild(d);
    }
    this.setScreen('results');
  }

  renderPauseStats(g) {
    const st = $('pause-stats');
    st.innerHTML = `
      <div>${g.track ? g.track.name : ''} · ${g.env.clockString()} · ${WEATHER[g.env.weatherName].label}</div>
      <div>Score ${formatScore(g.scorer.total)} · best link ${formatScore(g.scorer.bestSingle)}</div>
      <div>Camera ${CAMERA_LABELS[g.camera.mode]}</div>`;
  }

  updateTitle() {
    const p = this.game.profile;
    const lv = p.levelProgress();
    $('title-profile').textContent = `${p.data.name} · LV${lv.level} · ${formatScore(p.credits)} CR`;
    const stats = computeStats(p.data.build);
    $('title-build').textContent = `${stats.power} hp · ${stats.mass} kg · ${stats.lock}° lock`;
  }
}

function sameColor(a, b) {
  if (!a || !b) return false;
  return Math.abs(a[0] - b[0]) < 0.01 && Math.abs(a[1] - b[1]) < 0.01 && Math.abs(a[2] - b[2]) < 0.01;
}

function hourLabel(h) {
  const hh = Math.floor(h);
  const mm = Math.round((h - hh) * 60);
  return `${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}`;
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}
