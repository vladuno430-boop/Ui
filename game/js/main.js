// Entry point: boots the renderer, owns the game loop and the run lifecycle.

import { GLContext } from './core/gl.js';
import { Renderer } from './render/renderer.js';
import { Environment, WEATHER } from './render/environment.js';
import { SmokeSystem, TireMarks, RainSystem } from './render/effects.js';
import { Vehicle } from './physics/vehicle.js';
import { Track } from './world/track.js';
import { TRACKS, getTrack } from './world/tracks.js';
import { Camera } from './game/camera.js';
import { InputManager } from './game/input.js';
import { DriftScorer, LapTimer, formatTime, formatScore } from './game/scoring.js';
import { Profile } from './game/save.js';
import { buildSetup, visualConfig, computeStats, findPart, PART_CATEGORIES, setupDefaults } from './game/tuning.js';
import { getEvent, evaluate, objectiveText } from './game/career.js';
import { Leaderboard, GhostRecorder, GhostPlayer, saveGhost, loadGhost } from './game/leaderboard.js';
import { AudioHub } from './audio/audio.js';
import { UI } from './ui/ui.js';
import { clamp, lerp, DEG, vec3 } from './core/math.js';

class Game {
  constructor() {
    this.canvas = document.getElementById('gl');
    this.profile = new Profile();
    this.ui = new UI(this);
    this.leaderboard = new Leaderboard();
    this.audio = new AudioHub();
    this.env = new Environment({ hour: 22.5, weather: 'clear' });
    this.camera = new Camera();
    this.mode = 'boot';          // boot | menu | run | paused
    this.runMode = 'free';       // free | career
    this.track = null;
    this.trackCache = new Map();
    this.event = null;
    this.runTime = 0;
    this.timeLimit = 0;
    this.time = 0;
    this.lastFrame = 0;
    this.accumulatedDistance = 0;
    this.driftDistance = 0;
    this.zonesCleared = 0;
    this.paused = false;
    this.ghostPlayer = null;
    this.ghostRecorder = new GhostRecorder();
    this.recordGhost = true;
    this.owned = loadOwned();
    this.dpr = 1;
    this.frameTimes = [];
    this.autoQualityChecked = 0;

    this.useTouchControls = matchMedia('(pointer: coarse)').matches
      || navigator.maxTouchPoints > 0;

    this.surfaceProxy = { grip: 1, roll: 0.014, name: 'asphalt' };
    this.world = {
      sample: (x, z) => {
        const s = this.track.sample(x, z);
        const base = s.surface;
        this.surfaceProxy.grip = base.grip * this.env.gripMultiplier;
        this.surfaceProxy.roll = base.roll * (1 + this.env.wetness * 0.25);
        this.surfaceProxy.name = base.name;
        s.surface = this.surfaceProxy;
        return s;
      },
    };
  }

  /* ----------------------------------------------------------------- boot */

