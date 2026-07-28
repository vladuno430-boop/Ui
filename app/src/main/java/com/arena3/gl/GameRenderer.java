package com.arena3.gl;

import android.opengl.GLES30;
import android.opengl.GLSurfaceView;

import com.arena3.audio.SoundEngine;
import com.arena3.core.Mat4;
import com.arena3.core.MathUtil;
import com.arena3.core.Vec3;
import com.arena3.game.CollisionWorld;
import com.arena3.game.GameEvent;
import com.arena3.game.GameWorld;
import com.arena3.game.ItemDef;
import com.arena3.game.ItemEntity;
import com.arena3.game.MapDef;
import com.arena3.game.MoveInput;
import com.arena3.game.PlayerState;
import com.arena3.game.Projectile;
import com.arena3.game.WeaponDef;
import com.arena3.render.FighterPose;
import com.arena3.render.MeshBuilder;
import com.arena3.render.Models;
import com.arena3.render.ParticleSystem;
import com.arena3.render.WorldGeometry;
import com.arena3.ui.Settings;
import com.arena3.ui.TouchControls;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Draws the game and drives the simulation from the render loop. */
public final class GameRenderer implements GLSurfaceView.Renderer {

    private static final int MAX_PARTICLE_QUADS = 1400;
    private static final float VIEW_FOV = 90f;
    private static final float GUN_FOV = 62f;

    public interface Listener {
        void onPauseRequested();

        void onMatchEnded(GameWorld world);
    }

    private final GameWorld world;
    private final Settings settings;
    private final TouchControls controls;
    private final SoundEngine sound;
    private final Listener listener;

    private final Hud hud = new Hud();
    private final ParticleSystem particles = new ParticleSystem();

    private Textures textures;
    private ShaderProgram worldProgram, skyProgram, modelProgram, particleProgram, hudProgram;
    private SpriteBatch sprites;

    private Mesh worldMesh, skyMesh, particleMesh;
    private Mesh[] fighterParts;
    private final Mesh[] weaponMeshes = new Mesh[WeaponDef.COUNT];
    private final Mesh[] itemMeshes = new Mesh[ItemDef.ALL.length];
    private final Mesh[] projectileMeshes = new Mesh[WeaponDef.COUNT];
    private final Mesh[] gibMeshes = new Mesh[3];

    private int viewWidth = 1, viewHeight = 1;
    private long lastFrameNanos;
    private boolean paused;
    private boolean matchEndNotified;

    // camera state
    private final Vec3 eye = new Vec3();
    private final Vec3 forward = new Vec3();
    private final Vec3 right = new Vec3();
    private final Vec3 up = new Vec3();
    private final Mat4 view = new Mat4();
    private final Mat4 projection = new Mat4();
    private final Mat4 viewProj = new Mat4();
    private final Mat4 model = new Mat4();
    private final Mat4 gunProj = new Mat4();
    private final Mat4 gunView = new Mat4();
    private final Mat4 gunVp = new Mat4();
    private float viewRoll;
    private float landDip;
    private float damageKick;
    private float fireKick;
    private float gunSwayX, gunSwayY;

    // dynamic lights: xyz + radius, and colour
    private static final int MAX_LIGHTS = Shaders.MAX_DYNAMIC_LIGHTS;
    private final float[] lightPos = new float[MAX_LIGHTS * 4];
    private final float[] lightColor = new float[MAX_LIGHTS * 3];
    private int lightCount;
    // transient flashes from explosions
    private static final int MAX_FLASHES = 24;
    private final float[] flashData = new float[MAX_FLASHES * 8]; // x y z radius r g b life
    private int flashCount;

    private final float[] particleScratch =
            new float[MAX_PARTICLE_QUADS * 4 * ParticleSystem.VERTEX_FLOATS];

    private final Vec3 tmp = new Vec3();
    private final Vec3 tmp2 = new Vec3();
    private final MoveInput localInput = new MoveInput();
    private final Mat4[] poseMatrices = FighterPose.allocate();

