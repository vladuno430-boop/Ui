package com.arena3.game;

import com.arena3.core.Vec3;

/** A rocket, grenade, plasma ball or void orb in flight. */
public final class Projectile {

    public boolean active;
    public int weapon;
    public int owner;
    public final Vec3 origin = new Vec3();
    public final Vec3 velocity = new Vec3();
    /** Seconds before it detonates on its own. */
    public float fuse;
    /** Grenades bounce; everything else detonates on contact. */
    public boolean bounces;
    /** Falls under gravity (grenades only). */
    public boolean affectedByGravity;
    public int damage;
    public int splashDamage;
    public float splashRadius;
    public float knockback;
    /** Damage multiplier captured when fired, so a quad shot stays quad. */
    public float damageScale = 1f;
    /** Seconds since launch — used for trails and to avoid self-collision. */
    public float age;
    public float radius = 4f;
    /** Rolling id so the renderer can keep per-projectile trail state. */
    public int id;
}
