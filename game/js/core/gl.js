// Thin WebGL2 wrapper — programs, meshes, textures, render targets.
// Everything the renderer touches goes through here so state handling stays in
// one place.

export class GLContext {
  constructor(canvas, opts = {}) {
    const attrs = {
      alpha: false,
      antialias: opts.antialias !== false,
      depth: true,
      stencil: false,
      powerPreference: 'high-performance',
      preserveDrawingBuffer: false,
      desynchronized: true,
    };
    const gl = canvas.getContext('webgl2', attrs);
    if (!gl) throw new Error('WEBGL2_UNAVAILABLE');
    this.gl = gl;
    this.canvas = canvas;
    this.floatLinear = !!gl.getExtension('OES_texture_float_linear');
    this.colorBufferFloat = !!gl.getExtension('EXT_color_buffer_float');
    this.aniso = gl.getExtension('EXT_texture_filter_anisotropic');
    this.maxAniso = this.aniso
      ? gl.getParameter(this.aniso.MAX_TEXTURE_MAX_ANISOTROPY_EXT)
      : 1;
    this._program = null;
    this.drawCalls = 0;
    this.triangles = 0;
  }

  resize(width, height, dpr) {
    const w = Math.max(1, Math.round(width * dpr));
    const h = Math.max(1, Math.round(height * dpr));
    if (this.canvas.width !== w || this.canvas.height !== h) {
      this.canvas.width = w;
      this.canvas.height = h;
      return true;
    }
    return false;
  }

  useProgram(program) {
    if (this._program !== program) {
      this.gl.useProgram(program.handle);
      this._program = program;
    }
    return program;
  }

  beginFrame() {
    this.drawCalls = 0;
    this.triangles = 0;
  }
}

/* -------------------------------------------------------------- program --- */

export class Program {
  constructor(ctx, vertSrc, fragSrc, name = 'program') {
    const gl = ctx.gl;
    this.ctx = ctx;
    this.name = name;
    const vs = compile(gl, gl.VERTEX_SHADER, vertSrc, name + ':vert');
    const fs = compile(gl, gl.FRAGMENT_SHADER, fragSrc, name + ':frag');
    const p = gl.createProgram();
    gl.attachShader(p, vs);
    gl.attachShader(p, fs);
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) {
      throw new Error(`Link failed (${name}): ${gl.getProgramInfoLog(p)}`);
    }
    gl.deleteShader(vs);
    gl.deleteShader(fs);
    this.handle = p;
    // Keep the declared type and array length so `set` can dispatch correctly —
    // a vec4[16] arrives as 64 floats and must not go through uniform1fv.
    this.uniforms = new Map();
    const count = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
    for (let i = 0; i < count; i++) {
      const info = gl.getActiveUniform(p, i);
      const base = info.name.replace(/\[0\]$/, '');
      this.uniforms.set(base, {
        loc: gl.getUniformLocation(p, info.name),
        type: info.type,
        size: info.size,
      });
    }
    this._cache = new Map();
  }

  loc(name) {
    const u = this.uniforms.get(name);
    return u ? u.loc : null;
  }

  set(name, value) {
    const gl = this.ctx.gl;
    const u = this.uniforms.get(name);
    if (!u) return this;
    const l = u.loc;
    if (typeof value === 'boolean') { gl.uniform1i(l, value ? 1 : 0); return this; }
    if (typeof value === 'number') {
      if (this._cache.get(name) === value) return this;
      this._cache.set(name, value);
      if (u.type === gl.INT || u.type === gl.BOOL) gl.uniform1i(l, value);
      else gl.uniform1f(l, value);
      return this;
    }
    switch (u.type) {
      case gl.FLOAT: gl.uniform1fv(l, value); break;
      case gl.FLOAT_VEC2: gl.uniform2fv(l, value); break;
      case gl.FLOAT_VEC3: gl.uniform3fv(l, value); break;
      case gl.FLOAT_VEC4: gl.uniform4fv(l, value); break;
      case gl.FLOAT_MAT3: gl.uniformMatrix3fv(l, false, value); break;
      case gl.FLOAT_MAT4: gl.uniformMatrix4fv(l, false, value); break;
      case gl.INT: case gl.BOOL: gl.uniform1iv(l, value); break;
      default: gl.uniform1fv(l, value);
    }
    return this;
  }

  setInt(name, v) {
    const l = this.loc(name);
    if (l !== null) this.ctx.gl.uniform1i(l, v);
    return this;
  }

  setTexture(name, texture, unit) {
    const gl = this.ctx.gl;
    const l = this.loc(name);
    if (l === null) return this;
    gl.activeTexture(gl.TEXTURE0 + unit);
    gl.bindTexture(texture.target || gl.TEXTURE_2D, texture.handle ?? texture);
    gl.uniform1i(l, unit);
    return this;
  }
}

