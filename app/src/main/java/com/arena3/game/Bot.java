package com.arena3.game;

import com.arena3.core.MathUtil;
import com.arena3.core.Vec3;

import java.util.Random;

/**
 * Bot brain: picks a goal, walks the nav graph to reach it, and fights whatever
 * it can see on the way.
 *
 * <p>Skill (0-4) moves every dial at once — reaction time, turn speed, aim
 * jitter, how well it leads projectiles, whether it bothers to strafe jump
 * between pickups, and how carefully it avoids its own splash damage.
 */
public final class Bot {

    private static final int MAX_PATH = 256;
    private static final float REPATH_INTERVAL = 1.0f;
    private static final float GOAL_INTERVAL = 2.0f;
    private static final float ENEMY_MEMORY = 2.5f;
    private static final float NODE_REACH_DIST = 52f;

    private final GameWorld world;
    private final PlayerState me;
    private final Random rnd;
    private final MoveInput cmd;

    // navigation
    private final int[] path = new int[MAX_PATH];
    private int pathLen;
    private int pathIndex;
    private int goalNode = -1;
    private ItemEntity goalItem;
    private float repathTimer;
    private float goalTimer;
    private float stuckTimer;
    private final Vec3 lastPos = new Vec3();

    // combat
    private int enemy = -1;
    private float enemyVisibleFor;
    private float enemyMemory;
    private float reactionTimer;
    private final Vec3 lastKnownEnemyPos = new Vec3();
    private float aimYaw;
    private float aimPitch;
    private final Vec3 aimNoise = new Vec3();
    private float noiseTimer;
    private float strafeSign = 1f;
    private float strafeTimer;
    private float weaponTimer;

    // movement flavour
    private float hopTimer;
    private float wanderYaw;

    // scratch
    private final Vec3 eye = new Vec3();
    private final Vec3 targetEye = new Vec3();
    private final Vec3 aimPoint = new Vec3();
    private final Vec3 dir = new Vec3();
    private final Vec3 forward = new Vec3();
    private final Vec3 right = new Vec3();
    private final Vec3 up = new Vec3();
    private final Vec3 nodePos = new Vec3();
    private final Vec3 probe = new Vec3();
    private final Vec3 probeEnd = new Vec3();
    private final Vec3 probeDir = new Vec3();
    /** Link type currently being traversed, so the edge guard knows to allow jumps. */
    private int currentLinkType = NavGraph.LINK_WALK;
    private final Vec3 boxMins = new Vec3(-15, -15, -24);
    private final Vec3 boxMaxs = new Vec3(15, 15, 32);
    private final Trace trace = new Trace();

    public Bot(GameWorld world, PlayerState me, Random rnd) {
        this.world = world;
        this.me = me;
        this.rnd = rnd;
        this.cmd = world.inputs[me.index];
        this.aimYaw = me.yaw;
        this.wanderYaw = me.yaw;
    }

    public void onSpawn() {
        pathLen = 0;
        pathIndex = 0;
        goalNode = -1;
        goalItem = null;
        enemy = -1;
        enemyMemory = 0f;
        repathTimer = 0f;
        goalTimer = 0f;
        aimYaw = me.yaw;
        aimPitch = 0f;
        lastPos.set(me.origin);
    }

    // ---- skill-derived constants ----

    private float reactionTime() {
        return 0.45f - me.skill * 0.08f;
    }

    private float turnSpeed() {
        return 200f + me.skill * 150f;
    }

    private float aimJitterDegrees() {
        return 7.5f - me.skill * 1.7f;
    }

    private float fireConeDegrees() {
        return 9f - me.skill * 1.2f;
    }

    private float sightRange() {
        return 1400f + me.skill * 500f;
    }

    private float leadAccuracy() {
        return me.skill / 4f;
    }

    private boolean likesStrafeJumping() {
        return me.skill >= 2;
    }

    // ------------------------------------------------------------------- think

