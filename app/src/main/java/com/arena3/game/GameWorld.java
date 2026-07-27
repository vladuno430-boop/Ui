package com.arena3.game;

import com.arena3.core.MathUtil;
import com.arena3.core.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The whole deathmatch: players, bots, projectiles, pickups, damage and match
 * rules. Deliberately free of any Android dependency so the simulation can be
 * run head-less on a desktop JVM for testing.
 */
public final class GameWorld implements Trace.Provider {

    public static final int STATE_COUNTDOWN = 0;
    public static final int STATE_LIVE = 1;
    public static final int STATE_OVER = 2;

    private static final int MAX_PROJECTILES = 192;
    private static final float RESPAWN_DELAY = 1.7f;
    private static final float COUNTDOWN = 3.0f;
    private static final float ITEM_PICKUP_RADIUS = 44f;
    private static final float KNOCKBACK_SCALE = 5f;
    private static final int MAX_AMMO = 200;

    public final GameConfig config;
    public final MapDef map;
    public final CollisionWorld collision = new CollisionWorld();
    public final NavGraph nav = new NavGraph();

    public PlayerState[] players;
    public Bot[] bots;
    public MoveInput[] inputs;
    public final List<ItemEntity> items = new ArrayList<>();
    public final Projectile[] projectiles = new Projectile[MAX_PROJECTILES];

    public int state = STATE_COUNTDOWN;
    public float matchTime;
    public float countdown = COUNTDOWN;
    public int winner = -1;

    /** Events produced this frame, consumed by the renderer and sound engine. */
    public final List<GameEvent> events = new ArrayList<>();
    private final List<GameEvent> eventPool = new ArrayList<>();
    private int eventPoolUsed;

    private final Random rnd;
    private final PlayerMove pmove = new PlayerMove();
    private int projectileIdCounter;

    // per-player bookkeeping the PlayerState does not need to expose
    private float[] nextHurtTime;
    private float[] respawnCooldown;
    private boolean[] wantRespawn;

    // scratch
    private final Trace trace = new Trace();
    private final Trace scratchTrace = new Trace();
    private final Vec3 forward = new Vec3();
    private final Vec3 right = new Vec3();
    private final Vec3 up = new Vec3();
    private final Vec3 muzzle = new Vec3();
    private final Vec3 endPoint = new Vec3();
    private final Vec3 tmp = new Vec3();
    private final Vec3 tmp2 = new Vec3();
    private final Vec3 boxMins = new Vec3();
    private final Vec3 boxMaxs = new Vec3();
    private final Vec3 entMins = new Vec3();
    private final Vec3 entMaxs = new Vec3();

    public GameWorld(MapDef map, GameConfig config) {
        this.map = map;
        this.config = config;
        this.rnd = new Random(config.seed);

        collision.build(map.brushes);
        nav.build(map, collision);

        for (int i = 0; i < MAX_PROJECTILES; i++) projectiles[i] = new Projectile();
        for (MapDef.ItemSpawn s : map.items) {
            ItemEntity e = new ItemEntity();
            e.itemId = s.itemId;
            e.origin.set(s.pos);
            e.bobPhase = rnd.nextFloat() * 6.28f;
            items.add(e);
        }
        createPlayers();
    }

    private void createPlayers() {
        int count = 1 + Math.max(0, config.botCount);
        players = new PlayerState[count];
        bots = new Bot[count];
        inputs = new MoveInput[count];
        nextHurtTime = new float[count];
        respawnCooldown = new float[count];
        wantRespawn = new boolean[count];

        for (int i = 0; i < count; i++) {
            PlayerState ps = new PlayerState();
            ps.index = i;
            ps.colorIndex = i;
            ps.isBot = i > 0;
            ps.name = i == 0 ? config.playerName : BOT_NAMES[(i - 1) % BOT_NAMES.length];
            ps.skill = config.skill;
            players[i] = ps;
            inputs[i] = new MoveInput();
            if (ps.isBot) bots[i] = new Bot(this, ps, new Random(config.seed * 31 + i));
            spawnPlayer(ps, true);
        }
    }

    public static final String[] BOT_NAMES = {
            "SARGE", "VISOR", "ORBB", "RAZOR", "KEEL", "HUNTER", "SLASH", "BONES",
    };

    // ------------------------------------------------------------------ update

    public void update(float dt) {
        dt = Math.min(dt, 0.1f);
        recycleEvents();

        if (state == STATE_COUNTDOWN) {
            countdown -= dt;
            if (countdown <= 0f) {
                state = STATE_LIVE;
                event(GameEvent.ANNOUNCE).text = "FIGHT!";
            }
            // Players can look around during the countdown but not move or shoot.
            for (int i = 0; i < players.length; i++) {
                PlayerState ps = players[i];
                ps.yaw = MathUtil.normalizeAngle(ps.yaw + inputs[i].deltaYaw);
                ps.pitch = MathUtil.clamp(ps.pitch + inputs[i].deltaPitch, -89f, 89f);
            }
            return;
        }

        if (state == STATE_LIVE) {
            matchTime += dt;
        }

        for (int i = 0; i < players.length; i++) {
            if (bots[i] != null) bots[i].think(dt);
        }

        for (int i = 0; i < players.length; i++) {
            updatePlayer(players[i], inputs[i], dt);
        }

        updateProjectiles(dt);
        updateItems(dt);

        if (state == STATE_LIVE) checkMatchEnd();
    }