function compile(gl, type, src, label) {
  const s = gl.createShader(type);
  gl.shaderSource(s, src);
  gl.compileShader(s);
  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
    const log = gl.getShaderInfoLog(s);
    const numbered = src.split('\n').map((l, i) => `${i + 1}: ${l}`).join('\n');
    throw new Error(`Shader compile failed (${label}): ${log}\n${numbered}`);
  }
  return s;
}

/* ----------------------------------------------------------------- mesh --- */
// Interleaved vertex buffer + index buffer wrapped in a VAO.
// Layout entries: { name, size, type?, normalized?, offset } with a shared stride.

export class Mesh {
  constructor(ctx, { vertices, indices, layout, stride, dynamic = false, instanceLayout = null }) {
    const gl = ctx.gl;
    this.ctx = ctx;
    this.vao = gl.createVertexArray();
    this.vbo = gl.createBuffer();
    this.stride = stride;
    this.dynamic = dynamic;
    this.instanceBuffer = null;
    this.instanceCount = 0;

    gl.bindVertexArray(this.vao);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo);
    gl.bufferData(gl.ARRAY_BUFFER, vertices, dynamic ? gl.DYNAMIC_DRAW : gl.STATIC_DRAW);
    this.vertexCapacity = vertices.byteLength;
    bindLayout(gl, layout, stride, 0);

    if (indices) {
      this.ibo = gl.createBuffer();
      gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, this.ibo);
      gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, indices, dynamic ? gl.DYNAMIC_DRAW : gl.STATIC_DRAW);
      this.count = indices.length;
      this.indexType = indices instanceof Uint32Array ? gl.UNSIGNED_INT : gl.UNSIGNED_SHORT;
    } else {
      this.ibo = null;
      this.count = vertices.byteLength / stride;
      this.indexType = 0;
    }

    if (instanceLayout) {
      this.instanceBuffer = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, this.instanceBuffer);
      gl.bufferData(gl.ARRAY_BUFFER, instanceLayout.data, gl.DYNAMIC_DRAW);
      bindLayout(gl, instanceLayout.layout, instanceLayout.stride, 1);
      this.instanceStride = instanceLayout.stride;
    }

    gl.bindVertexArray(null);
    this.mode = gl.TRIANGLES;
  }

  updateVertices(data, count) {
    const gl = this.ctx.gl;
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo);
    if (data.byteLength > this.vertexCapacity) {
      gl.bufferData(gl.ARRAY_BUFFER, data, gl.DYNAMIC_DRAW);
      this.vertexCapacity = data.byteLength;
    } else {
      gl.bufferSubData(gl.ARRAY_BUFFER, 0, data, 0, count * (this.stride / data.BYTES_PER_ELEMENT));
    }
    if (!this.ibo) this.count = count;
  }

  // Partial upload — used by the tire-mark ring buffer, which only ever
  // rewrites the handful of quads laid down this frame.
  updateVertexRange(data, byteOffset) {
    const gl = this.ctx.gl;
    gl.bindBuffer(gl.ARRAY_BUFFER, this.vbo);
    gl.bufferSubData(gl.ARRAY_BUFFER, byteOffset, data);
  }

  updateInstances(data, count) {
    const gl = this.ctx.gl;
    gl.bindBuffer(gl.ARRAY_BUFFER, this.instanceBuffer);
    gl.bufferData(gl.ARRAY_BUFFER, data, gl.DYNAMIC_DRAW);
    this.instanceCount = count;
  }

  setIndexCount(n) { this.count = n; }

  draw(count = this.count, offset = 0) {
    if (count <= 0) return;
    const gl = this.ctx.gl;
    gl.bindVertexArray(this.vao);
    if (this.ibo) {
      const byteSize = this.indexType === gl.UNSIGNED_INT ? 4 : 2;
      if (this.instanceBuffer && this.instanceCount) {
        gl.drawElementsInstanced(this.mode, count, this.indexType, offset * byteSize, this.instanceCount);
      } else {
        gl.drawElements(this.mode, count, this.indexType, offset * byteSize);
      }
    } else if (this.instanceBuffer && this.instanceCount) {
      gl.drawArraysInstanced(this.mode, offset, count, this.instanceCount);
    } else {
      gl.drawArrays(this.mode, offset, count);
    }
    this.ctx.drawCalls++;
    this.ctx.triangles += count / 3;
  }

  dispose() {
    const gl = this.ctx.gl;
    gl.deleteVertexArray(this.vao);
    gl.deleteBuffer(this.vbo);
    if (this.ibo) gl.deleteBuffer(this.ibo);
    if (this.instanceBuffer) gl.deleteBuffer(this.instanceBuffer);
  }
}