    public void think(float dt) {
        cmd.clear();
        if (!me.alive) {
            // Dead bots just wait for the respawn timer to elapse.
            return;
        }
        if (world.state != GameWorld.STATE_LIVE) {
            return;
        }

        me.eyePosition(eye);
        updateEnemy(dt);
        updateGoal(dt);

        if (enemy >= 0 && reactionTimer <= 0f) {
            fight(dt);
        } else {
            travel(dt);
        }

        // Landing from a jump pad at speed will carry a bot straight off a small
        // platform, and friction alone is far too slow to stop it. Running
        // against the momentum is not — so that takes priority over everything
        // else, unless the route genuinely wants us airborne.
        if (me.onGround && !me.padFlight && me.velocity.length2d() > 110f
                && currentLinkType != NavGraph.LINK_JUMP) {
            probeDir.set(me.velocity.x, me.velocity.y, 0f);
            probeDir.normalize();
            if (!safeToStep(probeDir)) {
                brake();
                cmd.jump = false;
            }
        }

        // Once a pad has thrown us, the arc is already aimed at its landing spot —
        // steering mid-flight only drags it off target.
        if (me.padFlight) {
            cmd.forward = 0f;
            cmd.right = 0f;
            cmd.jump = false;
        }

        applyAim(dt);
        detectStuck(dt);
    }

    // ------------------------------------------------------------------ senses

    private void updateEnemy(float dt) {
        enemyMemory = Math.max(0f, enemyMemory - dt);
        reactionTimer = Math.max(0f, reactionTimer - dt);

        int best = -1;
        float bestScore = -1f;
        for (PlayerState other : world.players) {
            if (other == me || !other.alive) continue;
            float dist = me.origin.distanceTo(other.origin);
            if (dist > sightRange()) continue;

            // Invisible opponents are only noticed up close.
            if (other.hasPowerup(ItemDef.PW_INVIS) && dist > 380f && !other.firing) continue;

            targetEye.set(other.origin.x, other.origin.y, other.origin.z + 12f);
            if (!inViewCone(targetEye) && !recentlyAttackedBy(other)) continue;
            if (!world.lineOfSight(eye, targetEye)) continue;

            // Prefer whoever is closest, with a nudge towards the current target
            // so the bot does not flip-flop between two enemies.
            float score = 4000f - dist + (other.index == enemy ? 500f : 0f);
            if (score > bestScore) {
                bestScore = score;
                best = other.index;
            }
        }

        if (best >= 0) {
            if (enemy != best) {
                enemy = best;
                reactionTimer = reactionTime();
                enemyVisibleFor = 0f;
            }
            enemyVisibleFor += dt;
            enemyMemory = ENEMY_MEMORY;
            lastKnownEnemyPos.set(world.players[best].origin);
        } else if (enemyMemory <= 0f) {
            enemy = -1;
            enemyVisibleFor = 0f;
        }
    }

    private boolean recentlyAttackedBy(PlayerState other) {
        return me.lastAttacker == other.index && world.matchTime - me.lastDamageTime < 2f;
    }

    private boolean inViewCone(Vec3 point) {
        dir.setSub(point, eye);
        dir.normalize();
        MathUtil.angleVectors(me.pitch, me.yaw, forward, right, up);
        // Roughly 160 degrees of awareness, widening with skill.
        float threshold = -0.35f - me.skill * 0.06f;
        return forward.dot(dir) > threshold;
    }

    // ------------------------------------------------------------------ combat

    private void fight(float dt) {
        PlayerState target = world.players[enemy];
        boolean visible = target.alive && world.lineOfSight(eye, aimTargetOf(target));

        if (!visible) {
            // Chase the last known position instead of standing around.
            travelTo(lastKnownEnemyPos, dt);
            return;
        }

        float dist = me.origin.distanceTo(target.origin);
        chooseWeapon(target, dist, dt);
        aimAt(target, dist, dt);
        combatMove(target, dist, dt);

        float error = aimError(target);
        boolean coneOk = error < fireConeDegrees();
        boolean safe = splashSafe(dist);
        cmd.fire = coneOk && safe && me.weaponSwitchTime <= 0f;

        // The gauntlet needs to be right on top of someone.
        if (me.weapon == WeaponDef.GAUNTLET && dist > 70f) cmd.fire = false;
    }

    private Vec3 aimTargetOf(PlayerState target) {
        targetEye.set(target.origin.x, target.origin.y, target.origin.z + (target.ducked ? 2f : 12f));
        return targetEye;
    }