    private void updatePlayer(PlayerState ps, MoveInput cmd, float dt) {
        ps.painFlash = Math.max(0f, ps.painFlash - dt * 3f);
        ps.muzzleFlash = Math.max(0f, ps.muzzleFlash - dt);
        ps.invulnerable = Math.max(0f, ps.invulnerable - dt);
        ps.hitFeedback = Math.max(0f, ps.hitFeedback - dt * 2f);

        if (!ps.alive) {
            ps.deathTime += dt;
            respawnCooldown[ps.index] -= dt;
            // Dead players keep falling so the corpse settles.
            ps.velocity.z -= PlayerMove.GRAVITY * dt;
            tmp.setMa(ps.origin, ps.velocity, dt);
            boxMins.set(-15, -15, -24);
            boxMaxs.set(15, 15, 0);
            collision.traceBox(trace, ps.origin, tmp, boxMins, boxMaxs, Contents.MASK_PLAYER);
            ps.origin.set(trace.endPos);
            if (trace.fraction < 1f) {
                ps.velocity.clipVelocity(trace.normal, 1.2f);
                ps.velocity.scale(0.6f);
            }
            if (respawnCooldown[ps.index] <= 0f && (ps.isBot || wantRespawn[ps.index] || cmd.fire
                    || respawnCooldown[ps.index] < -3f)) {
                spawnPlayer(ps, false);
            }
            return;
        }

        updatePowerups(ps, dt);

        boolean frozen = state != STATE_LIVE;
        if (frozen) {
            cmd.forward = 0;
            cmd.right = 0;
            cmd.jump = false;
            cmd.fire = false;
        }

        pmove.move(ps, cmd, dt, this);

        if (ps.onGround) ps.padFlight = false;

        if (ps.landImpact > 0f) {
            int dmg = PlayerMove.fallDamage(ps.landImpact);
            GameEvent ev = event(GameEvent.LAND);
            ev.pos.set(ps.origin);
            ev.b = ps.index;
            ev.f = ps.landImpact;
            if (dmg > 0) {
                tmp.set(0, 0, 1);
                damage(ps, null, dmg, tmp, 0f, -1);
            }
            ps.landImpact = 0f;
        }

        touchTriggers(ps, dt);
        touchItems(ps);
        updateWeapon(ps, cmd, dt);

        if (ps.origin.z < map.killZ && ps.alive) {
            tmp.set(0, 0, 1);
            damage(ps, null, 9999, tmp, 0f, -1);
        }
        if (!ps.origin.isFinite()) {
            // Belt and braces: never let a broken value poison the sim.
            spawnPlayer(ps, true);
        }
    }

    private void updatePowerups(PlayerState ps, float dt) {
        for (int i = 0; i < ItemDef.PW_COUNT; i++) {
            if (ps.powerupTime[i] > 0f) ps.powerupTime[i] = Math.max(0f, ps.powerupTime[i] - dt);
        }
        if (ps.hasPowerup(ItemDef.PW_REGEN)) {
            regenAccumulator[ps.index] += dt;
            while (regenAccumulator[ps.index] >= 1f) {
                regenAccumulator[ps.index] -= 1f;
                if (ps.health < 200) ps.health = Math.min(200, ps.health + 15);
            }
        }
        // Health above the soft cap decays back down, as in the original.
        decayAccumulator[ps.index] += dt;
        while (decayAccumulator[ps.index] >= 1f) {
            decayAccumulator[ps.index] -= 1f;
            if (ps.health > ps.maxHealth) ps.health--;
            if (ps.armor > 100) ps.armor--;
        }
    }

    private final float[] regenAccumulator = new float[16];
    private final float[] decayAccumulator = new float[16];

    // ------------------------------------------------------------- spawn/death

    public void spawnPlayer(PlayerState ps, boolean initial) {
        MapDef.Spawn best = pickSpawn();
        ps.origin.set(best.pos);
        ps.yaw = best.yaw;
        ps.pitch = 0f;
        ps.legsYaw = best.yaw;
        ps.resetForSpawn();
        ps.deathTime = 0f;
        wantRespawn[ps.index] = false;
        regenAccumulator[ps.index] = 0f;
        decayAccumulator[ps.index] = 0f;
        if (bots[ps.index] != null) bots[ps.index].onSpawn();
        if (!initial) {
            GameEvent ev = event(GameEvent.RESPAWN);
            ev.pos.set(ps.origin);
            ev.b = ps.index;
        }
    }