  async boot() {
    const ui = this.ui;
    ui.setLoading(0.05, 'Creating context');
    try {
      this.ctx = new GLContext(this.canvas, { antialias: false });
    } catch (err) {
      ui.showError('WebGL 2 not available',
        'This device or browser cannot create a WebGL 2 context. On Android, Chrome 58+ '
        + 'or any modern WebView will work; check that hardware acceleration is enabled.');
      return;
    }

    try {
      ui.setLoading(0.15, 'Compiling shaders');
      await frame();
      this.renderer = new Renderer(this.ctx, this.profile.data.settings.quality);
    } catch (err) {
      console.error(err);
      ui.showError('Shader compilation failed', String(err.message || err));
      return;
    }

    ui.setLoading(0.3, 'Building the car');
    await frame();
    this.vehicle = new Vehicle(buildSetup(this.profile.data.build));
    this.vehicle.headlights = true;
    this.renderer.buildCar(visualConfig(this.profile.data.build));

    ui.setLoading(0.5, 'Laying tarmac');
    await frame();
    await this.loadTrack(this.profile.data.unlockedTracks.includes('shibuya') ? 'shibuya' : 'shibuya');

    ui.setLoading(0.75, 'Lighting the neon');
    await frame();
    this.smoke = new SmokeSystem(this.ctx);
    this.marks = new TireMarks(this.ctx, 30);
    this.rain = new RainSystem(this.ctx);
    this.scorer = new DriftScorer(this.track);
    this.lapTimer = new LapTimer(this.track);

    ui.setLoading(0.9, 'Wiring controls');
    await frame();
    this.setupInput();
    this.applySettings();
    this.resize();
    window.addEventListener('resize', () => this.resize());
    window.addEventListener('orientationchange', () => setTimeout(() => this.resize(), 150));
    document.addEventListener('visibilitychange', () => {
      if (document.hidden && this.mode === 'run') this.pause(true);
    });
    this.addRotateHint();

    ui.setLoading(1, 'Ready');
    await frame();
    this.mode = 'menu';
    this.ui.setScreen('title');
    this.ui.updateTitle();
    this.placeForGarage();
    this.lastFrame = performance.now();
    requestAnimationFrame((t) => this.loop(t));

    // Audio needs a gesture; arm it on the first interaction anywhere.
    const unlock = async () => {
      await this.audio.resume();
      this.audio.setMaster(this.profile.data.settings.masterVolume);
      this.audio.setSfx(this.profile.data.settings.sfxVolume);
      this.audio.setMusic(this.profile.data.settings.musicVolume);
      if (!this.audio.music.playing) this.audio.music.start();
      window.removeEventListener('pointerdown', unlock);
      window.removeEventListener('keydown', unlock);
    };
    window.addEventListener('pointerdown', unlock);
    window.addEventListener('keydown', unlock);
  }

  addRotateHint() {
    const el = document.createElement('div');
    el.className = 'rotate-hint';
    el.innerHTML = '<span>ROTATE TO LANDSCAPE</span>';
    document.getElementById('app').appendChild(el);
    const check = () => {
      document.body.classList.toggle('is-portrait',
        window.innerHeight > window.innerWidth && this.useTouchControls);
    };
    check();
    window.addEventListener('resize', check);
  }

  setupInput() {
    const s = this.profile.data.settings;
    this.input = new InputManager(document.body, {
      steerMode: s.steerMode, sensitivity: s.sensitivity, vibrate: s.vibrate,
    });
    this.input.bindTouch({
      steer: 'ctrl-steer', throttle: 'ctrl-throttle', brake: 'ctrl-brake',
      handbrake: 'ctrl-handbrake', clutch: 'ctrl-clutch',
      shiftUp: 'ctrl-up', shiftDown: 'ctrl-down',
      camera: 'ctrl-camera', reset: 'ctrl-reset', pause: 'ctrl-pause',
    });
    this.input.on('camera', () => {
      const mode = this.camera.cycle(1);
      this.profile.setSetting('cameraMode', mode);
      this.ui.toast(`Camera: ${mode}`);
    });
    this.input.on('reset', () => this.resetCar());
    this.input.on('pause', () => {
      if (this.mode === 'run') this.pause(true);
      else if (this.mode === 'paused') this.pause(false);
    });
    this.input.on('headlights', () => {
      this.vehicle.headlights = !this.vehicle.headlights;
    });
  }

  applySettings() {
    const s = this.profile.data.settings;
    document.body.classList.toggle('hud-left', s.hudSide === 'left');
    this.camera.setMode(s.cameraMode || 'chase');
    if (this.input) {
      this.input.steerMode = s.steerMode;
      this.input.sensitivity = s.sensitivity;
      this.input.vibrate = s.vibrate;
    }
  }