    private void chooseWeapon(PlayerState target, float dist, float dt) {
        weaponTimer -= dt;
        if (weaponTimer > 0f) return;
        weaponTimer = 0.6f;

        int best = -1;
        float bestScore = -1f;
        for (int w = 0; w < WeaponDef.COUNT; w++) {
            if (!me.hasWeapon(w)) continue;
            WeaponDef d = WeaponDef.get(w);
            if (d.usesAmmo() && me.ammo[w] < d.ammoPerShot) continue;
            if (d.style == WeaponDef.STYLE_BEAM && dist > d.range * 0.95f) continue;
            if (d.style == WeaponDef.STYLE_MELEE && dist > 80f) continue;

            float rangeFit = 1f / (1f + Math.abs(dist - d.botIdealRange) / Math.max(120f, d.botIdealRange));
            float score = d.botPreference * (0.45f + rangeFit);
            // Do not pick something that will blow us up at this range.
            if (d.splashDamage > 0 && dist < d.splashRadius * 1.35f) score *= 0.18f;
            // Low-skill bots stick to simple weapons.
            if (me.skill <= 1 && (w == WeaponDef.RAILGUN || w == WeaponDef.VOIDCANNON)) score *= 0.7f;
            if (score > bestScore) {
                bestScore = score;
                best = w;
            }
        }
        if (best >= 0 && best != me.weapon) {
            cmd.selectWeapon = best;
        }
    }

    private boolean splashSafe(float dist) {
        WeaponDef d = WeaponDef.get(me.weapon);
        if (d.splashDamage <= 0) return true;
        if (dist > d.splashRadius * 1.2f) return true;
        // Desperate bots will still take the trade when they are healthy.
        return me.health > 80 && me.skill <= 1;
    }

    private void aimAt(PlayerState target, float dist, float dt) {
        aimPoint.set(aimTargetOf(target));

        WeaponDef d = WeaponDef.get(me.weapon);
        if (d.isProjectile() && d.projectileSpeed > 0f) {
            float travel = dist / d.projectileSpeed;
            float lead = leadAccuracy();
            aimPoint.addScaled(target.velocity, travel * lead);
            if (d.isLobbed()) {
                // Lob grenades a little above the target.
                aimPoint.z += 0.5f * PlayerMove.GRAVITY * travel * travel;
            }
        }

        // Slow, jittery aim at low skill; crisp tracking at high skill.
        noiseTimer -= dt;
        if (noiseTimer <= 0f) {
            noiseTimer = 0.25f + rnd.nextFloat() * 0.2f;
            float j = aimJitterDegrees();
            aimNoise.set((rnd.nextFloat() * 2f - 1f) * j, (rnd.nextFloat() * 2f - 1f) * j * 0.6f, 0f);
        }

        dir.setSub(aimPoint, eye);
        dir.normalize();
        aimYaw = MathUtil.normalizeAngle(MathUtil.yawOf(dir) + aimNoise.x);
        aimPitch = MathUtil.clamp(MathUtil.pitchOf(dir) + aimNoise.y, -89f, 89f);
    }

    private float aimError(PlayerState target) {
        dir.setSub(aimTargetOf(target), eye);
        dir.normalize();
        float wantYaw = MathUtil.yawOf(dir);
        float wantPitch = MathUtil.pitchOf(dir);
        float dy = Math.abs(MathUtil.angleDelta(me.yaw, wantYaw));
        float dp = Math.abs(MathUtil.angleDelta(me.pitch, wantPitch));
        return Math.max(dy, dp);
    }