    /** Picks the spawn point furthest from any living player. */
    private MapDef.Spawn pickSpawn() {
        MapDef.Spawn best = null;
        float bestScore = -1f;
        for (int attempt = 0; attempt < map.spawns.size(); attempt++) {
            MapDef.Spawn s = map.spawns.get((attempt + rnd.nextInt(map.spawns.size())) % map.spawns.size());
            float nearest = Float.MAX_VALUE;
            for (PlayerState other : players) {
                if (other == null || !other.alive) continue;
                nearest = Math.min(nearest, s.pos.distanceTo(other.origin));
            }
            if (nearest == Float.MAX_VALUE) nearest = 4000f;
            float score = nearest + rnd.nextFloat() * 200f;
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best != null ? best : map.spawns.get(0);
    }

    /** Called by the UI when the local player taps to respawn. */
    public void requestRespawn(int playerIndex) {
        wantRespawn[playerIndex] = true;
    }

    public float respawnRemaining(int playerIndex) {
        return Math.max(0f, respawnCooldown[playerIndex]);
    }

    private void die(PlayerState victim, PlayerState attacker, int meansOfDeath, int overkill) {
        if (!victim.alive) return;
        victim.alive = false;
        victim.deaths++;
        victim.streak = 0;
        victim.deathTime = 0f;
        victim.firing = false;
        respawnCooldown[victim.index] = RESPAWN_DELAY;
        victim.deathStyle = rnd.nextInt(3);

        boolean gib = overkill <= -25 || meansOfDeath == WeaponDef.VOIDCANNON;
        GameEvent ev = event(GameEvent.DEATH);
        ev.pos.set(victim.origin);
        ev.b = victim.index;
        ev.a = gib ? 1 : 0;

        String verb = meansOfDeath >= 0 ? WeaponDef.get(meansOfDeath).name : "the world";
        if (attacker != null && attacker != victim) {
            attacker.frags++;
            attacker.streak++;
            if (matchTime - attacker.lastKillTime < 3f) {
                attacker.multiKill++;
            } else {
                attacker.multiKill = 1;
            }
            attacker.lastKillTime = matchTime;
            announceKill(attacker, victim, verb);
        } else {
            // Suicide, lava, falling or the void.
            victim.frags = Math.max(0, victim.frags - 1);
            GameEvent frag = event(GameEvent.FRAG);
            frag.a = -1;
            frag.b = victim.index;
            frag.text = victim.name + (meansOfDeath >= 0 ? " blew himself up" : " left the arena");
        }
    }

    private void announceKill(PlayerState attacker, PlayerState victim, String verb) {
        GameEvent frag = event(GameEvent.FRAG);
        frag.a = attacker.index;
        frag.b = victim.index;
        frag.text = attacker.name + " ▸ " + victim.name + "  (" + verb + ")";

        String callout = null;
        if (attacker.multiKill == 2) callout = "DOUBLE KILL";
        else if (attacker.multiKill == 3) callout = "MULTI KILL";
        else if (attacker.multiKill >= 4) callout = "RAMPAGE";
        else if (attacker.streak == 5) callout = "KILLING SPREE";
        else if (attacker.streak == 10) callout = "UNSTOPPABLE";
        if (callout != null) {
            GameEvent ev = event(GameEvent.ANNOUNCE);
            ev.text = callout;
            ev.b = attacker.index;
        }
    }

    // ------------------------------------------------------------------ damage

    /**
     * Applies damage, armor absorption and knockback.
     *
     * @param dir       unit vector pointing from the attacker towards the victim
     * @param knockback impulse strength before the mass division
     * @param mod       weapon id responsible, or -1 for the world
     */
    public void damage(PlayerState victim, PlayerState attacker, int amount, Vec3 dir,
                       float knockback, int mod) {
        if (!victim.alive || amount <= 0) return;
        if (state != STATE_LIVE) return;

        boolean selfDamage = attacker == victim;
        if (victim.invulnerable > 0f && !selfDamage) return;

        if (attacker != null && !selfDamage) {
            amount = Math.round(amount * attacker.damageScale());
        }
        if (selfDamage) amount = Math.max(1, amount / 2);
        if (victim.hasPowerup(ItemDef.PW_BATTLESUIT)) {
            if (selfDamage) return;
            amount = Math.max(1, amount / 2);
            knockback *= 0.5f;
        }

        // Knockback lands even when the damage is fully absorbed — a rocket at
        // your feet should still throw you, armor or not.
        if (knockback > 0f && dir != null) {
            victim.velocity.addScaled(dir, KNOCKBACK_SCALE * knockback);
            victim.onGround = false;
        }

        int take = amount;
        int save = Math.min(victim.armor, take * 2 / 3);
        victim.armor -= save;
        take -= save;
        victim.health -= take;

        victim.lastDamageTime = matchTime;
        victim.painFlash = 1f;
        if (dir != null) victim.lastDamageDir.set(dir);
        if (attacker != null && !selfDamage) victim.lastAttacker = attacker.index;

        GameEvent pain = event(GameEvent.PAIN);
        pain.pos.set(victim.origin);
        pain.b = victim.index;
        pain.a = amount;

        if (attacker != null && !selfDamage) {
            attacker.hitFeedback = 1f;
            if (attacker.index == 0) {
                GameEvent hc = event(GameEvent.HIT_CONFIRM);
                hc.a = amount;
                hc.b = victim.index;
            }
        }

        if (victim.health <= 0) {
            die(victim, attacker, mod, victim.health);
        }
    }

    /**
     * Damage falling off with distance, blocked by geometry. {@code ignore} is
     * the victim of a direct hit, who already took the impact damage.
     */
    public void radiusDamage(Vec3 origin, PlayerState attacker, int damage, float radius,
                             float knockbackScale, int mod, PlayerState ignore) {
        for (PlayerState ps : players) {
            if (!ps.alive || ps == ignore) continue;
            // Measure to the nearest point of the player's box, as Quake does.
            ps.mins(entMins);
            ps.maxs(entMaxs);
            float dx = Math.max(0f, Math.max(ps.origin.x + entMins.x - origin.x, origin.x - (ps.origin.x + entMaxs.x)));
            float dy = Math.max(0f, Math.max(ps.origin.y + entMins.y - origin.y, origin.y - (ps.origin.y + entMaxs.y)));
            float dz = Math.max(0f, Math.max(ps.origin.z + entMins.z - origin.z, origin.z - (ps.origin.z + entMaxs.z)));
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist >= radius) continue;

            float points = damage * (1f - dist / radius);
            if (points < 1f) continue;

            tmp.set(ps.origin.x, ps.origin.y, ps.origin.z + 8f);
            if (!lineOfSight(origin, tmp)) continue;

            tmp2.setSub(tmp, origin);
            if (tmp2.normalize() < 1f) tmp2.set(0, 0, 1);
            damage(ps, attacker, Math.round(points), tmp2, points * knockbackScale, mod);
        }
    }

