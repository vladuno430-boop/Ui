import com.arena3.core.Vec3;
import com.arena3.game.Bot;
import com.arena3.game.CollisionWorld;
import com.arena3.game.Contents;
import com.arena3.game.GameConfig;
import com.arena3.game.GameEvent;
import com.arena3.game.GameWorld;
import com.arena3.game.ItemDef;
import com.arena3.game.ItemEntity;
import com.arena3.game.MapBuilder;
import com.arena3.game.MapDef;
import com.arena3.game.Maps;
import com.arena3.game.MoveInput;
import com.arena3.game.PlayerMove;
import com.arena3.game.PlayerState;
import com.arena3.game.Tex;
import com.arena3.game.Trace;
import com.arena3.game.WeaponDef;
import com.arena3.render.FighterPose;
import com.arena3.render.MeshBuilder;
import com.arena3.render.Models;
import com.arena3.render.ParticleSystem;
import com.arena3.render.ProcTex;
import com.arena3.render.ShadowFit;
import com.arena3.render.WorldGeometry;
import com.arena3.core.Mat4;

import java.util.Locale;

/**
 * Runs the simulation without Android so movement, collision, navigation, bots
 * and the match rules can be exercised on a desktop JVM.
 *
 *   javac -d out $(find app/src/main/java/com/arena3/core app/src/main/java/com/arena3/game -name '*.java') tools/HeadlessTest.java
 *   java -cp out HeadlessTest
 */
public final class HeadlessTest {

    private static int failures;