    private void combatMove(PlayerState target, float dist, float dt) {
        WeaponDef d = WeaponDef.get(me.weapon);
        float ideal = d.botIdealRange;

        // Approach or back off to reach the weapon's comfortable range.
        float wantForward;
        if (dist > ideal * 1.25f) {
            wantForward = 1f;
        } else if (dist < ideal * 0.6f) {
            wantForward = -1f;
        } else {
            wantForward = 0.15f;
        }

        strafeTimer -= dt;
        if (strafeTimer <= 0f) {
            strafeTimer = 0.5f + rnd.nextFloat() * 0.9f;
            strafeSign = -strafeSign;
        }
        float wantRight = strafeSign * (0.6f + me.skill * 0.1f);

        // Convert intent (relative to the aim direction) into movement input and
        // veto any component that would walk us off a ledge or into lava.
        MathUtil.angleVectors(0f, aimYaw, forward, right, up);
        dir.zero();
        dir.addScaled(forward, wantForward);
        dir.addScaled(right, wantRight);
        if (dir.length2d() > 0.01f) {
            if (!safeToStep(dir)) {
                // Reverse the strafe and try again; if still unsafe, hold ground.
                strafeSign = -strafeSign;
                dir.zero();
                dir.addScaled(forward, wantForward);
                dir.addScaled(right, strafeSign * 0.8f);
                if (!safeToStep(dir)) {
                    dir.zero();
                    dir.addScaled(forward, -0.4f);
                    if (!safeToStep(dir)) {
                        brake();
                        return;
                    }
                }
            }
        }
        setMoveFromWorldDir(dir, aimYaw);

        // Dodge-jump now and then, more often at higher skill.
        hopTimer -= dt;
        if (hopTimer <= 0f) {
            hopTimer = 1.4f - me.skill * 0.15f + rnd.nextFloat();
            if (me.onGround && rnd.nextFloat() < 0.25f + me.skill * 0.1f) cmd.jump = true;
        }
    }

    // -------------------------------------------------------------- navigation

    private void updateGoal(float dt) {
        goalTimer -= dt;
        if (goalItem != null && !goalItem.available()) {
            goalItem = null;
            goalTimer = 0f;
        }
        if (goalTimer > 0f && goalNode >= 0) return;
        goalTimer = GOAL_INTERVAL + rnd.nextFloat();

        ItemEntity bestItem = null;
        float bestScore = 0.15f;                 // below this, just roam
        for (ItemEntity it : world.items) {
            if (!it.available()) continue;
            float need = needFor(it.def());
            if (need <= 0f) continue;
            float dist = me.origin.distanceTo(it.origin);
            if (dist > 3000f) continue;
            float score = it.def().botWeight * need * (1200f / (600f + dist));
            if (score > bestScore) {
                bestScore = score;
                bestItem = it;
            }
        }

        if (bestItem != null) {
            goalItem = bestItem;
            goalNode = world.nav.nearest(bestItem.origin);
        } else if (enemy >= 0) {
            goalItem = null;
            goalNode = world.nav.nearest(lastKnownEnemyPos);
        } else {
            goalItem = null;
            goalNode = world.nav.nodeCount > 0 ? rnd.nextInt(world.nav.nodeCount) : -1;
        }
        repathTimer = 0f;
    }

    /** 0 when the bot has no use for an item, up to ~1.5 when it badly wants it. */
    private float needFor(ItemDef def) {
        switch (def.category) {
            case ItemDef.CAT_HEALTH:
                if (me.health >= def.cap) return 0f;
                return MathUtil.clamp(1.4f - me.health / 100f, 0.05f, 1.4f);
            case ItemDef.CAT_ARMOR:
                if (me.armor >= def.cap) return 0f;
                return MathUtil.clamp(1.2f - me.armor / 120f, 0.1f, 1.2f);
            case ItemDef.CAT_WEAPON:
                return me.hasWeapon(def.weapon) ? 0.3f : 1.5f;
            case ItemDef.CAT_AMMO: {
                if (!me.hasWeapon(def.weapon)) return 0.15f;
                float have = me.ammo[def.weapon];
                return MathUtil.clamp(1f - have / 60f, 0.05f, 1f);
            }
            case ItemDef.CAT_POWERUP:
                return 1.5f;
            default:
                return 0f;
        }
    }

    private void travel(float dt) {
        if (enemyMemory > 0f && enemy >= 0) {
            travelTo(lastKnownEnemyPos, dt);
            return;
        }
        followPath(dt);
        // Look where we are going.
        aimPitch = MathUtil.approach(aimPitch, 0f, 6f, dt);
    }