    public boolean lineOfSight(Vec3 from, Vec3 to) {
        collision.traceRay(scratchTrace, from, to, Contents.MASK_SHOT);
        return scratchTrace.fraction >= 1f && !scratchTrace.startSolid;
    }

    // ----------------------------------------------------------------- weapons

    private void updateWeapon(PlayerState ps, MoveInput cmd, float dt) {
        if (ps.weaponSwitchTime > 0f) {
            float before = ps.weaponSwitchTime;
            ps.weaponSwitchTime -= dt;
            if (before > 0.12f && ps.weaponSwitchTime <= 0.12f && ps.pendingWeapon >= 0) {
                ps.weapon = ps.pendingWeapon;
                ps.pendingWeapon = -1;
                GameEvent ev = event(GameEvent.WEAPON_SWITCH);
                ev.a = ps.weapon;
                ev.b = ps.index;
            }
        }
        ps.weaponCooldown = Math.max(0f, ps.weaponCooldown - dt);

        if (cmd.selectWeapon >= 0 && cmd.selectWeapon != ps.weapon && ps.hasWeapon(cmd.selectWeapon)) {
            switchWeapon(ps, cmd.selectWeapon);
        }

        ps.firing = cmd.fire;
        if (!cmd.fire) return;
        if (ps.weaponSwitchTime > 0f || ps.weaponCooldown > 0f) return;

        WeaponDef def = WeaponDef.get(ps.weapon);
        if (def.usesAmmo() && ps.ammo[ps.weapon] < def.ammoPerShot) {
            GameEvent ev = event(GameEvent.NO_AMMO);
            ev.b = ps.index;
            ps.weaponCooldown = 0.5f;
            int next = bestWeaponWithAmmo(ps);
            if (next >= 0 && next != ps.weapon) switchWeapon(ps, next);
            return;
        }
        fire(ps, def);
    }

    public void switchWeapon(PlayerState ps, int weapon) {
        if (!ps.hasWeapon(weapon) || weapon == ps.weapon || ps.pendingWeapon == weapon) return;
        ps.pendingWeapon = weapon;
        ps.weaponSwitchTime = 0.25f;
    }

    /** Highest-preference weapon the player can actually shoot right now. */
    public int bestWeaponWithAmmo(PlayerState ps) {
        int best = -1;
        float bestScore = -1f;
        for (int w = 0; w < WeaponDef.COUNT; w++) {
            if (!ps.hasWeapon(w)) continue;
            WeaponDef d = WeaponDef.get(w);
            if (d.usesAmmo() && ps.ammo[w] < d.ammoPerShot) continue;
            if (d.botPreference > bestScore) {
                bestScore = d.botPreference;
                best = w;
            }
        }
        return best;
    }

    private void fire(PlayerState ps, WeaponDef def) {
        ps.weaponCooldown = def.fireDelay * ps.fireRateScale();
        if (def.usesAmmo()) ps.ammo[ps.weapon] -= def.ammoPerShot;
        ps.lastFireTime = matchTime;
        ps.muzzleFlash = 0.06f;

        MathUtil.angleVectors(ps.pitch, ps.yaw, forward, right, up);
        ps.eyePosition(muzzle);

        GameEvent fired = event(GameEvent.FIRE);
        fired.pos.set(muzzle);
        fired.dir.set(forward);
        fired.a = ps.weapon;
        fired.b = ps.index;

        switch (def.style) {
            case WeaponDef.STYLE_MELEE:
                fireMelee(ps, def);
                break;
            case WeaponDef.STYLE_HITSCAN:
                fireBullet(ps, def, def.spread);
                break;
            case WeaponDef.STYLE_SHOTGUN:
                for (int i = 0; i < def.pellets; i++) fireBullet(ps, def, def.spread);
                break;
            case WeaponDef.STYLE_BEAM:
                fireBeam(ps, def);
                break;
            case WeaponDef.STYLE_PROJECTILE:
                fireProjectile(ps, def);
                break;
            default:
                break;
        }
    }