function bindLayout(gl, layout, stride, divisor) {
  for (const a of layout) {
    const type = a.type ?? gl.FLOAT;
    gl.enableVertexAttribArray(a.index);
    if (type === gl.FLOAT || a.normalized) {
      gl.vertexAttribPointer(a.index, a.size, type, !!a.normalized, stride, a.offset);
    } else {
      gl.vertexAttribIPointer(a.index, a.size, type, stride, a.offset);
    }
    if (divisor) gl.vertexAttribDivisor(a.index, divisor);
  }
}

/* -------------------------------------------------------------- texture --- */

export class Texture {
  constructor(ctx, opts = {}) {
    const gl = ctx.gl;
    this.ctx = ctx;
    this.target = gl.TEXTURE_2D;
    this.handle = gl.createTexture();
    this.width = opts.width || 1;
    this.height = opts.height || 1;
    gl.bindTexture(gl.TEXTURE_2D, this.handle);
    const internal = opts.internalFormat ?? gl.RGBA8;
    const format = opts.format ?? gl.RGBA;
    const type = opts.type ?? gl.UNSIGNED_BYTE;
    if (opts.source) {
      gl.texImage2D(gl.TEXTURE_2D, 0, internal, format, type, opts.source);
      this.width = opts.source.width;
      this.height = opts.source.height;
    } else {
      gl.texImage2D(gl.TEXTURE_2D, 0, internal, this.width, this.height, 0, format, type, opts.data ?? null);
    }
    const filter = opts.filter ?? gl.LINEAR;
    const wrap = opts.wrap ?? gl.CLAMP_TO_EDGE;
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, wrap);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, wrap);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, filter);
    if (opts.mipmap) {
      gl.generateMipmap(gl.TEXTURE_2D);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
      if (ctx.aniso) {
        gl.texParameterf(gl.TEXTURE_2D, ctx.aniso.TEXTURE_MAX_ANISOTROPY_EXT, Math.min(8, ctx.maxAniso));
      }
    } else {
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, filter);
    }
    if (opts.compare) {
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_COMPARE_MODE, gl.COMPARE_REF_TO_TEXTURE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_COMPARE_FUNC, gl.LEQUAL);
    }
    gl.bindTexture(gl.TEXTURE_2D, null);
  }

  bind(unit) {
    const gl = this.ctx.gl;
    gl.activeTexture(gl.TEXTURE0 + unit);
    gl.bindTexture(gl.TEXTURE_2D, this.handle);
  }

  dispose() { this.ctx.gl.deleteTexture(this.handle); }
}