    private void travelTo(Vec3 worldPos, float dt) {
        int node = world.nav.nearest(worldPos);
        if (node != goalNode) {
            goalNode = node;
            repathTimer = 0f;
        }
        followPath(dt);
    }

    private void followPath(float dt) {
        repathTimer -= dt;
        int myNode = world.nav.nearest(me.origin);
        if (myNode < 0 || goalNode < 0) {
            wander(dt);
            return;
        }
        if (repathTimer <= 0f || pathLen == 0 || pathIndex >= pathLen) {
            repathTimer = REPATH_INTERVAL;
            pathLen = world.nav.findPath(myNode, goalNode, path);
            pathIndex = 0;
            if (pathLen == 0) {
                wander(dt);
                return;
            }
        }

        // Skip nodes we have already reached (and the one we are standing on).
        while (pathIndex < pathLen) {
            world.nav.nodePos(path[pathIndex], nodePos);
            float flat = nodePos.distance2dTo(me.origin);
            if (flat < NODE_REACH_DIST && Math.abs(nodePos.z - me.origin.z) < 60f) {
                pathIndex++;
            } else {
                break;
            }
        }
        if (pathIndex >= pathLen) {
            pathLen = 0;
            goalTimer = 0f;
            return;
        }

        world.nav.nodePos(path[pathIndex], nodePos);
        dir.setSub(nodePos, me.origin);
        dir.z = 0;
        dir.normalize();

        // Jump for links that need it, and when a step is in the way.
        int linkType = pathIndex > 0 ? world.nav.linkTypeBetween(path[pathIndex - 1], path[pathIndex])
                : NavGraph.LINK_WALK;
        currentLinkType = linkType;
        if (linkType == NavGraph.LINK_JUMP && me.onGround
                && nodePos.distance2dTo(me.origin) < 140f) {
            cmd.jump = true;
        }

        float travelYaw = MathUtil.yawOf(dir);

        // Long stretches of ordinary floor are covered by strafe jumping, exactly
        // as a human would. Anything that needs precision — a jump pad, a gap, a
        // ledge — is walked instead.
        boolean canDash = likesStrafeJumping() && !me.ducked
                && linkType == NavGraph.LINK_WALK
                && remainingPathDistance() > 400f
                && safeToStep(dir);
        if (canDash) {
            strafeTimer -= dt;
            if (strafeTimer <= 0f) {
                strafeTimer = 0.7f + rnd.nextFloat() * 0.5f;
                strafeSign = -strafeSign;
            }
            cmd.jump = true;
            // Turn slightly into the strafe while airborne: that is what converts
            // air acceleration into extra speed.
            float offset = me.onGround ? 0f : strafeSign * 22f;
            aimYaw = MathUtil.normalizeAngle(travelYaw + offset);
            cmd.forward = 1f;
            cmd.right = me.onGround ? 0f : strafeSign;
            return;
        }

        // Refuse to walk into a pit unless the route explicitly calls for a jump.
        if (me.onGround && linkType != NavGraph.LINK_JUMP && linkType != NavGraph.LINK_FALL
                && !safeToStep(dir)) {
            brake();
            pathLen = 0;
            repathTimer = 0f;
            goalTimer = 0f;
            return;
        }

        aimYaw = travelYaw;
        setMoveFromWorldDir(dir, aimYaw);
        cmd.forward = Math.max(cmd.forward, 0.9f);
    }

    private final Vec3 pathCursor = new Vec3();

    /** Rough distance still to cover, capped at a few nodes ahead. */
    private float remainingPathDistance() {
        float total = 0f;
        pathCursor.set(me.origin);
        for (int i = pathIndex; i < pathLen && i < pathIndex + 6; i++) {
            world.nav.nodePos(path[i], nodePos);
            total += pathCursor.distance2dTo(nodePos);
            pathCursor.set(nodePos);
        }
        return total;
    }

    private void wander(float dt) {
        wanderYaw = MathUtil.normalizeAngle(wanderYaw + (rnd.nextFloat() - 0.5f) * 120f * dt);
        MathUtil.angleVectors(0f, wanderYaw, forward, right, up);
        if (!safeToStep(forward)) {
            wanderYaw = MathUtil.normalizeAngle(wanderYaw + 150f);
        }
        aimYaw = wanderYaw;
        cmd.forward = 0.8f;
    }

