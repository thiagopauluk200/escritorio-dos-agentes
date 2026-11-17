'use strict';
/*
 * Motor 3D da Rede Neural — sem dependências externas.
 * - Simulação física 3D (repulsão de Coulomb, molas de Hooke nos links, gravidade central,
 *   atrito viscoso, resfriamento "alpha" como em simulações de força).
 * - Câmera orbital com inércia: arrastar = girar, pinça = zoom, dois dedos = mover,
 *   toque = selecionar, toque duplo = focar, toque longo = puxar uma nota pela física.
 * - Renderização em Canvas 2D com projeção em perspectiva, névoa por profundidade,
 *   brilho aditivo e partículas de fluxo de dados.
 */
(function (global) {
  const TAU = Math.PI * 2;
  const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);

  function hexRgb(h) {
    const n = parseInt(h.slice(1), 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }

  const spriteCache = new Map();
  function sprite(cor) {
    let s = spriteCache.get(cor);
    if (s) return s;
    const c = document.createElement('canvas');
    c.width = c.height = 64;
    const g = c.getContext('2d');
    const [r, gg, b] = hexRgb(cor);
    const grad = g.createRadialGradient(32, 32, 0, 32, 32, 32);
    grad.addColorStop(0, `rgba(${r},${gg},${b},0.95)`);
    grad.addColorStop(0.18, `rgba(${r},${gg},${b},0.55)`);
    grad.addColorStop(0.45, `rgba(${r},${gg},${b},0.14)`);
    grad.addColorStop(1, `rgba(${r},${gg},${b},0)`);
    g.fillStyle = grad;
    g.fillRect(0, 0, 64, 64);
    spriteCache.set(cor, c);
    return c;
  }

  const PADRAO = {
    repulsao: 900, mola: 0.06, distancia: 46, gravidade: 0.012, atrito: 0.14,
    sensibilidade: 1, autoGirar: true, velGiro: 0.06, rotulos: 'auto', qualidade: 'alta',
    particulas: true, trilhas: true, tags: true, fantasmas: true, pastas: true, notas: true, tamanhoPor: 'conexoes',
    corPor: 'pasta', fisica: true,
  };

  class Motor {
    constructor(canvas) {
      this.cv = canvas;
      this.ctx = canvas.getContext('2d', { alpha: false });
      this.op = Object.assign({}, PADRAO);
      this.nos = [];
      this.mapa = new Map();
      this.arestas = [];
      this.trilhas = [];
      this.particulas = [];
      this.estrelas = [];
      for (let i = 0; i < 260; i++) {
        const u = Math.random() * 2 - 1, t = Math.random() * TAU, r = Math.sqrt(1 - u * u);
        this.estrelas.push({ x: r * Math.cos(t), y: u, z: r * Math.sin(t), b: Math.random() });
      }
      this.alpha = 1;
      this.cam = { yaw: 0.6, pitch: 0.25, dist: 520, alvo: [0, 0, 0] };
      this.meta = { yaw: 0.6, pitch: 0.25, dist: 520, alvo: [0, 0, 0] };
      this.vel = { yaw: 0, pitch: 0 };
      this.ponteiros = new Map();
      this.gesto = null;
      this.selecionado = null;
      this.foco = null;
      this.ultimaInteracao = 0;
      this.filtro = null;
      this.cb = { selecionar: () => {}, fps: () => {} };
      this.raioNuvem = 200;
      this.fps = 60;
      this._t = performance.now();
      this._frames = 0;
      this._fpsT = this._t;
      this._toque = { t: 0, x: 0, y: 0 };
      this.dpr = 1;
      this._ligarGestos();
      this.redimensionar();
      this._loop = this._loop.bind(this);
      requestAnimationFrame(this._loop);
    }

    on(ev, fn) { this.cb[ev] = fn; }

    opcoes(o) {
      Object.assign(this.op, o);
      if (this.foco && !this._visivel(this.foco)) this.enquadrar();
      if (this.selecionado && !this._visivel(this.selecionado)) this.selecionar(null);
      this.redimensionar();
      this.reaquecer(0.4);
    }

    redimensionar() {
      const r = this.cv.getBoundingClientRect();
      const max = this.op.qualidade === 'alta' ? 2.5 : this.op.qualidade === 'media' ? 1.75 : 1;
      this.dpr = Math.min(global.devicePixelRatio || 1, max);
      this.w = Math.max(1, r.width);
      this.h = Math.max(1, r.height);
      this.cv.width = Math.round(this.w * this.dpr);
      this.cv.height = Math.round(this.h * this.dpr);
    }

    reaquecer(a) { this.alpha = Math.max(this.alpha, a); }

    // ------------------------------------------------------------ dados
    definirGrafo(nos, arestas) {
      const antigos = this.mapa;
      const novos = new Map();
      const lista = [];
      let novosN = 0;
      for (const d of nos) {
        let n = antigos.get(d.id);
        if (!n) {
          n = { x: 0, y: 0, z: 0, vx: 0, vy: 0, vz: 0, pulso: 0, brilho: 0, novo: true };
          novosN++;
        }
        Object.assign(n, d);
        novos.set(d.id, n);
        lista.push(n);
      }
      const arr = [];
      for (const [a, b, tipo] of arestas) {
        const na = novos.get(a), nb = novos.get(b);
        if (na && nb) arr.push({ a: na, b: nb, tipo });
      }
      const grau = new Map();
      for (const e of arr) {
        grau.set(e.a, (grau.get(e.a) || 0) + 1);
        grau.set(e.b, (grau.get(e.b) || 0) + 1);
      }
      for (const n of lista) {
        n.grauSim = grau.get(n) || 1;
        if (n.novo) {
          const viz = arr.find(e => (e.a === n && !e.b.novo) || (e.b === n && !e.a.novo));
          const base = viz ? (viz.a === n ? viz.b : viz.a) : null;
          const r = base ? 30 : 160 + Math.random() * 80;
          const u = Math.random() * 2 - 1, t = Math.random() * TAU, s = Math.sqrt(1 - u * u);
          n.x = (base ? base.x : 0) + r * s * Math.cos(t);
          n.y = (base ? base.y : 0) + r * u;
          n.z = (base ? base.z : 0) + r * s * Math.sin(t);
          n.novo = false;
          n.pulso = antigos.size ? 1 : 0;
        }
      }
      this.nos = lista;
      this.mapa = novos;
      this.arestas = arr;
      this.reaquecer(novosN > lista.length * 0.5 ? 1 : 0.35);
      if (this.selecionado && !novos.has(this.selecionado.id)) this.selecionado = null;
    }

    definirTrilhas(tr) { this.trilhas = tr || []; }
    definirFiltro(fn) { this.filtro = fn; }
    no(id) { return this.mapa.get(id); }

    pulsar(id, forca = 1) {
      const n = this.mapa.get(id);
      if (n) { n.pulso = Math.min(1.6, n.pulso + forca); n.brilho = 1; }
    }

    particula(deId, paraId, cor, tam = 1, quantidade = 1) {
      if (!this.op.particulas) return;
      const a = this.mapa.get(deId), b = this.mapa.get(paraId);
      if (!a || !b || a === b) return;
      for (let i = 0; i < quantidade; i++) {
        if (this.particulas.length > 600) this.particulas.shift();
        this.particulas.push({
          a, b, cor, tam: clamp(tam, 0.6, 3.2), t: -i * 0.09,
          vel: 0.55 + Math.random() * 0.35,
          curva: [(Math.random() - 0.5) * 60, (Math.random() - 0.5) * 60, (Math.random() - 0.5) * 60],
        });
      }
    }

    selecionar(id, notificar = false) {
      const n = this.mapa.get(id);
      this.selecionado = n && this._visivel(n) ? n : null;
      if (notificar) this.cb.selecionar(this.selecionado);
    }

    zoom(fator) {
      this.meta.dist *= fator;
      this.ultimaInteracao = performance.now();
    }

    focar(id) {
      const n = this.mapa.get(id);
      if (!n || !this._visivel(n)) return;
      this.foco = n;
      this.meta.dist = clamp(160 + (n.raio || 4) * 10, 120, 400);
      this.selecionar(id);
      this.ultimaInteracao = performance.now();
    }

    enquadrar() {
      this.foco = null;
      this.meta.alvo = [0, 0, 0];
      this.meta.dist = Math.max(260, this.raioNuvem * 2.6);
    }

    // ---------------------------------------------------------- física
    _simular() {
      const nos = this.nos, op = this.op;
      const N = nos.length;
      if (!N) return;
      let agentesR = 0;
      if (op.fisica && this.alpha > 0.004) {
        const a = this.alpha;
        const ativos = nos.filter(n => n.tipo !== 'agente' && this._visivel(n));
        const M = ativos.length;
        const k = op.repulsao * a;
        const amostrar = M > 900;
        for (let i = 0; i < M; i++) {
          const p = ativos[i];
          const lim = amostrar ? 80 : M;
          for (let s = 0; s < lim; s++) {
            let j;
            if (amostrar) { j = (Math.random() * M) | 0; if (j === i) continue; }
            else { j = s; if (j <= i) continue; }
            const q = ativos[j];
            let dx = p.x - q.x, dy = p.y - q.y, dz = p.z - q.z;
            let d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < 1) { dx = Math.random() - 0.5; dy = Math.random() - 0.5; dz = Math.random() - 0.5; d2 = 1; }
            if (d2 > 250000) continue;
            const f = (k * (amostrar ? M / 80 : 1)) / d2 / Math.sqrt(d2);
            const fx = dx * f, fy = dy * f, fz = dz * f;
            p.vx += fx; p.vy += fy; p.vz += fz;
            if (!amostrar) { q.vx -= fx; q.vy -= fy; q.vz -= fz; }
          }
        }
        for (const e of this.arestas) {
          if (e.tipo === 'origem') continue;
          const A = e.a, B = e.b;
          if (A.tipo === 'agente' || B.tipo === 'agente' || !this._visivel(A) || !this._visivel(B)) continue;
          const L = op.distancia * (e.tipo === 'pasta' ? 1.25 : e.tipo === 'tag' ? 0.9 : 1);
          const dx = B.x - A.x, dy = B.y - A.y, dz = B.z - A.z;
          const d = Math.sqrt(dx * dx + dy * dy + dz * dz) || 1;
          const forca = op.mola * (e.tipo === 'link' ? 1 : e.tipo === 'pasta' ? 0.55 : 0.4);
          const f = ((d - L) / d) * forca * a;
          const wa = B.grauSim / (A.grauSim + B.grauSim), wb = 1 - wa;
          A.vx += dx * f * wa; A.vy += dy * f * wa; A.vz += dz * f * wa;
          B.vx -= dx * f * wb; B.vy -= dy * f * wb; B.vz -= dz * f * wb;
        }
        const g = op.gravidade * a;
        const atr = 1 - clamp(op.atrito, 0.02, 0.6);
        for (const n of ativos) {
          n.vx -= n.x * g; n.vy -= n.y * g; n.vz -= n.z * g;
          if (n.preso) { n.vx = n.vy = n.vz = 0; continue; }
          n.vx *= atr; n.vy *= atr; n.vz *= atr;
          const v2 = n.vx * n.vx + n.vy * n.vy + n.vz * n.vz;
          if (v2 > 400) { const s = 20 / Math.sqrt(v2); n.vx *= s; n.vy *= s; n.vz *= s; }
          n.x += n.vx; n.y += n.vy; n.z += n.vz;
        }
        const alvoAlpha = this.gesto && this.gesto.tipo === 'puxar' ? 0.3 : 0;
        this.alpha += (alvoAlpha - this.alpha) * 0.012;
      }
      let r2 = 0;
      for (const n of nos) {
        if (n.tipo === 'agente' || !this._visivel(n)) continue;
        const d = n.x * n.x + n.y * n.y + n.z * n.z;
        if (d > r2) r2 = d;
      }
      this.raioNuvem += (Math.sqrt(r2) - this.raioNuvem) * 0.05;
      agentesR = this.raioNuvem * 0.87 + 38;
      const t = performance.now() / 1000;
      for (const n of nos) {
        if (n.tipo !== 'agente') continue;
        const lado = n.id === 'agente:claude' ? -1 : 1;
        n.x = lado * agentesR;
        n.y = (op.movimento ? Math.sin(t * 0.9 + lado) * 7 : 0) + 20;
        n.z = op.movimento ? Math.cos(t * 0.6 + lado) * 12 : 0;
      }
    }

    _visivel(n) {
      const op = this.op;
      if (n.tipo === 'nota' && !op.notas) return false;
      if (n.tipo === 'tag' && !op.tags) return false;
      if (n.tipo === 'fantasma' && !op.fantasmas) return false;
      if ((n.tipo === 'pasta' || n.tipo === 'raiz') && !op.pastas) return false;
      return true;
    }

    // ---------------------------------------------------------- câmera
    _base() {
      const c = this.cam;
      const cp = Math.cos(c.pitch);
      const pos = [c.alvo[0] + c.dist * cp * Math.sin(c.yaw), c.alvo[1] + c.dist * Math.sin(c.pitch),
        c.alvo[2] + c.dist * cp * Math.cos(c.yaw)];
      let f = [c.alvo[0] - pos[0], c.alvo[1] - pos[1], c.alvo[2] - pos[2]];
      const fl = Math.hypot(f[0], f[1], f[2]);
      f = [f[0] / fl, f[1] / fl, f[2] / fl];
      let r = [f[2], 0, -f[0]];
      const rl = Math.hypot(r[0], r[2]) || 1;
      r = [-r[0] / rl, 0, -r[2] / rl];
      const u = [r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]];
      // Limita o campo horizontal em telas altas para manter a rede no quadro.
      const escala = Math.min(this.h, this.w * 1.55);
      this._b = { pos, f, r, u, foc: (escala * 0.5) / Math.tan(0.45) };
      return this._b;
    }

    _proj(x, y, z, b) {
      const vx = x - b.pos[0], vy = y - b.pos[1], vz = z - b.pos[2];
      const pz = vx * b.f[0] + vy * b.f[1] + vz * b.f[2];
      if (pz < 8) return null;
      const px = vx * b.r[0] + vy * b.r[1] + vz * b.r[2];
      const py = vx * b.u[0] + vy * b.u[1] + vz * b.u[2];
      const s = b.foc / pz;
      return { x: this.w / 2 + px * s, y: this.h / 2 - py * s, s, z: pz };
    }

    _camera(dt) {
      const c = this.cam, m = this.meta;
      const ocioso = performance.now() - this.ultimaInteracao > 4000;
      if (!this.gesto) {
        m.yaw += this.vel.yaw * dt;
        m.pitch += this.vel.pitch * dt;
        const amort = Math.exp(-dt * 2.6);
        this.vel.yaw *= amort;
        this.vel.pitch *= amort;
        if (this.op.autoGirar && ocioso && Math.abs(this.vel.yaw) < this.op.velGiro) {
          this.vel.yaw += (this.op.velGiro - this.vel.yaw) * Math.min(1, dt * 0.8);
        }
      }
      m.pitch = clamp(m.pitch, -1.45, 1.45);
      m.dist = clamp(m.dist, 30, 4000);
      if (this.foco) m.alvo = [this.foco.x, this.foco.y, this.foco.z];
      const k = 1 - Math.exp(-dt * 9);
      c.yaw += (m.yaw - c.yaw) * k;
      c.pitch += (m.pitch - c.pitch) * k;
      c.dist += (m.dist - c.dist) * (1 - Math.exp(-dt * 7));
      for (let i = 0; i < 3; i++) c.alvo[i] += (m.alvo[i] - c.alvo[i]) * (1 - Math.exp(-dt * 5));
    }

    // ---------------------------------------------------------- gestos
    _ligarGestos() {
      const cv = this.cv;
      cv.style.touchAction = 'none';
      cv.addEventListener('pointerdown', e => this._down(e));
      cv.addEventListener('pointermove', e => this._move(e));
      cv.addEventListener('pointerup', e => this._up(e));
      cv.addEventListener('pointercancel', e => this._up(e, true));
      cv.addEventListener('wheel', e => {
        e.preventDefault();
        this.meta.dist *= Math.exp(e.deltaY * 0.0012);
        this.ultimaInteracao = performance.now();
      }, { passive: false });
      cv.addEventListener('contextmenu', e => e.preventDefault());
    }

    _xy(e) { const r = this.cv.getBoundingClientRect(); return [e.clientX - r.left, e.clientY - r.top]; }

    _down(e) {
      this.cv.setPointerCapture(e.pointerId);
      const [x, y] = this._xy(e);
      this.ponteiros.set(e.pointerId, { x, y, x0: x, y0: y, t0: performance.now(), botao: e.button });
      this.ultimaInteracao = performance.now();
      this.vel.yaw = this.vel.pitch = 0;
      if (this.ponteiros.size === 1) {
        this.gesto = { tipo: e.button === 2 ? 'mover' : 'girar', ultimo: [x, y], t: performance.now(), amostras: [] };
        clearTimeout(this._longo);
        const alvo = this._acertar(x, y);
        if (alvo && alvo.tipo !== 'agente' && e.pointerType !== 'mouse') {
          this._longo = setTimeout(() => {
            if (this.gesto && this.gesto.tipo === 'girar' && this.ponteiros.size === 1) {
              this.gesto = { tipo: 'puxar', no: alvo, ultimo: this.gesto.ultimo };
              alvo.preso = true;
              this.selecionar(alvo.id);
              this.reaquecer(0.3);
              if (navigator.vibrate) navigator.vibrate(18);
            }
          }, 420);
        }
      } else if (this.ponteiros.size === 2) {
        clearTimeout(this._longo);
        if (this.gesto && this.gesto.no) this.gesto.no.preso = false;
        const [a, b] = [...this.ponteiros.values()];
        this.gesto = { tipo: 'pinca', d: Math.hypot(a.x - b.x, a.y - b.y), m: [(a.x + b.x) / 2, (a.y + b.y) / 2] };
      }
    }

    _move(e) {
      const p = this.ponteiros.get(e.pointerId);
      if (!p) return;
      const [x, y] = this._xy(e);
      p.x = x; p.y = y;
      if (Math.hypot(x - p.x0, y - p.y0) > 9) clearTimeout(this._longo);
      const g = this.gesto;
      if (!g) return;
      this.ultimaInteracao = performance.now();
      const sens = 0.0055 * this.op.sensibilidade;
      if (g.tipo === 'girar' && this.ponteiros.size === 1) {
        const dx = x - g.ultimo[0], dy = y - g.ultimo[1];
        this.meta.yaw -= dx * sens;
        this.meta.pitch += dy * sens;
        const agora = performance.now();
        g.amostras.push([agora, -dx * sens, dy * sens]);
        while (g.amostras.length && agora - g.amostras[0][0] > 90) g.amostras.shift();
        g.ultimo = [x, y];
      } else if (g.tipo === 'mover' && this.ponteiros.size === 1) {
        this._mover(x - g.ultimo[0], y - g.ultimo[1]);
        g.ultimo = [x, y];
      } else if (g.tipo === 'puxar') {
        const b = this._b || this._base();
        const n = g.no;
        const pr = this._proj(n.x, n.y, n.z, b);
        if (pr) {
          const dx = (x - g.ultimo[0]) / pr.s, dy = -(y - g.ultimo[1]) / pr.s;
          n.x += b.r[0] * dx + b.u[0] * dy;
          n.y += b.r[1] * dx + b.u[1] * dy;
          n.z += b.r[2] * dx + b.u[2] * dy;
          this.reaquecer(0.3);
        }
        g.ultimo = [x, y];
      } else if (g.tipo === 'pinca' && this.ponteiros.size === 2) {
        const [a, b] = [...this.ponteiros.values()];
        const d = Math.hypot(a.x - b.x, a.y - b.y);
        const m = [(a.x + b.x) / 2, (a.y + b.y) / 2];
        if (g.d > 10 && d > 10) {
          this.meta.dist *= Math.pow(g.d / d, 1.15);
          this.foco = null;
        }
        this._mover(m[0] - g.m[0], m[1] - g.m[1]);
        g.d = d; g.m = m;
      }
    }

    _mover(dx, dy) {
      const b = this._b || this._base();
      const esc = this.cam.dist / b.foc;
      this.foco = null;
      for (let i = 0; i < 3; i++) this.meta.alvo[i] += (-b.r[i] * dx + b.u[i] * dy) * esc;
    }

    _up(e, cancel) {
      const p = this.ponteiros.get(e.pointerId);
      this.ponteiros.delete(e.pointerId);
      clearTimeout(this._longo);
      const g = this.gesto;
      if (!p) return;
      const agora = performance.now();
      if (g && g.tipo === 'puxar') {
        g.no.preso = false;
        this.reaquecer(0.25);
        if (!cancel) this.cb.selecionar(g.no);
      }
      if (g && g.tipo === 'girar' && g.amostras.length > 1) {
        const dt = (agora - g.amostras[0][0]) / 1000;
        if (dt > 0.008 && agora - g.amostras[g.amostras.length - 1][0] < 60) {
          let sy = 0, sp = 0;
          for (const s of g.amostras) { sy += s[1]; sp += s[2]; }
          this.vel.yaw = clamp(sy / dt, -6, 6);
          this.vel.pitch = clamp(sp / dt, -4, 4);
        }
      }
      const toque = !cancel && g && g.tipo !== 'puxar' && g.tipo !== 'pinca' &&
        Math.hypot(p.x - p.x0, p.y - p.y0) < 10 && agora - p.t0 < 350;
      if (this.ponteiros.size === 1) {
        const [q] = [...this.ponteiros.values()];
        this.gesto = { tipo: 'girar', ultimo: [q.x, q.y], t: agora, amostras: [] };
      } else if (this.ponteiros.size === 0) {
        this.gesto = null;
      }
      if (toque) {
        const alvo = this._acertar(p.x, p.y);
        const duplo = agora - this._toque.t < 320 && Math.hypot(p.x - this._toque.x, p.y - this._toque.y) < 30;
        this._toque = { t: agora, x: p.x, y: p.y };
        if (duplo) {
          if (alvo) this.focar(alvo.id); else this.enquadrar();
        } else {
          this.selecionar(alvo ? alvo.id : null, true);
          if (alvo && navigator.vibrate) navigator.vibrate(8);
        }
      }
    }

    _acertar(x, y) {
      let melhor = null, md = Infinity;
      for (const n of this.nos) {
        const p = n._p;
        if (!p || !this._visivel(n)) continue;
        const r = Math.max(22, n._r * 1.6);
        const d = Math.hypot(p.x - x, p.y - y);
        if (d < r && d + p.z * 0.002 < md) { md = d + p.z * 0.002; melhor = n; }
      }
      return melhor;
    }

    // ---------------------------------------------------------- desenho
    _raio(n) {
      if (n.tipo === 'agente') return 13;
      if (n.tipo === 'raiz') return 9;
      if (n.tipo === 'pasta') return 6.5;
      if (n.tipo === 'tag') return 2.6 + Math.sqrt(n.usos || 1) * 0.8;
      if (n.tipo === 'fantasma') return 2.4;
      const p = this.op.tamanhoPor;
      if (p === 'tamanho') return 2.5 + Math.sqrt((n.bytes || 0) / 1024) * 1.4;
      if (p === 'relevancia') return 2.5 + Math.sqrt((n.rank || 0) * 400) * 1.6;
      return 2.6 + Math.sqrt(n.grau || 0) * 1.25;
    }

    _loop(agora) {
      requestAnimationFrame(this._loop);
      if (document.hidden || !this.cv.isConnected || this.w <= 1 || this.h <= 1) { this._t = agora; return; }
      let dt = (agora - this._t) / 1000;
      this._t = agora;
      if (dt > 0.1) dt = 0.1;
      this._frames++;
      if (agora - this._fpsT > 1000) {
        this.fps = Math.round((this._frames * 1000) / (agora - this._fpsT));
        this._frames = 0; this._fpsT = agora;
        this.cb.fps(this.fps);
      }
      this._simular();
      this._camera(dt);
      this._desenhar(dt, agora / 1000);
    }

    _desenhar(dt, t) {
      const ctx = this.ctx, w = this.w, h = this.h, op = this.op;
      ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
      ctx.globalCompositeOperation = 'source-over';
      const fundo = ctx.createRadialGradient(w / 2, h * 0.45, 0, w / 2, h * 0.45, Math.max(w, h) * 0.8);
      fundo.addColorStop(0, '#182a4a');
      fundo.addColorStop(0.48, '#0d172a');
      fundo.addColorStop(1, '#070c17');
      ctx.fillStyle = fundo;
      ctx.fillRect(0, 0, w, h);
      const b = this._base();

      // estrelas (fundo com paralaxe só de rotação)
      ctx.fillStyle = '#9fb3d9';
      for (const s of this.estrelas) {
        const vx = s.x, vy = s.y, vz = s.z;
        const pz = vx * b.f[0] + vy * b.f[1] + vz * b.f[2];
        if (pz < 0.1) continue;
        const px = vx * b.r[0] + vy * b.r[1] + vz * b.r[2];
        const py = vx * b.u[0] + vy * b.u[1] + vz * b.u[2];
        const x = w / 2 + (px / pz) * b.foc, y = h / 2 - (py / pz) * b.foc;
        if (x < 0 || y < 0 || x > w || y > h) continue;
        ctx.globalAlpha = 0.15 + 0.35 * s.b * (op.movimento ? 0.7 + 0.3 * Math.sin(t * 1.3 + s.b * 20) : 0.7);
        ctx.fillRect(x, y, 1.1, 1.1);
      }
      ctx.globalAlpha = 1;

      // projeção
      const sel = this.selecionado;
      const vizinhos = new Set();
      if (sel) for (const e of this.arestas) { if (e.a === sel) vizinhos.add(e.b); else if (e.b === sel) vizinhos.add(e.a); }
      const filtro = this.filtro;
      const vis = [];
      for (const n of this.nos) {
        n._p = null;
        if (!this._visivel(n)) continue;
        const p = this._proj(n.x, n.y, n.z, b);
        if (!p) continue;
        n._p = p;
        n._r = this._raio(n) * p.s;
        n._apagado = (filtro && !filtro(n)) || (sel && n !== sel && !vizinhos.has(n) && n.tipo !== 'agente');
        vis.push(n);
      }
      const prof = (z) => clamp(1.25 - (z - this.cam.dist * 0.35) / (this.cam.dist * 1.9), 0.18, 1);

      // arestas
      ctx.lineCap = 'round';
      for (const e of this.arestas) {
        const pa = e.a._p, pb = e.b._p;
        if (!pa || !pb) continue;
        const ativa = sel && (e.a === sel || e.b === sel);
        let alfa = prof((pa.z + pb.z) / 2);
        let cor;
        if (e.tipo === 'link') { cor = '150,200,255'; alfa *= 0.32; }
        else if (e.tipo === 'pasta') { cor = '120,140,170'; alfa *= 0.13; }
        else if (e.tipo === 'tag') { cor = '190,140,255'; alfa *= 0.16; }
        else { cor = e.a.id === 'agente:claude' ? '217,119,87' : '25,195,156'; alfa *= 0.18; }
        if (e.a._apagado || e.b._apagado) alfa *= 0.25;
        if (ativa) alfa = Math.min(1, alfa * 3 + 0.25);
        ctx.strokeStyle = `rgba(${cor},${alfa.toFixed(3)})`;
        ctx.lineWidth = ativa ? 1.6 : e.tipo === 'link' ? 0.9 : 0.6;
        if (e.tipo === 'origem') ctx.setLineDash([3, 5]); else ctx.setLineDash([]);
        ctx.beginPath();
        ctx.moveTo(pa.x, pa.y);
        ctx.lineTo(pb.x, pb.y);
        ctx.stroke();
      }
      ctx.setLineDash([]);

      // trilhas (fluxo nota→nota percorrido pelos agentes)
      if (op.trilhas) {
        const agoraMs = Date.now();
        for (const tr of this.trilhas) {
          const A = this.mapa.get(tr.de), B = this.mapa.get(tr.para);
          if (!A || !B || !A._p || !B._p) continue;
          const idade = (agoraMs - tr.t) / 3600000;
          const alfa = clamp(0.75 - idade * 0.2, 0.12, 0.75);
          const cor = tr.agente === 'claude' ? '217,119,87' : '25,195,156';
          const mx = (A.x + B.x) / 2, my = (A.y + B.y) / 2 + 25, mz = (A.z + B.z) / 2;
          const pm = this._proj(mx, my, mz, b);
          if (!pm) continue;
          ctx.strokeStyle = `rgba(${cor},${alfa})`;
          ctx.lineWidth = 1.2 + Math.min(3, tr.n * 0.5);
          ctx.setLineDash([2, 6]);
          ctx.lineDashOffset = op.movimento ? -t * 18 : 0;
          ctx.beginPath();
          ctx.moveTo(A._p.x, A._p.y);
          ctx.quadraticCurveTo(pm.x, pm.y, B._p.x, B._p.y);
          ctx.stroke();
        }
        ctx.setLineDash([]);
        ctx.lineDashOffset = 0;
      }

      // nós (longe → perto)
      vis.sort((p, q) => q._p.z - p._p.z);
      const brilhoQ = op.qualidade !== 'baixa';
      for (const n of vis) {
        const p = n._p;
        let r = Math.max(0.8, n._r);
        const a = prof(p.z) * (n._apagado ? 0.22 : 1);
        n.pulso *= Math.exp(-dt * 1.4);
        n.brilho *= Math.exp(-dt * 0.5);
        const cor = n.cor || '#8fb4ff';
        if (n.tipo === 'agente') r *= 1 + 0.08 * Math.sin(t * (n.ritmo || 2));
        if (brilhoQ) {
          ctx.globalCompositeOperation = 'lighter';
          ctx.globalAlpha = a * (0.55 + n.brilho * 0.6 + (n.tipo === 'agente' ? (n.energia || 0.3) : 0));
          const g = r * (n.tipo === 'agente' ? 6 : 4.2 + n.brilho * 3);
          ctx.drawImage(sprite(cor), p.x - g, p.y - g, g * 2, g * 2);
          ctx.globalCompositeOperation = 'source-over';
        }
        ctx.globalAlpha = a;
        if (n.tipo === 'fantasma') {
          ctx.strokeStyle = cor; ctx.lineWidth = 1;
          ctx.beginPath(); ctx.arc(p.x, p.y, r, 0, TAU); ctx.stroke();
        } else {
          ctx.fillStyle = cor;
          ctx.beginPath(); ctx.arc(p.x, p.y, r, 0, TAU); ctx.fill();
          if (n.tipo === 'nota' || n.tipo === 'agente') {
            ctx.fillStyle = 'rgba(255,255,255,0.55)';
            ctx.beginPath(); ctx.arc(p.x - r * 0.3, p.y - r * 0.3, r * 0.35, 0, TAU); ctx.fill();
          }
        }
        if (n.pulso > 0.03) {
          const rp = r + (1.6 - n.pulso) * 26 * p.s + 4;
          ctx.globalAlpha = clamp(n.pulso, 0, 1) * a;
          ctx.strokeStyle = n.corPulso || cor; ctx.lineWidth = 1.5;
          ctx.beginPath(); ctx.arc(p.x, p.y, rp, 0, TAU); ctx.stroke();
        }
        if (n === sel) {
          ctx.globalAlpha = 1;
          ctx.strokeStyle = '#ffffff'; ctx.lineWidth = 1.5;
          ctx.beginPath(); ctx.arc(p.x, p.y, r + 5 + Math.sin(t * 4) * 1.5, 0, TAU); ctx.stroke();
        }
      }
      ctx.globalAlpha = 1;

      // partículas de fluxo
      if (this.particulas.length) {
        ctx.globalCompositeOperation = 'lighter';
        const vivas = [];
        for (const q of this.particulas) {
          q.t += dt * q.vel;
          if (q.t >= 1) { q.b.pulso = Math.min(1.6, q.b.pulso + 0.25); q.b.brilho = 1; q.b.corPulso = q.cor; continue; }
          vivas.push(q);
          if (q.t < 0) continue;
          for (let k = 0; k < 4; k++) {
            const tt = Math.max(0, q.t - k * 0.025);
            const s = tt * tt * (3 - 2 * tt);
            const bz = 4 * s * (1 - s);
            const x = q.a.x + (q.b.x - q.a.x) * s + q.curva[0] * bz;
            const y = q.a.y + (q.b.y - q.a.y) * s + q.curva[1] * bz;
            const z = q.a.z + (q.b.z - q.a.z) * s + q.curva[2] * bz;
            const p = this._proj(x, y, z, b);
            if (!p) continue;
            const g = (6 - k) * q.tam * Math.max(0.5, p.s);
            ctx.globalAlpha = (1 - k * 0.22) * 0.9;
            ctx.drawImage(sprite(q.cor), p.x - g, p.y - g, g * 2, g * 2);
          }
        }
        this.particulas = vivas;
        ctx.globalCompositeOperation = 'source-over';
        ctx.globalAlpha = 1;
      }

      // rótulos
      if (op.rotulos !== 'nunca') {
        const ocupado = [];
        const candidatos = vis.filter(n => {
          if (n._apagado) return false;
          if (n === sel || vizinhos.has(n) || n.tipo === 'agente' || n.tipo === 'raiz') return true;
          if (op.rotulos === 'sempre') return n.tipo !== 'fantasma';
          return (n.tipo === 'pasta' && n._p.s > 0.7) || (n.tipo === 'nota' && (n._r > 7.5 || n.destaque));
        }).sort((p, q) => (q === sel) - (p === sel) || q._r - p._r);
        ctx.textAlign = 'center';
        ctx.textBaseline = 'top';
        for (const n of candidatos.slice(0, 60)) {
          const p = n._p;
          const tam = clamp(9 + p.s * 3, 10, n === sel ? 15 : 13);
          ctx.font = `${n === sel || n.tipo === 'agente' ? 600 : 500} ${tam}px Inter, system-ui, sans-serif`;
          let txt = n.rotulo || '';
          if (txt.length > 34) txt = txt.slice(0, 32) + '…';
          const larg = ctx.measureText(txt).width;
          const x = p.x, y = p.y + n._r + 5;
          const caixa = [x - larg / 2 - 4, y - 2, larg + 8, tam + 5];
          if (n !== sel && ocupado.some(o => caixa[0] < o[0] + o[2] && caixa[0] + caixa[2] > o[0] &&
            caixa[1] < o[1] + o[3] && caixa[1] + caixa[3] > o[1])) continue;
          ocupado.push(caixa);
          ctx.globalAlpha = n === sel ? 1 : prof(p.z) * 0.95;
          ctx.fillStyle = 'rgba(6,10,20,0.66)';
          ctx.fillRect(caixa[0], caixa[1], caixa[2], caixa[3]);
          ctx.fillStyle = n.tipo === 'agente' ? n.cor : '#e8eefc';
          ctx.fillText(txt, x, y);
        }
        ctx.globalAlpha = 1;
      }
    }
  }

  global.Motor3D = Motor;
})(window);