/* --------------------------------------------------------- framebuffer --- */

export class RenderTarget {
  constructor(ctx, width, height, opts = {}) {
    const gl = ctx.gl;
    this.ctx = ctx;
    this.width = width;
    this.height = height;
    this.fbo = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, this.fbo);

    if (opts.depthOnly) {
      this.depth = new Texture(ctx, {
        width, height,
        internalFormat: gl.DEPTH_COMPONENT24,
        format: gl.DEPTH_COMPONENT,
        type: gl.UNSIGNED_INT,
        filter: opts.compare ? gl.LINEAR : gl.NEAREST,
        wrap: gl.CLAMP_TO_EDGE,
        compare: opts.compare,
      });
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.DEPTH_ATTACHMENT, gl.TEXTURE_2D, this.depth.handle, 0);
      gl.drawBuffers([gl.NONE]);
      gl.readBuffer(gl.NONE);
    } else {
      const hdr = opts.hdr && ctx.colorBufferFloat;
      this.color = new Texture(ctx, {
        width, height,
        internalFormat: hdr ? gl.RGBA16F : gl.RGBA8,
        format: gl.RGBA,
        type: hdr ? gl.HALF_FLOAT : gl.UNSIGNED_BYTE,
        filter: gl.LINEAR,
      });
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, this.color.handle, 0);
      if (opts.depth !== false) {
        this.depthBuffer = gl.createRenderbuffer();
        gl.bindRenderbuffer(gl.RENDERBUFFER, this.depthBuffer);
        gl.renderbufferStorage(gl.RENDERBUFFER, gl.DEPTH_COMPONENT24, width, height);
        gl.framebufferRenderbuffer(gl.FRAMEBUFFER, gl.DEPTH_ATTACHMENT, gl.RENDERBUFFER, this.depthBuffer);
      }
    }
    const status = gl.checkFramebufferStatus(gl.FRAMEBUFFER);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    if (status !== gl.FRAMEBUFFER_COMPLETE) {
      throw new Error(`Framebuffer incomplete: 0x${status.toString(16)}`);
    }
  }

  bind() {
    const gl = this.ctx.gl;
    gl.bindFramebuffer(gl.FRAMEBUFFER, this.fbo);
    gl.viewport(0, 0, this.width, this.height);
  }

  resize(width, height) {
    if (this.width === width && this.height === height) return;
    const gl = this.ctx.gl;
    this.width = width;
    this.height = height;
    gl.bindFramebuffer(gl.FRAMEBUFFER, this.fbo);
    if (this.color) {
      gl.bindTexture(gl.TEXTURE_2D, this.color.handle);
      const hdr = this.ctx.colorBufferFloat;
      gl.texImage2D(gl.TEXTURE_2D, 0, hdr ? gl.RGBA16F : gl.RGBA8, width, height, 0,
        gl.RGBA, hdr ? gl.HALF_FLOAT : gl.UNSIGNED_BYTE, null);
      this.color.width = width; this.color.height = height;
    }
    if (this.depthBuffer) {
      gl.bindRenderbuffer(gl.RENDERBUFFER, this.depthBuffer);
      gl.renderbufferStorage(gl.RENDERBUFFER, gl.DEPTH_COMPONENT24, width, height);
    }
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
  }

  dispose() {
    const gl = this.ctx.gl;
    gl.deleteFramebuffer(this.fbo);
    if (this.color) this.color.dispose();
    if (this.depth) this.depth.dispose();
    if (this.depthBuffer) gl.deleteRenderbuffer(this.depthBuffer);
  }
}

/* -------------------------------------------------------------- helpers --- */

export function fullscreenTriangle(ctx) {
  // Single oversized triangle — cheaper than a quad and avoids the diagonal seam.
  const verts = new Float32Array([-1, -1, 3, -1, -1, 3]);
  return new Mesh(ctx, {
    vertices: verts,
    layout: [{ index: 0, size: 2, offset: 0 }],
    stride: 8,
  });
}