    /** Runs against the current velocity to stop short of an edge. */
    private void brake() {
        if (me.velocity.length2d() < 40f) {
            cmd.forward = 0f;
            cmd.right = 0f;
            return;
        }
        dir.set(-me.velocity.x, -me.velocity.y, 0f);
        dir.normalize();
        setMoveFromWorldDir(dir, aimYaw);
    }

    /** Projects a world-space direction onto the view basis for the move command. */
    private void setMoveFromWorldDir(Vec3 worldDir, float yaw) {
        if (worldDir.length2d() < 0.001f) {
            cmd.forward = 0f;
            cmd.right = 0f;
            return;
        }
        MathUtil.angleVectors(0f, yaw, forward, right, up);
        float f = worldDir.x * forward.x + worldDir.y * forward.y;
        float r = worldDir.x * right.x + worldDir.y * right.y;
        float len = (float) Math.sqrt(f * f + r * r);
        if (len > 1e-4f) {
            f /= len;
            r /= len;
        }
        cmd.forward = f;
        cmd.right = r;
    }

    /**
     * True when continuing along {@code d} lands on solid ground that is not lava
     * and not a long drop. The look-ahead scales with speed, because vetoing the
     * input does not stop a bot that is already sliding towards the edge.
     */
    private boolean safeToStep(Vec3 d) {
        float lookAhead = Math.max(80f, me.velocity.length2d() * 0.7f);
        probe.set(me.origin);
        probe.addScaled(d, lookAhead / Math.max(0.001f, d.length2d()));
        probe.z = me.origin.z;

        world.collision.traceBox(trace, me.origin, probe, boxMins, boxMaxs, Contents.MASK_PLAYER);
        probe.set(trace.endPos);

        probeEnd.set(probe.x, probe.y, probe.z - 320f);
        world.collision.traceBox(trace, probe, probeEnd, boxMins, boxMaxs, Contents.MASK_PLAYER);
        if (trace.fraction >= 1f) return false;                          // long drop
        return !landsInHazard(trace.endPos);
    }

    /** True when the given landing spot is inside lava or a kill volume. */
    private boolean landsInHazard(Vec3 spot) {
        for (MapDef.HurtVolume hv : world.map.hurtVolumes) {
            if (spot.x >= hv.mins.x - 8f && spot.x <= hv.maxs.x + 8f
                    && spot.y >= hv.mins.y - 8f && spot.y <= hv.maxs.y + 8f
                    && spot.z >= hv.mins.z - 40f && spot.z <= hv.maxs.z + 40f) {
                return true;
            }
        }
        return false;
    }

    private void detectStuck(float dt) {
        float moved = me.origin.distance2dTo(lastPos);
        boolean wantsToMove = Math.abs(cmd.forward) > 0.2f || Math.abs(cmd.right) > 0.2f;
        // Below ~25 u/s while trying to run means something is in the way.
        if (wantsToMove && moved < 25f * dt) {
            stuckTimer += dt;
        } else {
            stuckTimer = Math.max(0f, stuckTimer - dt * 2f);
        }
        lastPos.set(me.origin);

        if (stuckTimer > 0.5f) {
            cmd.jump = true;
            aimYaw = MathUtil.normalizeAngle(aimYaw + 60f + rnd.nextFloat() * 90f);
            pathLen = 0;
            goalTimer = 0f;
            repathTimer = 0f;
            if (stuckTimer > 1.2f) {
                stuckTimer = 0f;
                goalNode = world.nav.nodeCount > 0 ? rnd.nextInt(world.nav.nodeCount) : -1;
            }
        }
    }

    /** Moves the view towards the desired angles at the skill's turn rate. */
    private void applyAim(float dt) {
        float rate = turnSpeed() * dt;
        float newYaw = MathUtil.angleTowards(me.yaw, aimYaw, rate);
        float newPitch = MathUtil.angleTowards(me.pitch, aimPitch, rate);
        cmd.deltaYaw = MathUtil.angleDelta(me.yaw, newYaw);
        cmd.deltaPitch = MathUtil.angleDelta(me.pitch, newPitch);
    }
}