    private void fireMelee(PlayerState ps, WeaponDef def) {
        endPoint.setMa(muzzle, forward, def.range);
        PlayerState hit = traceForPlayer(ps, muzzle, endPoint);
        if (hit == null) return;
        tmp.setSub(hit.origin, ps.origin);
        tmp.normalize();
        damage(hit, ps, def.damage, tmp, def.knockback, def.id);
        GameEvent ev = event(GameEvent.IMPACT_FLESH);
        ev.pos.set(hit.origin.x, hit.origin.y, hit.origin.z + 10f);
        ev.a = def.id;
        ev.b = hit.index;
    }

    private void fireBullet(PlayerState ps, WeaponDef def, float spread) {
        endPoint.setMa(muzzle, forward, def.range);
        if (spread > 0f) {
            // Same cone construction as the original: a random angle around the
            // barrel, with a random magnitude along it.
            float r = rnd.nextFloat() * 6.2831855f;
            float u = (float) Math.sin(r) * crandom() * spread * 16f;
            float rr = (float) Math.cos(r) * crandom() * spread * 16f;
            float scale = def.range / 8192f;
            endPoint.addScaled(right, rr * scale);
            endPoint.addScaled(up, u * scale);
        }

        PlayerState victim = traceForPlayer(ps, muzzle, endPoint);
        if (victim != null) {
            tmp.setSub(hitPoint, muzzle);
            tmp.normalize();
            damage(victim, ps, def.damage, tmp, def.knockback, def.id);
            GameEvent ev = event(GameEvent.IMPACT_FLESH);
            ev.pos.set(hitPoint);
            ev.dir.set(tmp).negate();
            ev.a = def.id;
            ev.b = victim.index;
            if (def.id == WeaponDef.RAILGUN) railTrail(ps, muzzle, hitPoint);
            return;
        }

        collision.traceRay(trace, muzzle, endPoint, Contents.MASK_SHOT);
        if (trace.fraction < 1f) {
            GameEvent ev = event(GameEvent.IMPACT);
            ev.pos.set(trace.endPos);
            ev.dir.set(trace.normal);
            ev.a = def.id;
            ev.b = ps.index;
            if (def.id == WeaponDef.RAILGUN) railTrail(ps, muzzle, trace.endPos);
        } else if (def.id == WeaponDef.RAILGUN) {
            railTrail(ps, muzzle, endPoint);
        }
    }

    private void railTrail(PlayerState ps, Vec3 from, Vec3 to) {
        GameEvent ev = event(GameEvent.RAIL_TRAIL);
        ev.pos.set(from);
        ev.to.set(to);
        ev.b = ps.index;
    }

    private void fireBeam(PlayerState ps, WeaponDef def) {
        endPoint.setMa(muzzle, forward, def.range);
        PlayerState victim = traceForPlayer(ps, muzzle, endPoint);
        Vec3 end = hitPoint;
        if (victim != null) {
            tmp.setSub(hitPoint, muzzle);
            tmp.normalize();
            damage(victim, ps, def.damage, tmp, def.knockback, def.id);
            GameEvent fl = event(GameEvent.IMPACT_FLESH);
            fl.pos.set(hitPoint);
            fl.a = def.id;
            fl.b = victim.index;
        } else {
            collision.traceRay(trace, muzzle, endPoint, Contents.MASK_SHOT);
            end = trace.fraction < 1f ? trace.endPos : endPoint;
            if (trace.fraction < 1f) {
                GameEvent im = event(GameEvent.IMPACT);
                im.pos.set(trace.endPos);
                im.dir.set(trace.normal);
                im.a = def.id;
                im.b = ps.index;
            }
        }
        GameEvent beam = event(GameEvent.BEAM);
        beam.pos.set(muzzle);
        beam.to.set(end);
        beam.b = ps.index;
    }

    private void fireProjectile(PlayerState ps, WeaponDef def) {
        Projectile p = allocProjectile();
        if (p == null) return;
        p.active = true;
        p.weapon = def.id;
        p.owner = ps.index;
        p.id = ++projectileIdCounter;
        // Start slightly in front of the eye, but never on the far side of a wall
        // the player is standing against.
        endPoint.setMa(muzzle, forward, 16f);
        collision.traceRay(trace, muzzle, endPoint, Contents.MASK_SHOT);
        p.origin.set(trace.endPos);
        if (trace.fraction < 1f) p.origin.addScaled(trace.normal, 2f);
        p.velocity.set(forward).scale(def.projectileSpeed);
        p.damage = def.damage;
        p.splashDamage = def.splashDamage;
        p.splashRadius = def.splashRadius;
        p.knockback = def.knockback;
        p.damageScale = ps.damageScale();
        p.age = 0f;
        p.affectedByGravity = def.id == WeaponDef.GRENADE;
        p.bounces = def.id == WeaponDef.GRENADE;
        p.fuse = def.id == WeaponDef.GRENADE ? 2.5f : 8f;
        p.radius = def.id == WeaponDef.PLASMA ? 3f : 5f;
        if (p.affectedByGravity) {
            // Grenades are thrown slightly upwards, like a lobbed weapon should be.
            p.velocity.z += 120f;
        }
    }