  resize() {
    const w = window.innerWidth;
    const h = window.innerHeight;
    // Cap the device pixel ratio: a 3x phone screen is not worth the fill rate.
    this.dpr = Math.min(window.devicePixelRatio || 1, this.profile.data.settings.quality === 'high' ? 2 : 1.5);
    this.ctx.resize(w, h, this.dpr);
    this.renderer.resize(w * this.dpr, h * this.dpr);
  }

  /* ---------------------------------------------------------------- track */

  async loadTrack(id) {
    if (this.trackCache.has(id)) {
      this.track = this.trackCache.get(id);
    } else {
      await frame();
      this.track = new Track(getTrack(id), this.ctx);
      this.trackCache.set(id, this.track);
      // Three tracks fit comfortably; beyond that, evict the oldest.
      if (this.trackCache.size > 3) {
        const oldest = this.trackCache.keys().next().value;
        if (oldest !== id) {
          this.trackCache.get(oldest).dispose();
          this.trackCache.delete(oldest);
        }
      }
    }
    this.trackId = id;
    if (this.scorer) this.scorer.track = this.track;
    if (this.lapTimer) this.lapTimer.track = this.track;
    if (this.marks) this.marks.clear();
    if (this.smoke) this.smoke.clear();
    return this.track;
  }

  placeForGarage() {
    const pose = this.track.startPose(20);
    this.vehicle.reset(pose.x, pose.z, pose.yaw, pose.y);
    this.vehicle.headlights = true;
    this.garageCentre = [pose.x, pose.y + 0.6, pose.z];
    this.camera.orbitAngle = 2.2;
  }

  /* ------------------------------------------------------------ lifecycle */

  navigate(target) {
    switch (target) {
      case 'title':
        this.mode = 'menu';
        this.ui.setScreen('title');
        this.ui.updateTitle();
        this.ui.showHud(false);
        break;
      case 'career':
        this.ui.renderCareer();
        this.ui.setScreen('career');
        break;
      case 'free':
        this.ui.renderFree();
        this.ui.setScreen('free');
        this.previewConditions(this.ui.freeConfig);
        break;
      case 'garage':
        this.ui.renderGarage();
        this.ui.setScreen('garage');
        this.mode = 'menu';
        this.placeForGarage();
        break;
      case 'boards':
        this.ui.renderBoards();
        this.ui.setScreen('boards');
        break;
      case 'settings':
        this.ui.renderSettings();
        this.ui.setScreen('settings');
        break;
      default:
        break;
    }
  }

  previewConditions(cfg) {
    this.env.setHour(cfg.hour);
    this.env.setWeather(cfg.weather, true);
    this.env.timeScale = 0;
    this.env.update(0);
  }

  async previewTrack(id, cfg) {
    await this.loadTrack(id);
    this.placeForGarage();
    this.previewConditions(cfg);
  }

  async startFreeDrift(cfg) {
    await this.loadTrack(cfg.track);
    this.runMode = 'free';
    this.event = null;
    this.timeLimit = cfg.duration || 0;
    this.env.setHour(cfg.hour);
    this.env.setWeather(cfg.weather, true);
    this.env.timeScale = cfg.timeScale || 0;
    const ghostData = cfg.ghost ? loadGhost(cfg.track, 'free') : null;
    this.ghostPlayer = ghostData ? new GhostPlayer(ghostData) : null;
    this.beginRun();
  }

  async startCareerEvent(eventId) {
    const event = getEvent(eventId);
    if (!event) return;
    await this.loadTrack(event.track);
    this.runMode = 'career';
    this.event = event;
    this.timeLimit = event.duration;
    this.env.setHour(event.hour);
    this.env.setWeather(event.weather, true);
    this.env.timeScale = event.timeScale || 0;
    this.ghostPlayer = null;
    this.beginRun();
  }