    public GameRenderer(GameWorld world, Settings settings, TouchControls controls,
                        SoundEngine sound, Listener listener) {
        this.world = world;
        this.settings = settings;
        this.controls = controls;
        this.sound = sound;
        this.listener = listener;
    }

    public GameWorld world() {
        return world;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (paused) controls.releaseAll();
        lastFrameNanos = 0;
    }

    public boolean isPaused() {
        return paused;
    }

    // ------------------------------------------------------------------ setup

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        GLES30.glClearColor(0.02f, 0.03f, 0.05f, 1f);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_CULL_FACE);
        GLES30.glCullFace(GLES30.GL_BACK);
        GLES30.glFrontFace(GLES30.GL_CCW);

        textures = new Textures();
        textures.create();

        worldProgram = new ShaderProgram(Shaders.WORLD_VS, Shaders.WORLD_FS, "world");
        skyProgram = new ShaderProgram(Shaders.SKY_VS, Shaders.SKY_FS, "sky");
        modelProgram = new ShaderProgram(Shaders.MODEL_VS, Shaders.MODEL_FS, "model");
        particleProgram = new ShaderProgram(Shaders.PARTICLE_VS, Shaders.PARTICLE_FS, "particle");
        hudProgram = new ShaderProgram(Shaders.HUD_VS, Shaders.HUD_FS, "hud");
        sprites = new SpriteBatch(hudProgram, textures);

        buildWorldMeshes();
        buildModelMeshes();

        particleMesh = new Mesh(new int[]{3, 2, 4}, true, MAX_PARTICLE_QUADS * 4);
        particleMesh.uploadIndices(Mesh.quadIndices(MAX_PARTICLE_QUADS), MAX_PARTICLE_QUADS * 6);

        hud.reset();
        lastFrameNanos = 0;
    }

    private void buildWorldMeshes() {
        WorldGeometry geometry = new WorldGeometry();
        geometry.build(world.map, world.collision);

        worldMesh = new Mesh(new int[]{3, 3, 2, 3, 1}, false, 0);
        worldMesh.upload(geometry.verts, geometry.vertexCount * WorldGeometry.VERTEX_FLOATS,
                geometry.indices, geometry.indexCount);

        // The sky pass only needs positions, but it shares the vertex layout.
        skyMesh = new Mesh(new int[]{3, 3, 2, 3, 1}, false, 0);
        skyMesh.upload(geometry.skyVerts, geometry.skyVertexCount * WorldGeometry.VERTEX_FLOATS,
                geometry.skyIndices, geometry.skyIndexCount);
    }

    private void buildModelMeshes() {
        MeshBuilder.MeshData[] parts = Models.fighter(0.9f, 0.62f, 0.18f);
        fighterParts = new Mesh[parts.length];
        for (int i = 0; i < parts.length; i++) fighterParts[i] = upload(parts[i]);

        for (int i = 0; i < WeaponDef.COUNT; i++) weaponMeshes[i] = upload(Models.weapon(i));
        for (int i = 0; i < ItemDef.ALL.length; i++) itemMeshes[i] = upload(Models.item(i));
        for (int i = 0; i < WeaponDef.COUNT; i++) projectileMeshes[i] = upload(Models.projectile(i));
        for (int i = 0; i < gibMeshes.length; i++) gibMeshes[i] = upload(Models.gib(i));
    }

    private Mesh upload(MeshBuilder.MeshData data) {
        Mesh mesh = new Mesh(new int[]{3, 3, 3}, false, 0);
        mesh.upload(data.verts, data.verts.length, data.indices, data.indexCount);
        return mesh;
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        viewWidth = Math.max(1, width);
        viewHeight = Math.max(1, height);
        GLES30.glViewport(0, 0, viewWidth, viewHeight);
        controls.setViewport(viewWidth, viewHeight);
    }

    // ------------------------------------------------------------------ frame

    @Override
    public void onDrawFrame(GL10 unused) {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 1f / 60f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        dt = Math.min(dt, 0.1f);

        if (controls.consumePause() && !paused && listener != null) {
            listener.onPauseRequested();
        }

        if (!paused) {
            step(dt);
        }
        drawScene();
        drawInterface(dt);
    }

    private void step(float dt) {
        gatherInput();
        world.update(dt);
        consumeEvents();
        hud.consume(world);
        hud.update(dt);
        particles.update(dt, world.collision);
        updateFlashes(dt);
        updateCameraEffects(dt);

        if (world.state == GameWorld.STATE_OVER && !matchEndNotified) {
            matchEndNotified = true;
            if (listener != null) listener.onMatchEnded(world);
        }
    }

    private void gatherInput() {
        PlayerState me = world.localPlayer();
        localInput.clear();
        controls.fill(localInput, true);
        localInput.deltaYaw *= settings.sensitivity();
        localInput.deltaPitch *= settings.sensitivity();

        if (controls.consumeWeaponTap()) {
            localInput.selectWeapon = nextWeapon(me);
        }
        if (!me.alive && controls.consumeTap()) {
            world.requestRespawn(me.index);
        }
        world.inputs[0].forward = localInput.forward;
        world.inputs[0].right = localInput.right;
        world.inputs[0].jump = localInput.jump;
        world.inputs[0].crouch = localInput.crouch;
        world.inputs[0].fire = localInput.fire;
        world.inputs[0].selectWeapon = localInput.selectWeapon;
        world.inputs[0].deltaYaw = localInput.deltaYaw;
        world.inputs[0].deltaPitch = localInput.deltaPitch;
    }

    /** Cycles to the next owned weapon that has ammo. */
    private int nextWeapon(PlayerState me) {
        for (int step = 1; step <= WeaponDef.COUNT; step++) {
            int w = (me.weapon + step) % WeaponDef.COUNT;
            if (!me.hasWeapon(w)) continue;
            WeaponDef def = WeaponDef.get(w);
            if (def.usesAmmo() && me.ammo[w] < def.ammoPerShot) continue;
            return w;
        }
        return -1;
    }

    // ----------------------------------------------------------------- events

    private void consumeEvents() {
        PlayerState me = world.localPlayer();
        for (GameEvent ev : world.events) {
            switch (ev.type) {
                case GameEvent.FIRE: {
                    WeaponDef def = WeaponDef.get(ev.a);
                    if (def.style != WeaponDef.STYLE_PROJECTILE || ev.a == WeaponDef.GRENADE) {
                        tmp.setMa(ev.pos, ev.dir, 24f);
                        particles.muzzleFlash(tmp, 1.6f, 1.1f, 0.5f);
                        addFlash(tmp, 190f, 1.0f, 0.75f, 0.35f, 0.07f);
                    }
                    if (ev.b == me.index) fireKick = 1f;
                    sound.weaponFire(ev.a, ev.pos, ev.b == me.index);
                    break;
                }
                case GameEvent.IMPACT:
                    if (ev.a == -2) {
                        sound.grenadeBounce(ev.pos);
                    } else {
                        particles.bulletImpact(ev.pos, ev.dir);
                        sound.impact(ev.pos);
                    }
                    break;
                case GameEvent.IMPACT_FLESH:
                    if (settings.gore()) particles.blood(ev.pos, ev.dir, 20);
                    sound.fleshHit(ev.pos);
                    break;
                case GameEvent.EXPLOSION: {
                    float r = 1.7f, g = 0.85f, b = 0.35f;
                    if (ev.a == WeaponDef.PLASMA) {
                        r = 0.45f;
                        g = 0.7f;
                        b = 1.8f;
                    } else if (ev.a == WeaponDef.VOIDCANNON) {
                        r = 0.9f;
                        g = 0.45f;
                        b = 1.8f;
                    }
                    particles.explosion(ev.pos, Math.max(60f, ev.f), r, g, b);
                    addFlash(ev.pos, ev.f * 3.2f, r, g, b, 0.35f);
                    sound.explosion(ev.pos, ev.a);
                    break;
                }
                case GameEvent.RAIL_TRAIL: {
                    float[] c = Models.PLAYER_COLORS[world.players[ev.b].colorIndex
                            % Models.PLAYER_COLORS.length];
                    particles.railTrail(ev.pos, ev.to, c[0] * 1.6f, c[1] * 1.6f, c[2] * 1.6f);
                    break;
                }
                case GameEvent.BEAM:
                    particles.beam(ev.pos, ev.to, 0.55f, 0.80f, 1.70f, 3.5f, 0.06f);
                    particles.sparkBurst(ev.to, 0.6f, 0.85f, 1.8f, 2, 160f);
                    addFlash(ev.to, 200f, 0.4f, 0.6f, 1.4f, 0.06f);
                    break;
                case GameEvent.PICKUP:
                    sound.pickup(ev.a, ev.pos, ev.b == me.index);
                    particles.sparkBurst(ev.pos, 1.2f, 1.0f, 0.5f, 6, 90f);
                    break;
                case GameEvent.JUMP:
                    sound.jump(ev.pos, ev.b == me.index);
                    break;
                case GameEvent.LAND:
                    if (ev.f > 260f) {
                        particles.landPuff(ev.pos, ev.f);
                        sound.land(ev.pos, ev.f, ev.b == me.index);
                        if (ev.b == me.index) landDip = Math.min(1f, ev.f / 700f);
                    }
                    break;
                case GameEvent.PAIN:
                    if (ev.b == me.index) damageKick = Math.min(1f, damageKick + ev.a / 70f);
                    sound.pain(ev.pos, ev.b == me.index);
                    break;
                case GameEvent.DEATH:
                    if (settings.gore() && ev.a == 1) {
                        spawnGibs(ev.pos);
                        particles.blood(ev.pos, tmp.set(0, 0, 1), 60);
                    }
                    sound.death(ev.pos, ev.a == 1);
                    break;
                case GameEvent.TELEPORT:
                    particles.teleportFlare(ev.pos);
                    addFlash(ev.pos, 260f, 0.6f, 0.4f, 1.6f, 0.25f);
                    sound.teleport(ev.pos);
                    break;
                case GameEvent.JUMPPAD:
                    particles.padJet(ev.pos);
                    sound.jumpPad(ev.pos);
                    break;
                case GameEvent.RESPAWN:
                    particles.teleportFlare(ev.pos);
                    sound.teleport(ev.pos);
                    break;
                case GameEvent.HIT_CONFIRM:
                    sound.hitConfirm();
                    break;
                case GameEvent.ANNOUNCE:
                    if (ev.b < 0 || ev.b == me.index) sound.announce();
                    break;
                case GameEvent.NO_AMMO:
                    if (ev.b == me.index) sound.noAmmo();
                    break;
                case GameEvent.WEAPON_SWITCH:
                    if (ev.b == me.index) sound.weaponSwitch();
                    break;
                default:
                    break;
            }
        }
        sound.setListener(eye, forward, right);
    }

    /** Gib chunks are pure decoration, thrown by the particle system. */
    private void spawnGibs(Vec3 at) {
        for (int i = 0; i < 8; i++) {
            particles.sparkBurst(at, 0.45f, 0.08f, 0.08f, 3, 260f);
        }
    }

    private void addFlash(Vec3 at, float radius, float r, float g, float b, float life) {
        if (flashCount >= MAX_FLASHES) return;
        int o = flashCount * 8;
        flashData[o] = at.x;
        flashData[o + 1] = at.y;
        flashData[o + 2] = at.z;
        flashData[o + 3] = radius;
        flashData[o + 4] = r;
        flashData[o + 5] = g;
        flashData[o + 6] = b;
        flashData[o + 7] = life;
        flashCount++;
    }

    private void updateFlashes(float dt) {
        for (int i = flashCount - 1; i >= 0; i--) {
            int o = i * 8;
            flashData[o + 7] -= dt;
            if (flashData[o + 7] <= 0f) {
                int last = (flashCount - 1) * 8;
                if (o != last) System.arraycopy(flashData, last, flashData, o, 8);
                flashCount--;
            }
        }
    }

    // ----------------------------------------------------------------- camera

    private void updateCameraEffects(float dt) {
        landDip = Math.max(0f, landDip - dt * 3.2f);
        damageKick = Math.max(0f, damageKick - dt * 2.4f);
        fireKick = Math.max(0f, fireKick - dt * 7f);

        PlayerState me = world.localPlayer();
        float targetRoll = -me.velocity.dot(rightOf(me)) * 0.012f;
        viewRoll = MathUtil.approach(viewRoll, MathUtil.clamp(targetRoll, -2.6f, 2.6f), 9f, dt);

        // Weapon sway trails the view a little.
        gunSwayX = MathUtil.approach(gunSwayX, MathUtil.clamp(world.inputs[0].deltaYaw * 0.5f, -6f, 6f), 7f, dt);
        gunSwayY = MathUtil.approach(gunSwayY, MathUtil.clamp(world.inputs[0].deltaPitch * 0.5f, -6f, 6f), 7f, dt);
    }

    private final Vec3 rightScratch = new Vec3();

    private Vec3 rightOf(PlayerState ps) {
        MathUtil.angleVectors(0f, ps.yaw, tmp2, rightScratch, tmp);
        return rightScratch;
    }

    private void setupCamera() {
        PlayerState me = world.localPlayer();
        float pitch = me.pitch;
        float yaw = me.yaw;

        me.eyePosition(eye);

        if (me.alive) {
            // Vertical bob while running, plus the dip on landing.
            float bob = (float) Math.sin(me.bobCycle * 6.2831855f) * 2.6f * me.bobFraction;
            eye.z += bob - landDip * 9f;
            pitch += landDip * 3.5f + damageKick * 2.5f;
        } else {
            // Death camera: sink to the floor and watch whoever did it.
            eye.z = me.origin.z + 6f;
            if (me.lastAttacker >= 0 && me.lastAttacker != me.index) {
                PlayerState killer = world.players[me.lastAttacker];
                tmp.setSub(killer.origin, eye);
                if (tmp.normalize() > 1f) {
                    yaw = MathUtil.angleTowards(me.yaw, MathUtil.yawOf(tmp), 240f * 0.016f);
                    me.yaw = yaw;
                    pitch = MathUtil.pitchOf(tmp);
                }
            }
        }

        MathUtil.angleVectors(pitch, yaw, forward, right, up);

        // Roll the basis around the view axis for the strafe lean.
        if (Math.abs(viewRoll) > 0.01f) {
            float s = (float) Math.sin(viewRoll * MathUtil.DEG2RAD);
            float c = (float) Math.cos(viewRoll * MathUtil.DEG2RAD);
            float rx = right.x * c + up.x * s;
            float ry = right.y * c + up.y * s;
            float rz = right.z * c + up.z * s;
            up.set(up.x * c - right.x * s, up.y * c - right.y * s, up.z * c - right.z * s);
            right.set(rx, ry, rz);
        }

        float aspect = viewWidth / (float) viewHeight;
        float fov = settings.fov();
        projection.perspective(verticalFov(fov, aspect), aspect, 4f, 12000f);
        view.view(eye, forward, right, up);
        viewProj.setMul(projection, view);
    }

    /** Converts a horizontal field of view into the vertical one GL wants. */
    private static float verticalFov(float horizontalDegrees, float aspect) {
        double h = Math.toRadians(horizontalDegrees) * 0.5;
        double v = Math.atan(Math.tan(h) / Math.max(0.2, aspect));
        return (float) Math.toDegrees(v * 2.0);
    }

    // ------------------------------------------------------------------- draw

    private void drawScene() {
        setupCamera();
        collectLights();

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthMask(true);
        GLES30.glDisable(GLES30.GL_BLEND);

        drawWorld();
        drawSky();
        drawEntities();
        drawEffects();
        drawViewModel();
    }

    private void collectLights() {
        lightCount = 0;
        // Projectiles in flight light their surroundings.
        for (Projectile p : world.projectiles) {
            if (!p.active || lightCount >= MAX_LIGHTS) continue;
            float r, g, b, radius;
            switch (p.weapon) {
                case WeaponDef.ROCKET: r = 1.1f; g = 0.55f; b = 0.20f; radius = 260f; break;
                case WeaponDef.PLASMA: r = 0.30f; g = 0.55f; b = 1.30f; radius = 190f; break;
                case WeaponDef.GRENADE: r = 0.45f; g = 0.60f; b = 0.30f; radius = 150f; break;
                default: r = 0.75f; g = 0.35f; b = 1.40f; radius = 300f; break;
            }
            pushLight(p.origin.x, p.origin.y, p.origin.z, radius, r, g, b);
        }
        for (int i = 0; i < flashCount && lightCount < MAX_LIGHTS; i++) {
            int o = i * 8;
            float fade = Math.min(1f, flashData[o + 7] * 6f);
            pushLight(flashData[o], flashData[o + 1], flashData[o + 2], flashData[o + 3],
                    flashData[o + 4] * fade, flashData[o + 5] * fade, flashData[o + 6] * fade);
        }
    }

    private void pushLight(float x, float y, float z, float radius, float r, float g, float b) {
        if (lightCount >= MAX_LIGHTS) return;
        int o = lightCount * 4;
        lightPos[o] = x;
        lightPos[o + 1] = y;
        lightPos[o + 2] = z;
        lightPos[o + 3] = radius;
        int c = lightCount * 3;
        lightColor[c] = r;
        lightColor[c + 1] = g;
        lightColor[c + 2] = b;
        lightCount++;
    }

    private void applyLightUniforms(ShaderProgram program) {
        program.set("uLightCount", lightCount);
        if (lightCount > 0) {
            program.setVec4Array("uLightPos", lightPos, lightCount);
            program.setVec3Array("uLightColor", lightColor, lightCount);
        }
    }

    private void drawWorld() {
        MapDef map = world.map;
        worldProgram.use();
        worldProgram.setMatrix("uViewProj", viewProj.m);
        worldProgram.set("uTex", 0);
        worldProgram.set("uEye", eye.x, eye.y, eye.z);
        worldProgram.set("uFogColor", map.fogColor.x, map.fogColor.y, map.fogColor.z);
        worldProgram.set("uFogRange", map.fogNear, map.fogFar);
        applyLightUniforms(worldProgram);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, textures.worldArray);
        worldMesh.draw();
    }

    private void drawSky() {
        if (skyMesh.indexCount() == 0) return;
        skyProgram.use();
        skyProgram.setMatrix("uViewProj", viewProj.m);
        skyProgram.set("uEye", eye.x, eye.y, eye.z);
        skyProgram.set("uStyle", world.map.skyStyle);
        skyProgram.set("uTime", world.matchTime);
        skyMesh.draw();
    }

    private void beginModels() {
        MapDef map = world.map;
        modelProgram.use();
        modelProgram.setMatrix("uViewProj", viewProj.m);
        modelProgram.set("uEye", eye.x, eye.y, eye.z);
        modelProgram.set("uFogColor", map.fogColor.x, map.fogColor.y, map.fogColor.z);
        modelProgram.set("uFogRange", map.fogNear, map.fogFar);
        modelProgram.set("uAmbient", map.ambient.x * 2.4f + 0.10f, map.ambient.y * 2.4f + 0.10f,
                map.ambient.z * 2.4f + 0.12f);
        modelProgram.set("uKeyDir", map.sunDir.x, map.sunDir.y, map.sunDir.z);
        modelProgram.set("uKeyColor", 0.55f, 0.55f, 0.60f);
        modelProgram.set("uTint", 1f, 1f, 1f);
        modelProgram.set("uEmissive", 0f);
        modelProgram.set("uAlpha", 1f);
        applyLightUniforms(modelProgram);
    }

    private void drawEntities() {
        beginModels();
        drawPlayers();
        drawItems();
        drawProjectiles();
    }

    private void drawPlayers() {
        PlayerState me = world.localPlayer();
        for (PlayerState ps : world.players) {
            if (ps.index == me.index) continue;
            if (!ps.alive && ps.deathTime > 6f) continue;

            float[] color = Models.PLAYER_COLORS[ps.colorIndex % Models.PLAYER_COLORS.length];
            float alpha = 1f;
            if (ps.hasPowerup(ItemDef.PW_INVIS)) alpha = 0.18f;
            if (alpha < 0.99f) {
                GLES30.glEnable(GLES30.GL_BLEND);
                GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
            }

            float tint = 1f;
            if (ps.painFlash > 0f) tint = 1f + ps.painFlash * 1.6f;
            if (ps.hasPowerup(ItemDef.PW_QUAD)) {
                modelProgram.set("uTint", tint * 0.6f, tint * 0.7f, tint * 1.8f);
            } else {
                modelProgram.set("uTint", tint, tint, tint);
            }
            modelProgram.set("uAlpha", alpha);

            poseFighter(ps);

            modelProgram.set("uTint", 1f, 1f, 1f);
            modelProgram.set("uAlpha", 1f);
            if (alpha < 0.99f) GLES30.glDisable(GLES30.GL_BLEND);
        }
    }

    /** Places and animates the seven parts of one fighter. */
    private void poseFighter(PlayerState ps) {
        FighterPose.compute(ps, poseMatrices);
        for (int i = 0; i < Models.PART_COUNT; i++) {
            modelProgram.setMatrix("uModel", poseMatrices[i].m);
            fighterParts[i].draw();
        }
        if (ps.alive) {
            modelProgram.setMatrix("uModel", poseMatrices[FighterPose.WEAPON_MATRIX].m);
            weaponMeshes[ps.weapon].draw();
        }
    }

    private void drawItems() {
        float t = world.matchTime;
        for (ItemEntity item : world.items) {
            if (!item.available()) continue;
            ItemDef def = item.def();
            float bob = (float) Math.sin(t * 2.4f + item.bobPhase) * 5f;
            model.identity();
            model.translate(item.origin.x, item.origin.y, item.origin.z + bob + 6f);
            model.rotateZ(item.spin);
            boolean powerup = def.category == ItemDef.CAT_POWERUP;
            modelProgram.set("uEmissive", powerup ? 0.65f : 0.18f);
            if (def.category == ItemDef.CAT_WEAPON) {
                model.rotateY(-14f);
                model.scale(0.8f, 0.8f, 0.8f);
                modelProgram.setMatrix("uModel", model.m);
                weaponMeshes[def.weapon].draw();
            } else {
                modelProgram.setMatrix("uModel", model.m);
                itemMeshes[def.id].draw();
            }
            modelProgram.set("uEmissive", 0f);
        }
    }

    private void drawProjectiles() {
        for (Projectile p : world.projectiles) {
            if (!p.active) continue;
            tmp.set(p.velocity);
            float speed = tmp.normalize();
            model.identity();
            model.translate(p.origin.x, p.origin.y, p.origin.z);
            if (speed > 1f) {
                model.rotateZ(MathUtil.yawOf(tmp));
                model.rotateY(MathUtil.pitchOf(tmp));
            }
            if (p.weapon == WeaponDef.GRENADE) model.rotateY(world.matchTime * 420f);
            modelProgram.set("uEmissive", p.weapon == WeaponDef.ROCKET ? 0.25f : 0.85f);
            modelProgram.setMatrix("uModel", model.m);
            projectileMeshes[p.weapon].draw();
            modelProgram.set("uEmissive", 0f);

            // Trails.
            tmp2.set(p.velocity);
            tmp2.normalize();
            tmp2.negate();
            if (p.weapon == WeaponDef.ROCKET) {
                particles.rocketTrail(p.origin, tmp2);
            } else if (p.weapon == WeaponDef.PLASMA || p.weapon == WeaponDef.VOIDCANNON) {
                particles.plasmaTrail(p.origin);
            }
        }
    }

    /** The player's own weapon, drawn over the scene with its own projection. */
    private void drawViewModel() {
        PlayerState me = world.localPlayer();
        if (!me.alive) return;

        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT);

        float aspect = viewWidth / (float) viewHeight;
        gunProj.perspective(verticalFov(GUN_FOV, aspect), aspect, 1f, 400f);
        gunView.view(eye, forward, right, up);
        gunVp.setMul(gunProj, gunView);

        modelProgram.use();
        modelProgram.setMatrix("uViewProj", gunVp.m);
        modelProgram.set("uEye", eye.x, eye.y, eye.z);
        modelProgram.set("uFogRange", 8000f, 9000f);
        modelProgram.set("uAmbient", 0.42f, 0.43f, 0.48f);
        modelProgram.set("uKeyDir", -0.4f, -0.3f, -0.86f);
        modelProgram.set("uKeyColor", 0.75f, 0.74f, 0.72f);
        modelProgram.set("uEmissive", 0f);
        modelProgram.set("uAlpha", 1f);
        applyLightUniforms(modelProgram);

        float bob = (float) Math.sin(me.bobCycle * 6.2831855f) * 1.6f * me.bobFraction;
        float bobSide = (float) Math.cos(me.bobCycle * 3.14159f) * 1.9f * me.bobFraction;
        float lower = me.weaponSwitchTime > 0f
                ? (1f - Math.abs(me.weaponSwitchTime - 0.125f) / 0.125f) * 14f : 0f;
        float recoil = fireKick * 4.5f;

        model.identity();
        model.translate(eye.x, eye.y, eye.z);
        model.rotateZ(me.yaw - gunSwayX * 0.35f);
        model.rotateY(me.pitch - gunSwayY * 0.35f + fireKick * 4f);
        model.translate(20f - recoil, -9f + bobSide, -11f + bob - lower);
        model.scale(0.62f, 0.62f, 0.62f);
        modelProgram.setMatrix("uModel", model.m);
        weaponMeshes[me.weapon].draw();
    }

    private void drawEffects() {
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glDepthMask(false);

        MapDef map = world.map;
        particleProgram.use();
        particleProgram.setMatrix("uViewProj", viewProj.m);
        particleProgram.set("uTex", 0);
        particleProgram.set("uEye", eye.x, eye.y, eye.z);
        particleProgram.set("uFogColor", map.fogColor.x, map.fogColor.y, map.fogColor.z);
        particleProgram.set("uFogRange", map.fogNear, map.fogFar);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures.particle);

        // Smoke and blood first, blended normally.
        particleProgram.set("uFogAmount", 1f);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        int quads = particles.buildQuads(particleScratch, MAX_PARTICLE_QUADS, right, up, false);
        if (quads > 0) {
            particleMesh.stream(particleScratch, quads * 4 * ParticleSystem.VERTEX_FLOATS);
            particleMesh.drawIndexed(quads * 6);
        }

        // Then everything that glows, added on top.
        particleProgram.set("uFogAmount", 0.25f);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);
        quads = particles.buildQuads(particleScratch, MAX_PARTICLE_QUADS, right, up, true);
        if (quads > 0) {
            particleMesh.stream(particleScratch, quads * 4 * ParticleSystem.VERTEX_FLOATS);
            particleMesh.drawIndexed(quads * 6);
        }
        int beamQuads = particles.buildBeamQuads(particleScratch, MAX_PARTICLE_QUADS, eye);
        if (beamQuads > 0) {
            particleMesh.stream(particleScratch, beamQuads * 4 * ParticleSystem.VERTEX_FLOATS);
            particleMesh.drawIndexed(beamQuads * 6);
        }

        GLES30.glDepthMask(true);
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    private void drawInterface(float dt) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);

        sprites.begin(viewWidth, viewHeight);
        hud.draw(sprites, world, settings.showFps(), false);
        if (!paused && world.state != GameWorld.STATE_OVER) {
            controls.showZones = settings.showZones();
            controls.draw(sprites);
        }
        sprites.flush();

        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
    }
}