    private Projectile allocProjectile() {
        for (Projectile p : projectiles) {
            if (!p.active) return p;
        }
        return null;
    }

    private float crandom() {
        return rnd.nextFloat() * 2f - 1f;
    }

    /** Where the last {@link #traceForPlayer} call actually hit. */
    private final Vec3 hitPoint = new Vec3();

    /**
     * Traces for the first player hit along a ray, respecting world geometry.
     * Returns null when the world blocks the shot first.
     */
    private PlayerState traceForPlayer(PlayerState shooter, Vec3 from, Vec3 to) {
        collision.traceRay(trace, from, to, Contents.MASK_SHOT);
        float worldFrac = trace.fraction;
        hitPoint.set(trace.endPos);

        PlayerState best = null;
        float bestFrac = worldFrac;
        for (PlayerState other : players) {
            if (other == shooter || !other.alive) continue;
            other.mins(entMins);
            other.maxs(entMaxs);
            entMins.add(other.origin);
            entMaxs.add(other.origin);
            float f = rayBox(from, to, entMins, entMaxs);
            if (f >= 0f && f < bestFrac) {
                bestFrac = f;
                best = other;
            }
        }
        if (best != null) {
            hitPoint.setSub(to, from);
            hitPoint.scale(bestFrac).add(from);
        }
        return best;
    }