  beginRun() {
    const pose = this.track.startPose(6);
    this.vehicle.applySetup(buildSetup(this.profile.data.build));
    this.vehicle.reset(pose.x, pose.z, pose.yaw, pose.y);
    this.vehicle.headlights = this.env.hour > 17.5 || this.env.hour < 6 || this.env.cloudCover > 0.6;
    this.renderer.buildCar(visualConfig(this.profile.data.build));
    this.scorer.reset();
    this.lapTimer.reset();
    this.marks.clear();
    this.smoke.clear();
    this.ghostRecorder.reset();
    if (this.ghostPlayer) this.ghostPlayer.reset();
    this.runTime = 0;
    this.driftDistance = 0;
    this.accumulatedDistance = 0;
    this.zonesCleared = 0;
    this.mode = 'run';
    this.paused = false;
    this.ui.hideScreens();
    this.ui.showHud(true);
    this.input.enabled = true;
    this.audio.resume();
    if (this.audio.music && !this.audio.music.playing) this.audio.music.start();
  }

  pause(on) {
    if (on && this.mode === 'run') {
      this.mode = 'paused';
      this.input.enabled = false;
      this.ui.renderPauseStats(this);
      this.ui.setScreen('pause');
    } else if (!on && this.mode === 'paused') {
      this.mode = 'run';
      this.input.enabled = true;
      this.ui.hideScreens();
      this.lastFrame = performance.now();
    }
  }

  pauseAction(action) {
    switch (action) {
      case 'resume': this.pause(false); break;
      case 'restart': this.beginRun(); break;
      case 'camera': {
        const mode = this.camera.cycle(1);
        this.profile.setSetting('cameraMode', mode);
        this.ui.renderPauseStats(this);
        break;
      }
      case 'quit':
        this.mode = 'menu';
        this.ui.showHud(false);
        this.placeForGarage();
        this.navigate('title');
        break;
    }
  }

  resultAction(action) {
    switch (action) {
      case 'again': this.beginRun(); break;
      case 'garage': this.mode = 'menu'; this.ui.showHud(false); this.placeForGarage(); this.navigate('garage'); break;
      default: this.mode = 'menu'; this.ui.showHud(false); this.placeForGarage(); this.navigate('title'); break;
    }
  }

  async finishRun(reason = 'time') {
    if (this.mode !== 'run') return;
    this.scorer.bank();
    this.mode = 'menu';
    this.input.enabled = false;
    this.ui.showHud(false);

    const scorer = this.scorer;
    const run = {
      score: scorer.total,
      bestSingle: scorer.bestSingle,
      zonesCleared: this.zonesCleared,
      distance: this.accumulatedDistance,
      driftDistance: this.driftDistance,
      time: this.runTime,
      peakAngle: scorer.peakAngle / DEG,
      peakSpeed: scorer.peakSpeed * 3.6,
      grade: scorer.grade,
    };

    const cells = [
      ['Grade', run.grade, ''],
      ['Best link', formatScore(run.bestSingle), 'points'],
      ['Peak angle', Math.round(run.peakAngle) + '°', ''],
      ['Peak speed', Math.round(run.peakSpeed), 'km/h'],
      ['Drift distance', Math.round(run.driftDistance), 'm'],
      ['Zones', String(this.zonesCleared), 'cleared'],
    ];
    if (this.lapTimer.bestLap !== null) {
      cells.push(['Best lap', formatTime(this.lapTimer.bestLap), '']);
    }

    let result;
    if (this.runMode === 'career' && this.event) {
      const outcome = evaluate(this.event, run);
      this.profile.addCredits(outcome.reward);
      this.profile.addRep(outcome.rep);
      if (outcome.passed) {
        this.profile.completeEvent(this.event.id, { stars: outcome.stars, score: run.score });
        if (this.event.unlocks) {
          this.profile.unlockTrack(this.event.unlocks);
          this.ui.toast(`${TRACKS[this.event.unlocks].name} unlocked`);
        }
      }
      cells.unshift(['Target', formatScore(outcome.target), objectiveLabel(this.event)]);
      cells.push(['Reward', formatScore(outcome.reward), 'CR']);
      result = {
        kicker: outcome.passed ? 'Event cleared' : 'Target missed',
        title: this.event.name,
        score: run.score,
        stars: outcome.stars,
        cells,
      };
    } else {
      this.profile.addCredits(Math.round(run.score / 22));
      this.profile.addRep(Math.round(run.score / 60));
      cells.push(['Earned', formatScore(Math.round(run.score / 22)), 'CR']);
      result = {
        kicker: reason === 'time' ? 'Session over' : 'Run complete',
        title: this.track.name,
        score: run.score,
        cells,
      };
    }

    const improved = this.profile.recordRun(this.trackId, this.runMode, run);
    if (improved && this.recordGhost && this.ghostRecorder.frames.length > 20) {
      saveGhost(this.trackId, this.runMode, {
        ...this.ghostRecorder.serialize({ score: run.score, date: Date.now() }),
      });
    }
    if (run.score > 0) {
      await this.leaderboard.submit({
        track: this.trackId,
        mode: this.runMode,
        name: this.profile.data.name,
        score: run.score,
        angle: Math.round(run.peakAngle),
        car: 'MARK II X71',
        build: buildSummary(this.profile.data.build),
      });
    }

    this.ui.showResults(result);
    this.placeForGarage();
  }

