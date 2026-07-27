package com.arena3.game;

import com.arena3.core.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Everything that makes up an arena: geometry, entities and lighting. */
public final class MapDef {

    public String id = "";
    public String name = "";
    public String subtitle = "";
    public String description = "";

    public final List<Brush> brushes = new ArrayList<>();
    public final List<Spawn> spawns = new ArrayList<>();
    public final List<ItemSpawn> items = new ArrayList<>();
    public final List<JumpPad> jumpPads = new ArrayList<>();
    public final List<Teleporter> teleporters = new ArrayList<>();
    public final List<HurtVolume> hurtVolumes = new ArrayList<>();
    public final List<Light> lights = new ArrayList<>();

    /** Ambient light colour applied to every surface. */
    public final Vec3 ambient = new Vec3(0.16f, 0.17f, 0.21f);
    /** Direction the key light comes from (normalised, pointing at the surface). */
    public final Vec3 sunDir = new Vec3(-0.35f, -0.30f, -0.89f);
    public final Vec3 sunColor = new Vec3(0.30f, 0.30f, 0.34f);
    /** Distance fog. */
    public final Vec3 fogColor = new Vec3(0.05f, 0.06f, 0.08f);
    public float fogNear = 1400f;
    public float fogFar = 4200f;
    /** 0 = night sky, 1 = red storm, 2 = void/space. */
    public int skyStyle = 0;
    /** Anything that falls below this dies. */
    public float killZ = -4000f;

    public static final class Spawn {
        public final Vec3 pos = new Vec3();
        public float yaw;

        public Spawn(float x, float y, float z, float yaw) {
            pos.set(x, y, z);
            this.yaw = yaw;
        }
    }

    public static final class ItemSpawn {
        public final int itemId;
        public final Vec3 pos = new Vec3();

        public ItemSpawn(int itemId, float x, float y, float z) {
            this.itemId = itemId;
            pos.set(x, y, z);
        }
    }

    /** Trigger box that launches whoever touches it along a fixed velocity. */
    public static final class JumpPad {
        public final Vec3 mins = new Vec3();
        public final Vec3 maxs = new Vec3();
        public final Vec3 velocity = new Vec3();
        /** Where the arc is meant to land — used by the nav graph. */
        public final Vec3 target = new Vec3();
    }

    public static final class Teleporter {
        public final Vec3 mins = new Vec3();
        public final Vec3 maxs = new Vec3();
        public final Vec3 dest = new Vec3();
        public float destYaw;
    }

    /** Damage-over-time or instant-kill volume (lava, the void under a map). */
    public static final class HurtVolume {
        public final Vec3 mins = new Vec3();
        public final Vec3 maxs = new Vec3();
        /** Damage per application; {@link #instantKill} overrides it. */
        public int damage = 30;
        public boolean instantKill;
        /** Seconds between applications. */
        public float interval = 0.5f;
    }

    /** Static light baked into the level's vertex lighting. */
    public static final class Light {
        public final Vec3 pos = new Vec3();
        public final Vec3 color = new Vec3(1, 0.86f, 0.6f);
        public float radius = 400f;
        public float intensity = 1f;

        public Light(float x, float y, float z, float radius, float intensity,
                     float r, float g, float b) {
            pos.set(x, y, z);
            this.radius = radius;
            this.intensity = intensity;
            color.set(r, g, b);
        }
    }

    public Spawn randomSpawn(java.util.Random rnd) {
        return spawns.get(rnd.nextInt(spawns.size()));
    }
}