    /** Ray/box intersection returning the entry fraction, or -1 for a miss. */
    private static float rayBox(Vec3 from, Vec3 to, Vec3 mins, Vec3 maxs) {
        float dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        float tmin = 0f, tmax = 1f;

        float[] o = {from.x, from.y, from.z};
        float[] d = {dx, dy, dz};
        float[] lo = {mins.x, mins.y, mins.z};
        float[] hi = {maxs.x, maxs.y, maxs.z};

        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-6f) {
                if (o[i] < lo[i] || o[i] > hi[i]) return -1f;
                continue;
            }
            float inv = 1f / d[i];
            float t1 = (lo[i] - o[i]) * inv;
            float t2 = (hi[i] - o[i]) * inv;
            if (t1 > t2) {
                float t = t1;
                t1 = t2;
                t2 = t;
            }
            if (t1 > tmin) tmin = t1;
            if (t2 < tmax) tmax = t2;
            if (tmin > tmax) return -1f;
        }
        return tmin;
    }

    // ------------------------------------------------------------- projectiles

    private void updateProjectiles(float dt) {
        for (Projectile p : projectiles) {
            if (!p.active) continue;
            p.age += dt;
            p.fuse -= dt;

            if (p.affectedByGravity) p.velocity.z -= PlayerMove.GRAVITY * dt;

            tmp.setMa(p.origin, p.velocity, dt);
            boxMins.set(-p.radius, -p.radius, -p.radius);
            boxMaxs.set(p.radius, p.radius, p.radius);

            PlayerState victim = projectileHitsPlayer(p, tmp);
            if (victim != null) {
                tmp2.set(p.velocity);
                tmp2.normalize();
                damage(victim, players[p.owner], Math.round(p.damage * p.damageScale), tmp2,
                        p.knockback, p.weapon);
                explode(p, p.origin, victim);
                continue;
            }

            collision.traceBox(trace, p.origin, tmp, boxMins, boxMaxs, Contents.MASK_SHOT);
            if (trace.fraction < 1f) {
                if (p.bounces && p.fuse > 0f) {
                    p.origin.set(trace.endPos);
                    p.velocity.clipVelocity(trace.normal, 1.4f);
                    p.velocity.scale(0.62f);
                    if (p.velocity.length() < 40f && trace.normal.z > 0.7f) {
                        p.velocity.zero();
                    }
                    GameEvent ev = event(GameEvent.IMPACT);
                    ev.pos.set(trace.endPos);
                    ev.dir.set(trace.normal);
                    ev.a = -2;                     // grenade bounce, not a detonation
                    continue;
                }
                p.origin.set(trace.endPos);
                explode(p, trace.endPos, null);
                continue;
            }

            p.origin.set(tmp);
            if (p.fuse <= 0f) {
                explode(p, p.origin, null);
            }
        }
    }

    private PlayerState projectileHitsPlayer(Projectile p, Vec3 to) {
        PlayerState best = null;
        float bestFrac = 1f;
        for (PlayerState other : players) {
            if (!other.alive) continue;
            // Own projectiles are harmless for the first instant so they do not
            // detonate in the shooter's face on spawn.
            if (other.index == p.owner && p.age < 0.06f) continue;
            other.mins(entMins);
            other.maxs(entMaxs);
            entMins.add(other.origin).add(-p.radius, -p.radius, -p.radius);
            entMaxs.add(other.origin).add(p.radius, p.radius, p.radius);
            float f = rayBox(p.origin, to, entMins, entMaxs);
            if (f >= 0f && f < bestFrac) {
                bestFrac = f;
                best = other;
            }
        }
        return best;
    }

    private void explode(Projectile p, Vec3 at, PlayerState directVictim) {
        p.active = false;
        PlayerState attacker = players[p.owner];

        GameEvent ev = event(GameEvent.EXPLOSION);
        ev.pos.set(at);
        ev.a = p.weapon;
        ev.b = p.owner;
        ev.f = p.splashRadius;

        if (p.splashDamage > 0) {
            radiusDamage(at, attacker, Math.round(p.splashDamage * p.damageScale), p.splashRadius,
                    p.knockback / Math.max(1f, p.splashDamage), p.weapon, directVictim);
        } else if (directVictim == null) {
            GameEvent im = event(GameEvent.IMPACT);
            im.pos.set(at);
            im.a = p.weapon;
        }
    }

    // ------------------------------------------------------------------- items

    private void updateItems(float dt) {
        for (ItemEntity it : items) {
            if (it.respawnIn > 0f) it.respawnIn -= dt;
            it.spin = (it.spin + dt * 90f) % 360f;
        }
    }

    private void touchItems(PlayerState ps) {
        for (ItemEntity it : items) {
            if (!it.available()) continue;
            float dx = it.origin.x - ps.origin.x;
            float dy = it.origin.y - ps.origin.y;
            float dz = it.origin.z - ps.origin.z;
            if (dx * dx + dy * dy > ITEM_PICKUP_RADIUS * ITEM_PICKUP_RADIUS) continue;
            if (dz > 48f || dz < -48f) continue;
            if (tryPickup(ps, it)) {
                it.respawnIn = it.def().respawn;
            }
        }
    }

    private boolean tryPickup(PlayerState ps, ItemEntity it) {
        ItemDef def = it.def();
        boolean taken = false;
        switch (def.category) {
            case ItemDef.CAT_HEALTH:
                if (ps.health >= def.cap) return false;
                ps.health = Math.min(def.cap, ps.health + def.amount);
                taken = true;
                break;
            case ItemDef.CAT_ARMOR:
                if (ps.armor >= def.cap) return false;
                ps.armor = Math.min(def.cap, ps.armor + def.amount);
                taken = true;
                break;
            case ItemDef.CAT_WEAPON: {
                int w = def.weapon;
                boolean isNew = !ps.hasWeapon(w);
                WeaponDef wd = WeaponDef.get(w);
                if (!isNew && ps.ammo[w] >= MAX_AMMO) return false;
                ps.giveWeapon(w);
                ps.ammo[w] = Math.min(MAX_AMMO, ps.ammo[w] + Math.max(1, wd.ammoOnPickup));
                taken = true;
                if (isNew && config.autoSwitchWeapons && ps.index == 0) {
                    if (wd.botPreference > WeaponDef.get(ps.weapon).botPreference) switchWeapon(ps, w);
                } else if (isNew && ps.isBot) {
                    if (wd.botPreference > WeaponDef.get(ps.weapon).botPreference) switchWeapon(ps, w);
                }
                break;
            }
            case ItemDef.CAT_AMMO: {
                int w = def.weapon;
                if (ps.ammo[w] >= MAX_AMMO) return false;
                ps.ammo[w] = Math.min(MAX_AMMO, ps.ammo[w] + def.amount);
                taken = true;
                break;
            }
            case ItemDef.CAT_POWERUP:
                ps.powerupTime[def.powerup] = Math.max(ps.powerupTime[def.powerup], def.amount);
                taken = true;
                break;
            default:
                break;
        }
        if (!taken) return false;

        ps.lastPickupTime = matchTime;
        ps.lastPickupItem = def.id;
        GameEvent ev = event(GameEvent.PICKUP);
        ev.pos.set(it.origin);
        ev.a = def.id;
        ev.b = ps.index;
        if (def.category == ItemDef.CAT_POWERUP) {
            GameEvent an = event(GameEvent.ANNOUNCE);
            an.text = def.name.toUpperCase();
            an.b = ps.index;
        }
        return true;
    }

    // ---------------------------------------------------------------- triggers

    private void touchTriggers(PlayerState ps, float dt) {
        ps.mins(entMins);
        ps.maxs(entMaxs);
        entMins.add(ps.origin);
        entMaxs.add(ps.origin);

        ps.launched = false;
        ps.teleported = false;

        for (MapDef.JumpPad pad : map.jumpPads) {
            if (!boxesOverlap(entMins, entMaxs, pad.mins, pad.maxs)) continue;
            ps.velocity.set(pad.velocity);
            ps.onGround = false;
            ps.launched = true;
            ps.padFlight = true;
            GameEvent ev = event(GameEvent.JUMPPAD);
            ev.pos.set(ps.origin);
            ev.b = ps.index;
            break;
        }

        for (MapDef.Teleporter tp : map.teleporters) {
            if (!boxesOverlap(entMins, entMaxs, tp.mins, tp.maxs)) continue;
            GameEvent out = event(GameEvent.TELEPORT);
            out.pos.set(ps.origin);
            out.b = ps.index;
            ps.origin.set(tp.dest);
            ps.yaw = tp.destYaw;
            ps.legsYaw = tp.destYaw;
            // Teleporters preserve speed but re-aim it, as in the original.
            float speed = ps.velocity.length();
            MathUtil.angleVectors(0f, tp.destYaw, forward, right, up);
            ps.velocity.set(forward).scale(Math.max(speed, 220f));
            ps.teleported = true;
            GameEvent in = event(GameEvent.TELEPORT);
            in.pos.set(ps.origin);
            in.b = ps.index;
            break;
        }

        nextHurtTime[ps.index] -= dt;
        for (MapDef.HurtVolume hv : map.hurtVolumes) {
            if (!boxesOverlap(entMins, entMaxs, hv.mins, hv.maxs)) continue;
            if (hv.instantKill) {
                tmp.set(0, 0, 1);
                damage(ps, null, 9999, tmp, 0f, -1);
                return;
            }
            if (nextHurtTime[ps.index] <= 0f) {
                nextHurtTime[ps.index] = hv.interval;
                tmp.set(0, 0, 1);
                damage(ps, null, hv.damage, tmp, 0f, -1);
            }
            return;
        }
    }

    private static boolean boxesOverlap(Vec3 aMin, Vec3 aMax, Vec3 bMin, Vec3 bMax) {
        return aMin.x <= bMax.x && aMax.x >= bMin.x
                && aMin.y <= bMax.y && aMax.y >= bMin.y
                && aMin.z <= bMax.z && aMax.z >= bMin.z;
    }

    // ------------------------------------------------------------- match rules

    private void checkMatchEnd() {
        boolean over = false;
        if (config.fragLimit > 0) {
            for (PlayerState ps : players) {
                if (ps.frags >= config.fragLimit) over = true;
            }
        }
        if (config.timeLimitSeconds > 0 && matchTime >= config.timeLimitSeconds) over = true;
        if (!over) return;

        state = STATE_OVER;
        int best = 0;
        for (int i = 1; i < players.length; i++) {
            if (players[i].frags > players[best].frags) best = i;
        }
        winner = best;
        GameEvent ev = event(GameEvent.MATCH_END);
        ev.b = winner;
        GameEvent an = event(GameEvent.ANNOUNCE);
        an.text = winner == 0 ? "YOU WIN" : players[winner].name + " WINS";
    }

    public float timeRemaining() {
        if (config.timeLimitSeconds <= 0) return 0f;
        return Math.max(0f, config.timeLimitSeconds - matchTime);
    }

    /** Players sorted by score, best first. */
    public PlayerState[] scoreboard() {
        PlayerState[] sorted = players.clone();
        java.util.Arrays.sort(sorted, (a, b) -> {
            if (a.frags != b.frags) return b.frags - a.frags;
            return a.deaths - b.deaths;
        });
        return sorted;
    }

    // ------------------------------------------------------------ trace support

    @Override
    public void trace(Trace out, Vec3 start, Vec3 end, Vec3 mins, Vec3 maxs, int mask, int skipEntity) {
        collision.traceBox(out, start, end, mins, maxs, mask);

        // Players are solid to each other.
        for (PlayerState other : players) {
            if (other.index == skipEntity || !other.alive) continue;
            other.mins(entMins);
            other.maxs(entMaxs);
            // Minkowski expansion: grow the target box by the mover's box.
            entMins.set(other.origin.x + entMins.x - maxs.x,
                    other.origin.y + entMins.y - maxs.y,
                    other.origin.z + entMins.z - maxs.z);
            other.maxs(tmp);
            entMaxs.set(other.origin.x + tmp.x - mins.x,
                    other.origin.y + tmp.y - mins.y,
                    other.origin.z + tmp.z - mins.z);

            float f = rayBox(start, end, entMins, entMaxs);
            if (f < 0f || f >= out.fraction) continue;

            out.fraction = f;
            out.endPos.setSub(end, start);
            out.endPos.scale(f).add(start);
            out.entity = other.index;
            out.brush = null;
            out.contents = Contents.SOLID;
            out.surfaceFlags = 0;
            // Push apart along the dominant axis of separation.
            float cx = out.endPos.x - other.origin.x;
            float cy = out.endPos.y - other.origin.y;
            if (Math.abs(cx) > Math.abs(cy)) {
                out.normal.set(Math.signum(cx), 0, 0);
            } else {
                out.normal.set(0, Math.signum(cy), 0);
            }
            if (out.endPos.z - other.origin.z > 40f) out.normal.set(0, 0, 1);
        }
    }

    // ------------------------------------------------------------------ events

    public GameEvent event(int type) {
        GameEvent ev;
        if (eventPoolUsed < eventPool.size()) {
            ev = eventPool.get(eventPoolUsed);
        } else {
            ev = new GameEvent();
            eventPool.add(ev);
        }
        eventPoolUsed++;
        ev.reset(type);
        events.add(ev);
        return ev;
    }

    private void recycleEvents() {
        events.clear();
        eventPoolUsed = 0;
    }

    public PlayerState localPlayer() {
        return players[0];
    }
}