  resetCar() {
    if (!this.track) return;
    const info = this.track.sample(this.vehicle.pos[0], this.vehicle.pos[2]);
    const seg = this.track.segments[info.segment];
    this.vehicle.reset(seg.p[0], seg.p[2], Math.atan2(seg.tangent[0], seg.tangent[2]), seg.p[1]);
    this.scorer.fail('RESET');
    this.ui.toast('Car reset');
  }

  /* -------------------------------------------------------------- garage */

  owns(category, id) {
    const part = findPart(category, id);
    if (!part || part.price === 0) return true;
    return this.owned.has(id);
  }

  fitPart(category, id) {
    const part = findPart(category, id);
    if (!part) return;
    if (!this.owns(category, id)) {
      if (!this.profile.spend(part.price)) {
        this.ui.toast(`Not enough credits — need ${formatScore(part.price)} CR`);
        return;
      }
      this.owned.add(id);
      saveOwned(this.owned);
      this.ui.toast(`${part.name} purchased`);
    }
    const build = this.profile.data.build;
    build.parts[category] = id;
    // Setup values that came from the old part have to follow the new one.
    const defaults = setupDefaults(build);
    for (const key of Object.keys(build.tune)) {
      if (defaults[key] === undefined) delete build.tune[key];
    }
    this.profile.save();
    this.refreshCar();
    this.ui.renderGarage();
  }

  setTune(key, value) {
    this.profile.data.build.tune[key] = value;
    this.profile.save();
    this.refreshCar(true);
  }

  resetTune() {
    this.profile.data.build.tune = {};
    this.profile.save();
    this.refreshCar();
    this.ui.renderGarage();
    this.ui.toast('Setup reset');
  }

  setAssist(key, value) {
    this.profile.data.build.assists[key] = value;
    this.profile.save();
    this.refreshCar(true);
  }

  setAutoShift(on) {
    this.profile.data.build.autoShift = on;
    this.profile.save();
    this.refreshCar(true);
  }

  setStyle(key, value) {
    this.profile.data.build.style[key] = value;
    this.profile.save();
    this.refreshCar();
  }

  refreshCar(physicsOnly = false) {
    const build = this.profile.data.build;
    this.vehicle.applySetup(buildSetup(build));
    if (!physicsOnly) this.renderer.buildCar(visualConfig(build));
  }

  /* ------------------------------------------------------------ settings */

  setSetting(key, value, after) {
    this.profile.setSetting(key, value);
    if (after) after();
    if (key === 'hudSide') document.body.classList.toggle('hud-left', value === 'left');
  }

