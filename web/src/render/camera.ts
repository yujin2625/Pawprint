import * as THREE from 'three';

/**
 * Fly camera with the controls of WEB_DESIGN.md §6.2 (Unity-like):
 *  - right button held: drag to look, W/A/S/D move, Q/E down/up, wheel changes speed, Shift moves faster
 *  - wheel alone: move forward/back; middle button drag: pan
 *  - F: frame the given bounds
 * Movement keys only act while the right button is held, so they stay free for shortcuts otherwise.
 */
export class FlyCamera {
  readonly camera: THREE.PerspectiveCamera;
  yaw = -Math.PI * 0.75;
  pitch = -0.5;
  /** Blocks per second. */
  speed = 10;
  onChange: () => void = () => {};
  onSpeed: (speed: number) => void = () => {};

  private looking = false;
  private panning = false;
  private last = { x: 0, y: 0 };
  private readonly keys = new Set<string>();
  private readonly cleanup: (() => void)[] = [];

  constructor(private readonly element: HTMLElement) {
    this.camera = new THREE.PerspectiveCamera(70, 1, 0.05, 20000);
    this.camera.rotation.order = 'YXZ';
    this.apply();

    const on = <K extends keyof HTMLElementEventMap>(target: HTMLElement | Window, type: K, fn: (e: HTMLElementEventMap[K]) => void, opts?: AddEventListenerOptions) => {
      target.addEventListener(type, fn as EventListener, opts);
      this.cleanup.push(() => target.removeEventListener(type, fn as EventListener, opts));
    };
    on(element, 'contextmenu', (e) => e.preventDefault());
    on(element, 'pointerdown', (e) => {
      if (e.button === 2) this.looking = true;
      else if (e.button === 1) this.panning = true;
      else return;
      e.preventDefault();
      element.setPointerCapture(e.pointerId);
      element.focus();
      this.last = { x: e.clientX, y: e.clientY };
    });
    on(element, 'pointermove', (e) => {
      const dx = e.clientX - this.last.x, dy = e.clientY - this.last.y;
      this.last = { x: e.clientX, y: e.clientY };
      if (this.looking) {
        this.yaw -= dx * 0.004;
        this.pitch = Math.max(-1.55, Math.min(1.55, this.pitch - dy * 0.004));
        this.apply();
      } else if (this.panning) {
        const scale = Math.max(0.02, this.speed * 0.004);
        this.camera.position.addScaledVector(this.right(), -dx * scale).addScaledVector(this.up(), dy * scale);
        this.apply();
      }
    });
    const release = (e: PointerEvent) => {
      if (e.button === 2) {
        this.looking = false;
        this.keys.clear();
      }
      if (e.button === 1) this.panning = false;
    };
    on(element, 'pointerup', release);
    on(element, 'pointercancel', () => {
      this.looking = this.panning = false;
      this.keys.clear();
    });
    on(element, 'wheel', (e) => {
      e.preventDefault();
      const notches = Math.sign(e.deltaY) * Math.min(3, Math.max(1, Math.abs(e.deltaY) / 100));
      if (this.looking) {
        this.speed = Math.max(0.5, Math.min(200, this.speed * Math.pow(1.25, -notches)));
        this.onSpeed(this.speed);
      } else {
        this.camera.position.addScaledVector(this.forward(), -notches * Math.max(1, this.speed * 0.3));
        this.apply();
      }
    }, { passive: false });
    on(window, 'keydown', (e) => {
      if (!this.looking) return;
      const key = e.key.toLowerCase();
      if ('wasdqe'.includes(key) || key === 'shift') {
        this.keys.add(key);
        e.preventDefault();
      }
    });
    on(window, 'keyup', (e) => this.keys.delete(e.key.toLowerCase()));
    on(window, 'blur', () => {
      this.keys.clear();
      this.looking = this.panning = false;
    });
  }

  /** Moves with held keys; returns whether the camera changed. */
  update(dt: number): boolean {
    if (!this.looking || this.keys.size === 0) return false;
    const move = new THREE.Vector3();
    const f = this.forward(), r = this.right();
    if (this.keys.has('w')) move.add(f);
    if (this.keys.has('s')) move.sub(f);
    if (this.keys.has('d')) move.add(r);
    if (this.keys.has('a')) move.sub(r);
    if (this.keys.has('e')) move.y += 1;
    if (this.keys.has('q')) move.y -= 1;
    if (move.lengthSq() === 0) return false;
    move.normalize().multiplyScalar(this.speed * dt * (this.keys.has('shift') ? 3 : 1));
    this.camera.position.add(move);
    this.apply();
    return true;
  }

  /** Places the camera so the whole box is in view, keeping the viewing direction. */
  frame(min: THREE.Vector3, max: THREE.Vector3): void {
    const center = min.clone().add(max).multiplyScalar(0.5);
    const radius = Math.max(1, min.distanceTo(max) / 2);
    const fov = (this.camera.fov * Math.PI) / 180;
    const fit = radius / Math.sin(Math.min(fov, fov * this.camera.aspect) / 2);
    this.camera.position.copy(center).addScaledVector(this.forward(), -fit * 1.05);
    this.speed = Math.max(4, Math.min(200, radius / 2));
    this.onSpeed(this.speed);
    this.apply();
  }

  resize(width: number, height: number): void {
    this.camera.aspect = width / Math.max(1, height);
    this.camera.updateProjectionMatrix();
  }

  forward(): THREE.Vector3 {
    const cp = Math.cos(this.pitch);
    return new THREE.Vector3(-Math.sin(this.yaw) * cp, Math.sin(this.pitch), -Math.cos(this.yaw) * cp);
  }

  right(): THREE.Vector3 {
    return new THREE.Vector3(Math.cos(this.yaw), 0, -Math.sin(this.yaw));
  }

  up(): THREE.Vector3 {
    return this.right().cross(this.forward()).normalize();
  }

  private apply(): void {
    this.camera.rotation.set(this.pitch, this.yaw, 0);
    this.camera.updateMatrixWorld();
    this.onChange();
  }

  dispose(): void {
    this.cleanup.forEach((fn) => fn());
  }
}
