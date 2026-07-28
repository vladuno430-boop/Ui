// Input: touch controls, keyboard, gamepad and device tilt, normalised into one
// control state. Throttle and brake are ramped rather than switched, because a
// binary right foot cannot balance a drift.

import { clamp, lerp, moveTowards } from '../core/math.js';

const KEY_MAP = {
  ArrowUp: 'throttle', KeyW: 'throttle',
  ArrowDown: 'brake', KeyS: 'brake',
  ArrowLeft: 'left', KeyA: 'left',
  ArrowRight: 'right', KeyD: 'right',
  Space: 'handbrake',
  ShiftLeft: 'clutch', ShiftRight: 'clutch',
};

export class InputManager {
  constructor(root, options = {}) {
    this.root = root;
    this.state = {
      throttle: 0, brake: 0, steer: 0,
      handbrake: 0, clutch: 0,
      shiftUp: false, shiftDown: false,
    };
    this.raw = {
      throttle: 0, brake: 0, handbrake: 0, clutch: 0,
      steerTarget: 0, left: false, right: false,
    };
    this.keys = new Set();
    this.touchSteer = null;
    this.steerMode = options.steerMode || 'touch';   // touch | tilt | buttons
    this.tiltCalibration = 0;
    this.tiltValue = 0;
    this.sensitivity = options.sensitivity ?? 1;
    this.deadzone = 0.06;
    this.vibrate = options.vibrate ?? true;
    this.handlers = {};
    this.gamepadIndex = null;
    this.lastGamepadButtons = [];
    this.enabled = true;

    this.bindKeyboard();
    this.bindGamepad();
    this.bindTilt();
  }

  on(event, fn) { this.handlers[event] = fn; return this; }
  emit(event, arg) { if (this.handlers[event]) this.handlers[event](arg); }

  /* ------------------------------------------------------------ keyboard -- */

  bindKeyboard() {
    this._onKeyDown = (e) => {
      if (e.repeat) return;
      const action = KEY_MAP[e.code];
      if (action) { this.keys.add(action); e.preventDefault(); }
      switch (e.code) {
        case 'KeyE': case 'KeyX': this.state.shiftUp = true; break;
        case 'KeyQ': case 'KeyZ': this.state.shiftDown = true; break;
        case 'KeyC': this.emit('camera'); break;
        case 'KeyR': this.emit('reset'); break;
        case 'KeyH': this.emit('headlights'); break;
        case 'Escape': case 'KeyP': this.emit('pause'); break;
        case 'F2': this.emit('photo'); break;
      }
    };
    this._onKeyUp = (e) => {
      const action = KEY_MAP[e.code];
      if (action) this.keys.delete(action);
    };
    window.addEventListener('keydown', this._onKeyDown);
    window.addEventListener('keyup', this._onKeyUp);
    window.addEventListener('blur', () => this.keys.clear());
  }

  /* --------------------------------------------------------------- touch -- */