  setQuality(name) {
    this.profile.setSetting('quality', name);
    this.renderer.setQuality(name);
    this.resize();
    this.ui.renderSettings();
  }

  async setSteerMode(mode) {
    if (mode === 'tilt') {
      const ok = await this.input.requestTiltPermission();
      if (!ok) { this.ui.toast('Motion access denied'); return; }
      this.input.calibrateTilt();
    }
    this.profile.setSetting('steerMode', mode);
    this.input.steerMode = mode;
    this.ui.renderSettings();
  }

  calibrateTilt() {
    this.input.calibrateTilt();
    this.ui.toast('Tilt zero set');
  }

  nextMusicTrack() {
    if (!this.audio.music) return;
    const name = this.audio.music.next();
    this.ui.toast(`♪ ${name}`);
  }

  setEndpoint(url) {
    this.leaderboard.setEndpoint(url);
    this.ui.toast(url ? 'Online endpoint set' : 'Back to offline boards');
    this.ui.renderBoards();
  }

  setDriverName(name) {
    this.profile.data.name = name;
    this.profile.save();
    this.ui.updateTitle();
  }

  wipeProfile() {
    this.profile.reset();
    this.owned = new Set();
    saveOwned(this.owned);
    localStorage.removeItem('markii-drift:boards:v1');
    localStorage.removeItem('markii-drift:ghosts:v1');
    this.refreshCar();
    this.applySettings();
    this.ui.renderSettings();
    this.ui.updateTitle();
    this.ui.toast('Profile erased');
  }

  /* ------------------------------------------------------------ the loop */

  loop(now) {
    requestAnimationFrame((t) => this.loop(t));
    let dt = (now - this.lastFrame) / 1000;
    this.lastFrame = now;
    if (!isFinite(dt) || dt <= 0) return;
    dt = Math.min(dt, 0.1);
    this.time += dt;

    this.env.update(dt);

    if (this.mode === 'run') this.updateRun(dt);
    else this.updateMenu(dt);

    this.draw(dt);
    this.trackPerformance(dt);
  }

  updateMenu(dt) {
    // Idle the car in the garage: engine running, wheels still.
    const input = { throttle: 0, brake: 0, steer: 0, handbrake: 1, clutch: 1, shiftUp: false, shiftDown: false };
    this.vehicle.update(dt, input, this.world);
    const centre = this.garageCentre || this.vehicle.pos;
    // On a wide screen the garage panel covers the right of the view, so slide
    // the turntable across to keep the car clear of it.
    const inGarage = this.ui.current === 'garage';
    const lateral = inGarage && window.innerWidth >= 700 ? 2.5 : 0;
    this.camera.orbit(dt, centre, inGarage ? 8.6 : 7.2, 2.15, 0.16, lateral);
    this.updateGarageLights(centre, inGarage);
    if (this.audio.ready) this.audio.car.update(this.vehicle.telemetry, dt, this.env);
    this.smoke.update(dt, this.env.wind);
  }

  /** Two rim lights on the turntable, so the paint reads at night. */
  updateGarageLights(centre, on) {
    if (!on) { this.garageLights = null; return; }
    const a = this.camera.orbitAngle;
    const key = [centre[0] + Math.sin(a + 2.2) * 4.2, centre[1] + 2.6, centre[2] + Math.cos(a + 2.2) * 4.2];
    const fill = [centre[0] + Math.sin(a - 2.0) * 4.6, centre[1] + 2.0, centre[2] + Math.cos(a - 2.0) * 4.6];
    this.garageLights = [
      { pos: key, color: [1.0, 0.28, 0.55], radius: 11, intensity: 3.2 },
      { pos: fill, color: [0.25, 0.85, 1.0], radius: 11, intensity: 2.6 },
      { pos: [centre[0], centre[1] + 4.2, centre[2]], color: [1.0, 0.95, 0.9], radius: 9, intensity: 2.0 },
    ];
  }