    public static void main(String[] args) {
        System.out.println("=== Arena 3 simulation tests ===\n");

        testMovementPhysics();
        testStrafeJumping();
        testStairsAndSlopes();
        for (int i = 0; i < Maps.COUNT; i++) testMap(i);
        testRenderData();
        testMatch();

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ checks

    private static void check(String what, boolean ok) {
        System.out.printf("  [%s] %s%n", ok ? "ok  " : "FAIL", what);
        if (!ok) failures++;
    }

    private static void checkRange(String what, float value, float lo, float hi) {
        boolean ok = value >= lo && value <= hi;
        System.out.printf("  [%s] %s = %.1f (expected %.1f..%.1f)%n",
                ok ? "ok  " : "FAIL", what, value, lo, hi);
        if (!ok) failures++;
    }

    // ------------------------------------------------------------ physics tests

    /** An empty plain, so movement can be measured without walls in the way. */
    private static GameWorld flatWorld() {
        MapBuilder b = new MapBuilder("test", "Test Plain", "", "");
        b.solid(-6000f, -6000f, -64f, 6000f, 6000f, 0f, Tex.FLOOR_METAL);
        b.spawn(0f, 0f, 32f, 0f);
        b.killZ(-2000f);
        GameConfig cfg = new GameConfig();
        cfg.botCount = 0;
        cfg.seed = 1234;
        GameWorld w = new GameWorld(b.build(), cfg);
        w.state = GameWorld.STATE_LIVE;
        return w;
    }

    /** A real arena, for the geometry-dependent tests. */
    private static GameWorld arenaWorld(int index) {
        GameConfig cfg = new GameConfig();
        cfg.mapIndex = index;
        cfg.botCount = 0;
        cfg.seed = 1234;
        GameWorld w = new GameWorld(Maps.build(index), cfg);
        w.state = GameWorld.STATE_LIVE;
        return w;
    }

    /** Drops the player at a known spot, facing a known way, at rest. */
    private static void place(GameWorld w, float x, float y, float z, float yaw) {
        PlayerState ps = w.players[0];
        ps.origin.set(x, y, z);
        ps.velocity.zero();
        ps.yaw = yaw;
        ps.pitch = 0f;
        ps.onGround = false;
        MoveInput cmd = w.inputs[0];
        for (int i = 0; i < 30; i++) {
            cmd.clear();
            w.update(1f / 60f);
        }
    }

    private static void testMovementPhysics() {
        System.out.println("Movement");
        GameWorld w = flatWorld();
        PlayerState ps = w.players[0];
        place(w, 0f, 0f, 40f, 0f);
        MoveInput cmd = w.inputs[0];
        check("player settles on the floor", ps.onGround);
        checkRange("resting height", ps.origin.z, 20f, 30f);

        // Run forwards for a second: speed should cap at exactly the run speed.
        for (int i = 0; i < 60; i++) {
            cmd.clear();
            cmd.forward = 1f;
            w.update(1f / 60f);
        }
        checkRange("ground run speed", ps.velocity.length2d(), 315f, 325f);

        // Release: friction should bring it to a stop quickly.
        for (int i = 0; i < 60; i++) {
            cmd.clear();
            w.update(1f / 60f);
        }
        checkRange("stops when input released", ps.velocity.length2d(), 0f, 2f);

        // Jump height: apex of a standing jump is v^2 / 2g = 270^2/1600 = 45.6.
        float startZ = ps.origin.z;
        float peak = startZ;
        cmd.clear();
        cmd.jump = true;
        w.update(1f / 60f);
        for (int i = 0; i < 60; i++) {
            cmd.clear();
            w.update(1f / 60f);
            peak = Math.max(peak, ps.origin.z);
            if (ps.onGround && i > 5) break;
        }
        checkRange("standing jump height", peak - startZ, 40f, 50f);
        System.out.println();
    }

    /**
     * Reproduces what a player does to strafe jump: hop continuously, hold
     * forward plus one strafe key, and keep turning so the desired direction
     * stays just outside the angle at which air acceleration stops paying out.
     * If the air-acceleration rules are right, speed climbs well past the
     * 320 u/s ground cap.
     */
    private static void testStrafeJumping() {
        System.out.println("Strafe jumping");
        GameWorld w = flatWorld();
        PlayerState ps = w.players[0];
        place(w, 0f, 0f, 40f, 0f);
        MoveInput cmd = w.inputs[0];

        // Get up to running speed first — the technique needs speed to work with.
        for (int i = 0; i < 90; i++) {
            cmd.clear();
            cmd.forward = 1f;
            w.update(1f / 60f);
        }

        // Air acceleration only pays out while the current speed along the wish
        // direction is under the run cap, so the view has to keep opening up as
        // the player gets faster.
        final float accelPerFrame = 1f * (1f / 60f) * PlayerMove.RUN_SPEED;

        float best = 0f;
        for (int i = 0; i < 60 * 12; i++) {
            cmd.clear();
            float speed = ps.velocity.length2d();
            float velYaw = (float) Math.toDegrees(Math.atan2(ps.velocity.y, ps.velocity.x));

            double cos = Math.min(1.0, (PlayerMove.RUN_SPEED - accelPerFrame) / Math.max(1f, speed));
            float theta = (float) Math.toDegrees(Math.acos(cos)) + 1.5f;

            cmd.forward = 1f;
            cmd.right = 1f;                              // wish direction is 45 deg right of view
            float wantView = velYaw + theta + 45f;
            cmd.deltaYaw = wrap(wantView - ps.yaw);
            cmd.jump = (i % 2) == 0;                     // tap, so each landing hops again

            w.update(1f / 60f);
            best = Math.max(best, ps.velocity.length2d());
        }
        check("strafe jumping exceeds the run cap", best > 420f);
        System.out.printf("       reached %.0f u/s (ground cap is %.0f)%n", best, PlayerMove.RUN_SPEED);
        System.out.println();
    }

    private static float wrap(float deg) {
        deg = deg % 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }

    private static void testStairsAndSlopes() {
        System.out.println("Stairs and ramps");
        GameWorld w = arenaWorld(0);
        PlayerState ps = w.players[0];
        MoveInput cmd = w.inputs[0];

        // Walk up the dais ramp on the west side of The Forge.
        place(w, -520f, 0f, 40f, 0f);
        float startZ = ps.origin.z;
        for (int i = 0; i < 70; i++) {
            cmd.clear();
            cmd.forward = 1f;
            w.update(1f / 60f);
        }
        check("ramp climbs to the dais", ps.origin.z > startZ + 100f);
        System.out.printf("       climbed %.0f units%n", ps.origin.z - startZ);

        // Walk up the west staircase.
        place(w, -330f, -420f, 40f, 180f);
        startZ = ps.origin.z;
        for (int i = 0; i < 300; i++) {
            cmd.clear();
            cmd.forward = 1f;
            w.update(1f / 60f);
        }
        check("staircase reaches the balcony", ps.origin.z > startZ + 200f);
        System.out.printf("       climbed %.0f units%n", ps.origin.z - startZ);
        System.out.println();
    }

    // ---------------------------------------------------------------- map tests

    private static void testMap(int index) {
        System.out.println("Map " + index + ": " + Maps.nameOf(index));
        long t0 = System.nanoTime();
        MapDef map = Maps.build(index);
        GameConfig cfg = new GameConfig();
        cfg.mapIndex = index;
        cfg.botCount = 3;
        cfg.seed = 99;
        GameWorld w = new GameWorld(map, cfg);
        long buildMs = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf("       %d brushes, %d items, %d spawns, %d nav nodes, %d links, built in %d ms%n",
                map.brushes.size(), map.items.size(), map.spawns.size(),
                w.nav.nodeCount, w.nav.linkTarget.length, buildMs);

        check("has spawn points", map.spawns.size() >= 4);
        check("has items", map.items.size() >= 10);
        check("nav graph is populated", w.nav.nodeCount > 40);
        check("nav graph is connected enough", w.nav.linkTarget.length > w.nav.nodeCount);
        check("build time is reasonable", buildMs < 4000);

        // Every spawn point must be free of solid geometry and standing on floor.
        Vec3 mins = new Vec3(-15, -15, -24), maxs = new Vec3(15, 15, 32);
        Trace tr = new Trace();
        int bad = 0, floating = 0;
        for (MapDef.Spawn s : map.spawns) {
            w.collision.traceBox(tr, s.pos, s.pos, mins, maxs, Contents.MASK_PLAYER);
            if (tr.startSolid || tr.allSolid) bad++;
            Vec3 down = new Vec3(s.pos.x, s.pos.y, s.pos.z - 200f);
            w.collision.traceBox(tr, s.pos, down, mins, maxs, Contents.MASK_PLAYER);
            if (tr.fraction >= 1f) floating++;
        }
        check("no spawn point is inside geometry", bad == 0);
        check("every spawn has floor beneath it", floating == 0);

        // Items must be reachable: each needs a nav node nearby.
        int unreachable = 0;
        for (ItemEntity it : w.items) {
            int n = w.nav.nearest(it.origin);
            if (n < 0) {
                unreachable++;
                continue;
            }
            Vec3 node = new Vec3();
            w.nav.nodePos(n, node);
            if (node.distanceTo(it.origin) > 190f) unreachable++;
        }
        check("items sit near reachable ground (" + unreachable + " far)", unreachable <= map.items.size() / 8);

        // Pathfinding across the map should succeed.
        int a = w.nav.nearest(map.spawns.get(0).pos);
        int reachedCount = 0;
        int[] path = new int[512];
        for (MapDef.Spawn s : map.spawns) {
            int b = w.nav.nearest(s.pos);
            if (w.nav.findPath(a, b, path) > 0) reachedCount++;
        }
        check("spawns are mutually reachable (" + reachedCount + "/" + map.spawns.size() + ")",
                reachedCount >= map.spawns.size() - 1);

        checkJumpPads(index);
        System.out.println();
    }

    /**
     * The sun's shadow projection has to enclose the whole arena: anything that
     * falls outside the light's box gets no shadow test at all, which shows up as
     * a hard-edged patch of unshadowed floor.
     */
    private static void checkShadowFit(WorldGeometry geo, MapDef map) {
        ShadowFit fit = new ShadowFit();
        fit.fit(geo.boundsMin, geo.boundsMax, map.sunDir);
        float[] lvp = fit.lightViewProj.m;

        int outside = 0;
        float minDepth = Float.MAX_VALUE, maxDepth = -Float.MAX_VALUE;
        for (int i = 0; i < geo.vertexCount; i++) {
            int o = i * WorldGeometry.VERTEX_FLOATS;
            float x = geo.verts[o], y = geo.verts[o + 1], z = geo.verts[o + 2];
            float w = lvp[3] * x + lvp[7] * y + lvp[11] * z + lvp[15];
            float cx = (lvp[0] * x + lvp[4] * y + lvp[8] * z + lvp[12]) / w;
            float cy = (lvp[1] * x + lvp[5] * y + lvp[9] * z + lvp[13]) / w;
            float cz = (lvp[2] * x + lvp[6] * y + lvp[10] * z + lvp[14]) / w;
            if (w <= 0f || cx < -1f || cx > 1f || cy < -1f || cy > 1f || cz < -1f || cz > 1f) {
                outside++;
            }
            minDepth = Math.min(minDepth, cz);
            maxDepth = Math.max(maxDepth, cz);
        }
        check("  shadow box encloses the map", outside == 0);
        // A box far larger than the geometry wastes texels and softens everything.
        check("  shadow depth range is tight", maxDepth - minDepth > 0.5f);

        // The light basis must stay orthonormal or the projection shears.
        float dotFR = fit.forward.dot(fit.right);
        float dotFU = fit.forward.dot(fit.up);
        float dotRU = fit.right.dot(fit.up);
        check("  light basis is orthonormal",
                Math.abs(dotFR) < 1e-3f && Math.abs(dotFU) < 1e-3f && Math.abs(dotRU) < 1e-3f);
    }

    /**
     * The real test of a shadow map: does it agree with the truth? A ray from the
     * surface towards the sun says whether a point is occluded, so every sampled
     * vertex gets both answers and they had better match.
     *
     * <p>Points near a shadow edge are skipped — PCF is deliberately soft there,
     * and a ray is not — as are points on faces angled away from the sun, which
     * get no sunlight either way. A ray that ends on a sky face has escaped the
     * level: the sun comes from there, so that counts as unoccluded.
     */
    private static void checkShadowAgreement(WorldGeometry geo, MapDef map, CollisionWorld cw) {
        SoftShadowMap sm = new SoftShadowMap();
        sm.build(geo, map.sunDir);

        Vec3 start = new Vec3(), end = new Vec3();
        Trace tr = new Trace();
        int compared = 0, disagree = 0, falseShadows = 0;
        // Every eighth vertex keeps the test quick and still samples thousands.
        for (int i = 0; i < geo.vertexCount; i += 8) {
            int o = i * WorldGeometry.VERTEX_FLOATS;
            float nx = geo.verts[o + 3], ny = geo.verts[o + 4], nz = geo.verts[o + 5];
            float ndl = -(nx * map.sunDir.x + ny * map.sunDir.y + nz * map.sunDir.z);
            if (ndl < 0.25f) continue;          // grazing or back-facing: no sun anyway

            float shadow = sm.factor(geo.verts[o], geo.verts[o + 1], geo.verts[o + 2], ndl);
            if (shadow > 0.05f && shadow < 0.95f) continue;   // inside the soft edge

            // Lift off the surface so the ray does not start inside its own face.
            start.set(geo.verts[o] + nx * 2f, geo.verts[o + 1] + ny * 2f, geo.verts[o + 2] + nz * 2f);
            end.set(start.x - map.sunDir.x * 8000f, start.y - map.sunDir.y * 8000f,
                    start.z - map.sunDir.z * 8000f);
            cw.traceRay(tr, start, end, Contents.SOLID);
            boolean rayBlocked = tr.fraction < 1f && (tr.surfaceFlags & Contents.SURF_SKY) == 0;
            boolean mapBlocked = shadow < 0.5f;

            compared++;
            if (rayBlocked != mapBlocked) {
                disagree++;
                // Front-face culling means a caster whose far side was dropped as
                // a buried face writes nothing, so the only tolerated error is a
                // missing shadow. A false one is acne, and that is always visible.
                if (!rayBlocked) falseShadows++;
            }
        }

        float rate = compared == 0 ? 0f : disagree / (float) compared;
        System.out.printf(Locale.ROOT,
                "  shadow map vs ray trace: %d samples, %.2f%% disagree (%d false)%n",
                compared, rate * 100f, falseShadows);
        check("  enough sunlit surface to test", compared > 200);
        // What is left over are contact shadows a few texels wide at wall bases,
        // where the caster's own far face was dropped as buried geometry.
        check("  shadow map matches ray-traced visibility", rate < 0.05f);
        check("  no false shadows (acne)", falseShadows == 0);
    }

    /**
     * Rides every jump pad. An arc that lands short, clips the edge of its
     * destination or drops the player into the void makes a map unplayable, and
     * it is invisible from reading the numbers.
     */
    private static void checkJumpPads(int mapIndex) {
        // No bots: this measures the arcs, not what someone shoots at you mid-flight.
        GameWorld w = arenaWorld(mapIndex);
        MapDef map = w.map;
        PlayerState ps = w.players[0];
        int bad = 0, wild = 0;
        for (MapDef.JumpPad pad : map.jumpPads) {
            float px = (pad.mins.x + pad.maxs.x) * 0.5f;
            float py = (pad.mins.y + pad.maxs.y) * 0.5f;
            ps.origin.set(px, py, pad.mins.z + 26f);
            ps.velocity.zero();
            ps.alive = true;
            ps.health = 100;
            ps.padFlight = false;
            boolean landed = false;
            for (int i = 0; i < 60 * 8; i++) {
                w.inputs[0].clear();
                w.update(1f / 60f);
                if (i > 12 && ps.onGround) {
                    landed = true;
                    break;
                }
                if (!ps.alive) break;
            }
            if (!landed) {
                bad++;
            } else if (Math.hypot(ps.origin.x - pad.target.x, ps.origin.y - pad.target.y) > 340) {
                // Touching down near the leading edge of a big platform is fine;
                // this only catches arcs that miss their destination entirely.
                wild++;
            }
        }
        check("every jump pad lands its rider (" + bad + " fatal, " + wild + " off-target)",
                bad == 0 && wild == 0);
    }

    // ------------------------------------------------------------- render data

    /**
     * The renderer's inputs are all generated, so a bad number here shows up as
     * an invisible or corrupted world on the device with nothing to inspect.
     */
    private static void testRenderData() {
        System.out.println("Render data");

        // Textures: every slot must be generated and have some variation in it.
        int flat = 0;
        for (int i = 0; i < Tex.COUNT; i++) {
            int[] px = ProcTex.generate(i);
            if (px.length != ProcTex.SIZE * ProcTex.SIZE) {
                check("texture " + i + " is the right size", false);
                return;
            }
            int min = 0xFFFFFF, max = 0;
            for (int p : px) {
                int lum = ((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF);
                min = Math.min(min, lum);
                max = Math.max(max, lum);
            }
            // The sky slot is deliberately a flat colour; nothing else should be.
            if (max - min < 12 && i != Tex.SKY) flat++;
        }
        check("all textures generate with detail (" + flat + " flat)", flat == 0);

        // World geometry for each arena.
        for (int m = 0; m < Maps.COUNT; m++) {
            MapDef map = Maps.build(m);
            CollisionWorld cw = new CollisionWorld();
            cw.build(map.brushes);
            long t0 = System.nanoTime();
            WorldGeometry geo = new WorldGeometry();
            geo.build(map, cw);
            long ms = (System.nanoTime() - t0) / 1_000_000;

            boolean finite = true;
            float maxLight = 0f;
            for (int i = 0; i < geo.vertexCount * WorldGeometry.VERTEX_FLOATS; i++) {
                float v = geo.verts[i];
                if (Float.isNaN(v) || Float.isInfinite(v)) finite = false;
            }
            // The sun is applied per pixel so the shadow map can cut it, which
            // leaves occlusion and the texture layer riding along in the vertex.
            float minAo = 1f, maxAo = 0f;
            boolean layersValid = true;
            for (int i = 0; i < geo.vertexCount; i++) {
                int o = i * WorldGeometry.VERTEX_FLOATS;
                maxLight = Math.max(maxLight, geo.verts[o + 8]);
                float ao = geo.verts[o + 11];
                minAo = Math.min(minAo, ao);
                maxAo = Math.max(maxAo, ao);
                int layer = (int) geo.verts[o + 12];
                if (layer < 0 || layer >= Tex.COUNT) layersValid = false;
            }
            boolean indicesValid = true;
            for (int i = 0; i < geo.indexCount; i++) {
                if (geo.indices[i] < 0 || geo.indices[i] >= geo.vertexCount) indicesValid = false;
            }
            System.out.printf("  %-10s %6d verts %6d tris  baked %3d ms%n",
                    Maps.nameOf(m), geo.vertexCount, geo.triangleCount(), ms);
            check("  geometry is finite", finite);
            check("  indices are in range", indicesValid);
            check("  geometry is non-trivial", geo.triangleCount() > 500);
            check("  lighting stays in range", maxLight > 0.05f && maxLight <= 2.3f);
            check("  occlusion stays in 0..1", minAo >= 0f && maxAo <= 1f && maxAo > 0.5f);
            check("  texture layers are valid", layersValid);
            check("  bake is fast enough", ms < 3000);

            checkShadowFit(geo, map);
            checkShadowAgreement(geo, map, cw);
        }

        // Models: every mesh must build with usable geometry.
        int emptyMeshes = 0;
        MeshBuilder.MeshData[] parts = Models.fighter(0.9f, 0.6f, 0.2f);
        for (MeshBuilder.MeshData d : parts) if (d.indexCount < 6) emptyMeshes++;
        for (int w = 0; w < WeaponDef.COUNT; w++) {
            if (Models.weapon(w).indexCount < 6) emptyMeshes++;
            if (Models.projectile(w).indexCount < 6) emptyMeshes++;
        }
        for (int i = 0; i < ItemDef.ALL.length; i++) {
            if (Models.item(i).indexCount < 6) emptyMeshes++;
        }
        check("every model builds (" + emptyMeshes + " empty)", emptyMeshes == 0);

        // Posing must produce finite matrices for the living and the dead alike.
        PlayerState ps = new PlayerState();
        ps.resetForSpawn();
        ps.origin.set(100, 200, 30);
        ps.velocity.set(250, 0, 0);
        ps.onGround = true;
        ps.bobCycle = 1.7f;
        Mat4[] pose = FighterPose.allocate();
        boolean poseOk = true;
        for (int pass = 0; pass < 2; pass++) {
            ps.alive = pass == 0;
            ps.deathTime = pass == 0 ? 0f : 0.8f;
            FighterPose.compute(ps, pose);
            for (Mat4 mat : pose) {
                for (float v : mat.m) {
                    if (Float.isNaN(v) || Float.isInfinite(v)) poseOk = false;
                }
            }
        }
        check("fighter posing is finite", poseOk);

        // Particles: fill the pool hard, then make sure it drains and builds.
        ParticleSystem fx = new ParticleSystem();
        Vec3 at = new Vec3(0, 0, 0);
        Vec3 dir = new Vec3(0, 0, 1);
        for (int i = 0; i < 300; i++) {
            fx.explosion(at, 120f, 1.6f, 0.9f, 0.4f);
            fx.bulletImpact(at, dir);
            fx.blood(at, dir, 40);
            fx.railTrail(at, new Vec3(900, 0, 0), 1f, 0.6f, 1.6f);
        }
        check("particle pool holds its cap", fx.liveParticles() <= ParticleSystem.MAX_PARTICLES);
        check("beam pool holds its cap", fx.beams() <= ParticleSystem.MAX_BEAMS);

        float[] scratch = new float[1400 * 4 * ParticleSystem.VERTEX_FLOATS];
        Vec3 camRight = new Vec3(0, 1, 0), camUp = new Vec3(0, 0, 1);
        int quads = fx.buildQuads(scratch, 1400, camRight, camUp, true);
        boolean quadsFinite = true;
        for (int i = 0; i < quads * 4 * ParticleSystem.VERTEX_FLOATS; i++) {
            if (Float.isNaN(scratch[i]) || Float.isInfinite(scratch[i])) quadsFinite = false;
        }
        check("particle geometry is finite (" + quads + " quads)", quadsFinite && quads > 0);

        for (int i = 0; i < 400; i++) fx.update(1f / 60f, null);
        check("particles expire", fx.liveParticles() == 0);
        System.out.println();
    }

    // -------------------------------------------------------------- match tests

    private static void testMatch() {
        System.out.println("Full match simulation");
        for (int mapIndex = 0; mapIndex < Maps.COUNT; mapIndex++) {
            GameConfig cfg = new GameConfig();
            cfg.mapIndex = mapIndex;
            cfg.botCount = 5;
            cfg.skill = 3;
            cfg.fragLimit = 0;
            cfg.timeLimitSeconds = 0;
            cfg.seed = 4242 + mapIndex;
            GameWorld w = new GameWorld(Maps.build(mapIndex), cfg);

            int steps = 60 * 90;                 // 90 seconds at 60 Hz
            long t0 = System.nanoTime();
            int fires = 0, deaths = 0, pickups = 0, explosions = 0, telefrags = 0;
            float minZ = Float.MAX_VALUE;
            boolean nan = false;

            for (int i = 0; i < steps; i++) {
                // The "human" wanders so slot 0 is not a sitting duck.
                MoveInput human = w.inputs[0];
                human.clear();
                human.forward = 1f;
                human.deltaYaw = (float) Math.sin(i * 0.01f) * 2.5f;
                human.jump = (i % 90) < 4;
                human.fire = (i % 37) < 6;

                w.update(1f / 60f);

                for (GameEvent ev : w.events) {
                    switch (ev.type) {
                        case GameEvent.FIRE: fires++; break;
                        case GameEvent.DEATH: deaths++; break;
                        case GameEvent.PICKUP: pickups++; break;
                        case GameEvent.EXPLOSION: explosions++; break;
                        case GameEvent.TELEPORT: telefrags++; break;
                        default: break;
                    }
                }
                for (PlayerState ps : w.players) {
                    if (!ps.origin.isFinite() || !ps.velocity.isFinite()) nan = true;
                    if (ps.alive) minZ = Math.min(minZ, ps.origin.z);
                }
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;

            int totalFrags = 0, botsThatScored = 0, stuckBots = 0;
            StringBuilder score = new StringBuilder();
            for (PlayerState ps : w.players) {
                totalFrags += Math.max(0, ps.frags);
                if (ps.isBot && ps.frags > 0) botsThatScored++;
                score.append(String.format(Locale.US, "%s %d/%d  ", ps.name, ps.frags, ps.deaths));
            }
            // A bot that never moved is a navigation failure.
            for (int i = 1; i < w.players.length; i++) {
                if (w.players[i].deaths == 0 && w.players[i].frags == 0) stuckBots++;
            }

            System.out.println("  " + Maps.nameOf(mapIndex));
            System.out.printf("       90 s simulated in %d ms (%.0fx real time)%n", ms, 90000f / Math.max(1, ms));
            System.out.printf("       %d shots, %d deaths, %d pickups, %d explosions%n",
                    fires, deaths, pickups, explosions);
            System.out.println("       " + score.toString().trim());

            check("no NaN in player state", !nan);
            check("nobody fell through the world", minZ > w.map.killZ);
            check("bots fought (" + deaths + " deaths)", deaths >= 3);
            check("bots collected items (" + pickups + ")", pickups >= 20);
            check("most bots are active", stuckBots <= 1);
            check("runs faster than real time", ms < 90000);
            System.out.println();
        }
    }
}