  /**
   * Wire up the on-screen controls. Each element is looked up by id; missing
   * elements are simply skipped so the desktop layout can omit them.
   */
  bindTouch(ids) {
    const bindPedal = (el, key, options = {}) => {
      if (!el) return;
      let pointerId = null;
      let startX = 0;
      const press = (e) => {
        pointerId = e.pointerId;
        el.setPointerCapture(e.pointerId);
        el.classList.add('is-active');
        startX = e.clientX;
        this.raw[key + 'Held'] = true;
        this.raw[key + 'Modulation'] = 1;
        if (options.instant) this.raw[key] = 1;
        if (this.vibrate && navigator.vibrate) navigator.vibrate(8);
        e.preventDefault();
      };
      const move = (e) => {
        if (e.pointerId !== pointerId) return;
        // Sliding sideways on the pedal modulates it — this is the throttle
        // control that makes holding an angle possible on a touchscreen.
        const dx = (e.clientX - startX) / (el.offsetWidth * 1.2 || 100);
        this.raw[key + 'Modulation'] = clamp(1 + dx * (options.invert ? 1 : -1), 0.25, 1);
        e.preventDefault();
      };
      const release = (e) => {
        if (pointerId !== null && e.pointerId !== pointerId) return;
        pointerId = null;
        el.classList.remove('is-active');
        this.raw[key + 'Held'] = false;
        this.raw[key + 'Modulation'] = 1;
        if (options.instant) this.raw[key] = 0;
      };
      el.addEventListener('pointerdown', press);
      el.addEventListener('pointermove', move);
      el.addEventListener('pointerup', release);
      el.addEventListener('pointercancel', release);
      el.addEventListener('lostpointercapture', release);
    };

    const q = (id) => (id ? document.getElementById(id) : null);
    bindPedal(q(ids.throttle), 'throttle');
    bindPedal(q(ids.brake), 'brake');
    bindPedal(q(ids.handbrake), 'handbrake', { instant: true });
    bindPedal(q(ids.clutch), 'clutch', { instant: true });

    const steerEl = q(ids.steer);
    if (steerEl) {
      let pointerId = null;
      let originX = 0;
      const range = () => Math.max(60, steerEl.offsetWidth * 0.34);
      steerEl.addEventListener('pointerdown', (e) => {
        pointerId = e.pointerId;
        steerEl.setPointerCapture(e.pointerId);
        originX = e.clientX;
        this.touchSteer = 0;
        steerEl.classList.add('is-active');
        e.preventDefault();
      });
      steerEl.addEventListener('pointermove', (e) => {
        if (e.pointerId !== pointerId) return;
        this.touchSteer = clamp((e.clientX - originX) / range(), -1, 1);
        // Re-anchor at full lock so the finger cannot wander off the pad.
        if (Math.abs(this.touchSteer) >= 1) originX = e.clientX - Math.sign(this.touchSteer) * range();
        e.preventDefault();
      });
      const end = (e) => {
        if (pointerId !== null && e.pointerId !== pointerId) return;
        pointerId = null;
        this.touchSteer = null;
        steerEl.classList.remove('is-active');
      };
      steerEl.addEventListener('pointerup', end);
      steerEl.addEventListener('pointercancel', end);
      steerEl.addEventListener('lostpointercapture', end);
    }

    const bindTap = (el, event) => {
      if (!el) return;
      el.addEventListener('pointerdown', (e) => {
        e.preventDefault();
        this.emit(event);
        if (this.vibrate && navigator.vibrate) navigator.vibrate(6);
      });
    };
    bindTap(q(ids.shiftUp), 'shiftUp');
    bindTap(q(ids.shiftDown), 'shiftDown');
    bindTap(q(ids.camera), 'camera');
    bindTap(q(ids.reset), 'reset');
    bindTap(q(ids.pause), 'pause');

    // Buttons that need to feed the vehicle directly rather than an event.
    this.on('shiftUp', () => { this.state.shiftUp = true; });
    this.on('shiftDown', () => { this.state.shiftDown = true; });
  }

  /* ---------------------------------------------------------------- tilt -- */

  bindTilt() {
    this._onOrientation = (e) => {
      // gamma is the left/right tilt in degrees when held in landscape.
      const raw = (e.gamma ?? 0);
      const beta = (e.beta ?? 0);
      // In landscape the useful axis swaps; use whichever the screen reports.
      const angle = (screen.orientation?.angle ?? window.orientation ?? 0);
      let value = Math.abs(angle) === 90 ? beta : raw;
      if (angle === -90 || angle === 270) value = -value;
      this.tiltValue = value;
    };
    window.addEventListener('deviceorientation', this._onOrientation);
  }

  async requestTiltPermission() {
    const anyWindow = window;
    if (anyWindow.DeviceOrientationEvent?.requestPermission) {
      try {
        const res = await anyWindow.DeviceOrientationEvent.requestPermission();
        return res === 'granted';
      } catch { return false; }
    }
    return true;
  }

  calibrateTilt() { this.tiltCalibration = this.tiltValue; }

  /* ------------------------------------------------------------- gamepad -- */

  bindGamepad() {
    window.addEventListener('gamepadconnected', (e) => {
      this.gamepadIndex = e.gamepad.index;
      this.emit('gamepad', e.gamepad.id);
    });
    window.addEventListener('gamepaddisconnected', () => { this.gamepadIndex = null; });
  }