  updateRun(dt) {
    const input = this.input.sample(dt);
    const v = this.vehicle;
    const beforeGear = v.gearIndex;

    v.update(dt, input, this.world);
    this.input.endFrame();

    const impact = this.track.collide(v);
    if (impact > 0.02) {
      this.camera.addShake(impact * 1.6);
      if (this.audio.ready) this.audio.car.impact(impact);
      if (this.input.vibrate && navigator.vibrate) navigator.vibrate(Math.round(impact * 60));
    }
    if (v.gearIndex !== beforeGear && this.audio.ready) this.audio.car.shift();
    if (v.backfire > 0.35 && this.audio.ready && Math.random() < 0.25) {
      this.audio.car.backfire(v.backfire);
      if (v.boost > 0.3) this.audio.car.blowOff(clamp(v.boost, 0, 1));
    }

    const info = this.track.sample(v.pos[0], v.pos[2]);
    const zonesBefore = this.scorer.zoneHits.size;
    this.scorer.update(dt, v, info, impact);
    if (this.scorer.zoneHits.size > zonesBefore) this.zonesCleared++;

    this.accumulatedDistance += v.speed * dt;
    if (Math.abs(v.slipAngle) > 12 * DEG && v.speed > 8) this.driftDistance += v.speed * dt;

    this.lapTimer.update(dt, info.progress);
    this.emitEffects(dt, info);

    if (this.recordGhost) this.ghostRecorder.update(dt, v);
    if (this.ghostPlayer) this.ghostPlayer.update(dt);

    this.camera.update(dt, v, this.track);
    if (this.audio.ready) {
      this.audio.car.update(v.telemetry, dt, this.env);
      // Duck the music a little when the engine is loud.
      this.audio.music.duck = lerp(1, 0.72, clamp(v.telemetry.rpm / 7000, 0, 1));
    }

    this.runTime += dt;
    this.profile.data.stats.playTime += dt;
    if (this.timeLimit && this.runTime >= this.timeLimit) {
      this.finishRun('time');
      return;
    }
    this.ui.updateHud(this, dt);
  }

  emitEffects(dt, info) {
    const v = this.vehicle;
    const wetness = this.env.wetness;
    const worldVel = v.worldVelocity();
    for (let i = 0; i < 4; i++) {
      const w = v.wheels[i];
      if (w.load <= 1) continue;
      const slip = w.tire.slipSpeed;
      // Below a real scrub speed the tire is gripping, not smoking.
      if (slip < 3.4) continue;
      const rate = clamp((slip - 3.4) / 15, 0, 1);
      const heat = clamp((w.tire.temp - 70) / 90, 0, 1);
      const intensity = clamp(rate * (0.55 + heat * 0.75), 0, 1);
      const contact = [w.worldPos[0], w.worldPos[1] - w.radius, w.worldPos[2]];

      if (intensity > 0.12) {
        const tint = this.smokeTint(contact);
        const dir = [-worldVel[0] * 0.12, 0, -worldVel[2] * 0.12];
        this.smoke.emit(contact, intensity, dir, tint, dt, wetness);
      }

      // Rubber only goes down on hard surfaces and only when actually sliding.
      if (slip > 4.0 && info.onTrack && wetness < 0.5) {
        const cy = Math.cos(v.yaw + w.steer), sy = Math.sin(v.yaw + w.steer);
        const right = [cy, 0, -sy];
        this.marks.addSegment(i, contact, right,
          (w.front ? v.setup.tireWidthFront : v.setup.tireWidthRear) * 1.05,
          clamp(intensity * 0.85, 0, 0.8), this.time);
      }
    }
  }

  smokeTint(pos) {
    // Sample the strongest nearby light so smoke picks up the neon.
    const lights = this.track.lights;
    let best = null, bestScore = 0;
    for (let i = 0; i < lights.length; i++) {
      const l = lights[i];
      const dx = l.pos[0] - pos[0], dz = l.pos[2] - pos[2];
      const d2 = dx * dx + dz * dz;
      if (d2 > l.radius * l.radius) continue;
      const score = l.intensity / (d2 + 4);
      if (score > bestScore) { bestScore = score; best = l; }
    }
    const amb = this.env.ambientSky;
    const base = [
      0.62 + amb[0] * 1.2,
      0.63 + amb[1] * 1.2,
      0.68 + amb[2] * 1.2,
    ];
    if (!best) return base;
    const k = clamp(bestScore * 6, 0, 0.75);
    return [
      lerp(base[0], best.color[0] * 1.25, k),
      lerp(base[1], best.color[1] * 1.25, k),
      lerp(base[2], best.color[2] * 1.25, k),
    ];
  }

  draw(dt) {
    this.smoke.update(dt, this.env.wind);
    this.rain.setIntensity(this.env.rainRate);

    const v = this.vehicle;
    const speedBlur = this.mode === 'run'
      ? clamp((v.speed - 22) / 55, 0, 1) * (this.camera.mode === 'cockpit' ? 0.6 : 1)
      : 0;

    this.renderer.render({
      camera: this.camera,
      env: this.env,
      track: this.track,
      vehicle: v,
      smoke: this.smoke,
      marks: this.marks,
      rain: this.rain,
      ghosts: this.ghostPlayer ? [this.ghostPlayer] : null,
      extraLights: this.mode === 'menu' ? this.garageLights : null,
      time: this.time,
      brakeLevel: this.mode === 'run' ? this.input.state.brake : 0,
      speedBlur,
      bloomStrength: 0.62 + this.env.wetness * 0.25,
      vignette: this.mode === 'run' ? 0.5 : 0.62,
      grain: 0.02,
      exposureBias: this.mode === 'menu' ? 1.15 : 1,
    });
  }

  trackPerformance(dt) {
    this.frameTimes.push(dt);
    if (this.frameTimes.length > 120) this.frameTimes.shift();
    // One-shot auto-downgrade if the first few seconds are clearly too slow.
    if (this.autoQualityChecked < 2 && this.frameTimes.length === 120 && this.mode === 'run') {
      const avg = this.frameTimes.reduce((a, b) => a + b, 0) / this.frameTimes.length;
      this.autoQualityChecked++;
      if (avg > 1 / 26) {
        const q = this.profile.data.settings.quality;
        const next = q === 'high' ? 'medium' : q === 'medium' ? 'low' : null;
        if (next) {
          this.setQuality(next);
          this.ui.toast(`Graphics lowered to ${next} to keep the frame rate up`);
        }
      }
      this.frameTimes.length = 0;
    }
  }
}

/* ------------------------------------------------------------- utilities -- */

function frame() {
  return new Promise((resolve) => requestAnimationFrame(() => resolve()));
}

function objectiveLabel(event) {
  switch (event.objective.type) {
    case 'zones': return 'zones';
    case 'single': return 'single drift';
    default: return 'points';
  }
}

function buildSummary(build) {
  return PART_CATEGORIES.map((c) => findPart(c.key, build.parts[c.key]).name).join(' / ');
}

const OWNED_KEY = 'markii-drift:owned:v1';

function loadOwned() {
  try {
    const raw = localStorage.getItem(OWNED_KEY);
    return new Set(raw ? JSON.parse(raw) : []);
  } catch { return new Set(); }
}

function saveOwned(set) {
  try { localStorage.setItem(OWNED_KEY, JSON.stringify([...set])); } catch { /* ignore */ }
}

/* ------------------------------------------------------------------ boot -- */

const game = new Game();
window.game = game;
game.boot();

if ('serviceWorker' in navigator && location.protocol.startsWith('http')) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('sw.js').catch(() => { /* offline is optional */ });
  });
}