  pollGamepad() {
    if (this.gamepadIndex === null || !navigator.getGamepads) return null;
    const pad = navigator.getGamepads()[this.gamepadIndex];
    if (!pad) return null;
    const axis = pad.axes[0] ?? 0;
    const throttle = pad.buttons[7]?.value ?? 0;
    const brake = pad.buttons[6]?.value ?? 0;
    const handbrake = pad.buttons[0]?.value ?? 0;
    const clutch = pad.buttons[1]?.value ?? 0;
    const up = pad.buttons[5]?.pressed ?? false;
    const down = pad.buttons[4]?.pressed ?? false;
    const camera = pad.buttons[3]?.pressed ?? false;
    const reset = pad.buttons[2]?.pressed ?? false;
    const wasUp = this.lastGamepadButtons[5];
    const wasDown = this.lastGamepadButtons[4];
    const wasCam = this.lastGamepadButtons[3];
    const wasReset = this.lastGamepadButtons[2];
    this.lastGamepadButtons[5] = up;
    this.lastGamepadButtons[4] = down;
    this.lastGamepadButtons[3] = camera;
    this.lastGamepadButtons[2] = reset;
    if (up && !wasUp) this.state.shiftUp = true;
    if (down && !wasDown) this.state.shiftDown = true;
    if (camera && !wasCam) this.emit('camera');
    if (reset && !wasReset) this.emit('reset');
    return { axis, throttle, brake, handbrake, clutch };
  }

  /* -------------------------------------------------------------- sample -- */

  sample(dt) {
    const s = this.state;
    const raw = this.raw;
    if (!this.enabled) {
      s.throttle = s.brake = s.handbrake = s.clutch = 0;
      s.steer = lerp(s.steer, 0, clamp(dt * 8, 0, 1));
      return s;
    }

    const pad = this.pollGamepad();
    const keyThrottle = this.keys.has('throttle');
    const keyBrake = this.keys.has('brake');

    // Pedal ramps: ~0.28 s to full throttle, ~0.18 s to full brake, quick release.
    const throttleTarget = Math.max(
      keyThrottle ? 1 : 0,
      raw.throttleHeld ? raw.throttleModulation ?? 1 : 0,
      pad ? pad.throttle : 0,
    );
    const brakeTarget = Math.max(
      keyBrake ? 1 : 0,
      raw.brakeHeld ? raw.brakeModulation ?? 1 : 0,
      pad ? pad.brake : 0,
    );
    s.throttle = moveTowards(s.throttle, throttleTarget, dt * (throttleTarget > s.throttle ? 3.6 : 6.5));
    s.brake = moveTowards(s.brake, brakeTarget, dt * (brakeTarget > s.brake ? 5.5 : 8.0));

    const hbTarget = Math.max(
      this.keys.has('handbrake') ? 1 : 0,
      raw.handbrakeHeld ? 1 : 0,
      pad ? pad.handbrake : 0,
    );
    s.handbrake = moveTowards(s.handbrake, hbTarget, dt * (hbTarget > s.handbrake ? 12 : 9));

    const clutchTarget = Math.max(
      this.keys.has('clutch') ? 1 : 0,
      raw.clutchHeld ? 1 : 0,
      pad ? pad.clutch : 0,
    );
    s.clutch = moveTowards(s.clutch, clutchTarget, dt * 16);

    // Steering.
    let target = 0;
    if (this.touchSteer !== null) {
      target = this.touchSteer;
    } else if (this.steerMode === 'tilt') {
      const t = (this.tiltValue - this.tiltCalibration) / 26;
      target = clamp(t, -1, 1);
    }
    if (pad && Math.abs(pad.axis) > this.deadzone) target = pad.axis;
    if (this.keys.has('left')) target = -1;
    if (this.keys.has('right')) target = 1;
    if (Math.abs(target) < this.deadzone) target = 0;
    target *= this.sensitivity;

    const usingAnalog = this.touchSteer !== null || (pad && Math.abs(pad.axis) > this.deadzone)
      || this.steerMode === 'tilt';
    if (usingAnalog) {
      s.steer = lerp(s.steer, clamp(target, -1, 1), clamp(dt * 22, 0, 1));
    } else {
      // Digital input still needs a believable rate at the rim.
      const rate = target === 0 ? 4.6 : 3.1;
      s.steer = moveTowards(s.steer, clamp(target, -1, 1), dt * rate);
    }

    return s;
  }

  /** Call after the vehicle has consumed the state — shift requests are edges. */
  endFrame() {
    this.state.shiftUp = false;
    this.state.shiftDown = false;
  }

  dispose() {
    window.removeEventListener('keydown', this._onKeyDown);
    window.removeEventListener('keyup', this._onKeyUp);
    window.removeEventListener('deviceorientation', this._onOrientation);
  }
}
