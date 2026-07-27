package com.arena3.game;

import com.arena3.core.MathUtil;
import com.arena3.core.Vec3;

/**
 * The movement code, ported in spirit from Quake III's pmove: ground friction
 * with a stop-speed floor, separate ground and air acceleration, plane-clipping
 * slide moves with crease handling, and 18-unit step-ups.
 *
 * <p>The important consequence of getting this exactly right is that all the
 * emergent movement survives — strafe jumping accelerates you past the 320 u/s
 * run cap, circle jumps work, and a rocket at your feet throws you across the
 * map.
 */
public final class PlayerMove {

    public static final float GRAVITY = 800f;
    public static final float RUN_SPEED = 320f;
    public static final float JUMP_VELOCITY = 270f;
    public static final float STEP_SIZE = 18f;
    public static final float MIN_WALK_NORMAL = 0.7f;
    public static final float OVERCLIP = 1.001f;

    private static final float FRICTION = 6f;
    private static final float STOP_SPEED = 100f;
    private static final float ACCELERATE = 10f;
    private static final float AIR_ACCELERATE = 1f;
    private static final float DUCK_SCALE = 0.25f;
    private static final int MAX_CLIP_PLANES = 5;
    private static final float JUMP_BUFFER = 0.12f;
    private static final float MAX_STEP_TIME = 1f / 60f;

    private Trace.Provider provider;
    private PlayerState ps;
    private MoveInput cmd;
    private float frametime;
    private boolean groundPlane;
    private boolean walking;
    private final Trace groundTrace = new Trace();

    // scratch
    private final Trace trace = new Trace();
    private final Vec3 forward = new Vec3();
    private final Vec3 right = new Vec3();
    private final Vec3 up = new Vec3();
    private final Vec3 wishVel = new Vec3();
    private final Vec3 wishDir = new Vec3();
    private final Vec3 mins = new Vec3();
    private final Vec3 maxs = new Vec3();
    private final Vec3 end = new Vec3();
    private final Vec3 startO = new Vec3();
    private final Vec3 startV = new Vec3();
    private final Vec3 downPos = new Vec3();
    private final Vec3 upPos = new Vec3();
    private final Vec3 endVelocity = new Vec3();
    private final Vec3 clipVel = new Vec3();
    private final Vec3 endClipVel = new Vec3();
    private final Vec3 crease = new Vec3();
    private final Vec3[] planes = new Vec3[MAX_CLIP_PLANES];

    public PlayerMove() {
        for (int i = 0; i < planes.length; i++) planes[i] = new Vec3();
    }

    /** Advances one player by {@code dt}, splitting long frames into substeps. */
    public void move(PlayerState state, MoveInput input, float dt, Trace.Provider tp) {
        this.ps = state;
        this.cmd = input;
        this.provider = tp;

        applyViewAngles(dt);

        state.jumpBuffer = Math.max(0f, state.jumpBuffer - dt);
        if (input.jump) state.jumpBuffer = JUMP_BUFFER;
        if (!input.jump) state.jumpHeld = false;

        float remaining = Math.min(dt, 0.25f);
        while (remaining > 0f) {
            float step = Math.min(remaining, MAX_STEP_TIME);
            singleMove(step);
            remaining -= step;
        }
        this.ps = null;
        this.cmd = null;
        this.provider = null;
    }

    private void applyViewAngles(float dt) {
        ps.yaw = MathUtil.normalizeAngle(ps.yaw + cmd.deltaYaw);
        ps.pitch = MathUtil.clamp(ps.pitch + cmd.deltaPitch, -89f, 89f);
        // Legs turn to follow the view, but lag it a little.
        ps.legsYaw = MathUtil.angleTowards(ps.legsYaw, ps.yaw, 540f * dt);
    }

    private void singleMove(float dt) {
        frametime = dt;

        checkDuck();
        groundTrace();

        boolean wasOnGround = ps.onGround;
        // Captured before the move, because landing zeroes the downward speed.
        float fallSpeed = ps.velocity.z;
        if (checkJump()) {
            airMove();
        } else if (walking) {
            walkMove();
        } else {
            airMove();
        }

        groundTrace();

        if (!wasOnGround && ps.onGround) {
            ps.landImpact = -Math.min(0f, fallSpeed);
            ps.airTime = 0f;
        } else if (!ps.onGround) {
            ps.airTime += dt;
        }

        // Bob cycle only advances while actually running along the ground.
        if (ps.onGround) {
            float speed2d = ps.velocity.length2d();
            ps.bobCycle += speed2d * dt * 0.0055f;
            ps.bobFraction = Math.min(1f, speed2d / RUN_SPEED);
        } else {
            ps.bobFraction *= Math.max(0f, 1f - dt * 4f);
        }
    }

    // ------------------------------------------------------------------ ground

    private void groundTrace() {
        ps.mins(mins);
        ps.maxs(maxs);
        end.set(ps.origin.x, ps.origin.y, ps.origin.z - 0.25f);
        provider.trace(groundTrace, ps.origin, end, mins, maxs, Contents.MASK_PLAYER, ps.index);

        if (groundTrace.fraction == 1f) {
            ps.onGround = false;
            groundPlane = false;
            walking = false;
            return;
        }
        // A slope too steep to stand on still counts as a contact plane for the
        // slide move, but you keep sliding down it.
        if (groundTrace.normal.z < MIN_WALK_NORMAL) {
            ps.onGround = false;
            groundPlane = true;
            walking = false;
            ps.groundNormal.set(groundTrace.normal);
            return;
        }
        // Jumping up through a ceilingless gap: ignore ground while rising.
        if (ps.velocity.z > 0 && groundTrace.normal.z > 0 && ps.velocity.dot(groundTrace.normal) > 10f) {
            ps.onGround = false;
            groundPlane = false;
            walking = false;
            return;
        }

        ps.onGround = true;
        groundPlane = true;
        walking = true;
        ps.groundNormal.set(groundTrace.normal);
    }

    private void checkDuck() {
        boolean wantDuck = cmd.crouch;
        if (wantDuck) {
            ps.ducked = true;
            return;
        }
        if (!ps.ducked) return;
        // Only stand back up when there is headroom.
        mins.set(-15, -15, -24);
        maxs.set(15, 15, 32);
        provider.trace(trace, ps.origin, ps.origin, mins, maxs, Contents.MASK_PLAYER, ps.index);
        if (!trace.allSolid && !trace.startSolid) ps.ducked = false;
    }

    private boolean checkJump() {
        if (ps.jumpBuffer <= 0f) return false;
        if (ps.jumpHeld) return false;
        if (!walking) return false;

        ps.jumpBuffer = 0f;
        ps.jumpHeld = true;
        ps.onGround = false;
        groundPlane = false;
        walking = false;
        ps.velocity.z = JUMP_VELOCITY;
        ps.airTime = 0f;
        return true;
    }

    // ------------------------------------------------------------------- moves

    private void walkMove() {
        friction();

        float scale = cmdScale();
        MathUtil.angleVectors(0f, ps.yaw, forward, right, up);

        // Project the movement basis onto the ground plane so running up a ramp
        // does not lose speed.
        forward.clipVelocity(ps.groundNormal, OVERCLIP);
        right.clipVelocity(ps.groundNormal, OVERCLIP);
        forward.normalize();
        right.normalize();

        wishVel.zero();
        wishVel.addScaled(forward, cmd.forward * scale);
        wishVel.addScaled(right, cmd.right * scale);

        wishDir.set(wishVel);
        float wishSpeed = wishDir.normalize();
        accelerate(wishDir, wishSpeed, ACCELERATE);

        ps.velocity.clipVelocity(ps.groundNormal, OVERCLIP);

        if (ps.velocity.x == 0f && ps.velocity.y == 0f) return;
        stepSlideMove(false);
    }

    private void airMove() {
        friction();

        float scale = cmdScale();
        MathUtil.angleVectors(0f, ps.yaw, forward, right, up);
        forward.z = 0;
        right.z = 0;
        forward.normalize();
        right.normalize();

        wishVel.zero();
        wishVel.addScaled(forward, cmd.forward * scale);
        wishVel.addScaled(right, cmd.right * scale);
        wishVel.z = 0;

        wishDir.set(wishVel);
        float wishSpeed = wishDir.normalize();

        // The low air acceleration is what allows strafe jumping: turning the
        // view while holding a strafe key adds a little speed every frame.
        accelerate(wishDir, wishSpeed, AIR_ACCELERATE);

        if (groundPlane) {
            ps.velocity.clipVelocity(ps.groundNormal, OVERCLIP);
        }
        stepSlideMove(true);
    }

    private float cmdScale() {
        float f = Math.abs(cmd.forward), r = Math.abs(cmd.right);
        float max = Math.max(f, r);
        if (max <= 0f) return 0f;
        float total = (float) Math.sqrt(cmd.forward * cmd.forward + cmd.right * cmd.right);
        if (total < 1e-4f) return 0f;
        float speed = RUN_SPEED * ps.speedScale();
        if (ps.ducked) speed *= DUCK_SCALE;
        return speed * max / total;
    }

    private void friction() {
        float vx = ps.velocity.x, vy = ps.velocity.y, vz = ps.velocity.z;
        float speed = (float) Math.sqrt(vx * vx + vy * vy + (walking ? 0f : vz * vz));
        if (speed < 1f) {
            if (walking) {
                ps.velocity.x = 0;
                ps.velocity.y = 0;
            }
            return;
        }
        float drop = 0f;
        if (walking) {
            boolean slick = (groundTrace.surfaceFlags & Contents.SURF_SLICK) != 0;
            if (!slick) {
                float control = speed < STOP_SPEED ? STOP_SPEED : speed;
                drop += control * FRICTION * frametime;
            }
        }
        float newSpeed = speed - drop;
        if (newSpeed < 0) newSpeed = 0;
        newSpeed /= speed;
        ps.velocity.scale(newSpeed);
    }

    private void accelerate(Vec3 dir, float wishSpeed, float accel) {
        float currentSpeed = ps.velocity.dot(dir);
        float addSpeed = wishSpeed - currentSpeed;
        if (addSpeed <= 0) return;
        float accelSpeed = accel * frametime * wishSpeed;
        if (accelSpeed > addSpeed) accelSpeed = addSpeed;
        ps.velocity.addScaled(dir, accelSpeed);
    }

    // -------------------------------------------------------------- slide move

    /**
     * Moves along the velocity vector, clipping against everything hit and
     * redirecting the remaining motion along the surfaces. Returns true if the
     * move was blocked at some point.
     */
    private boolean slideMove(boolean gravity) {
        ps.mins(mins);
        ps.maxs(maxs);

        int numPlanes = 0;
        endVelocity.zero();

        if (gravity) {
            endVelocity.set(ps.velocity);
            endVelocity.z -= GRAVITY * frametime;
            ps.velocity.z = (ps.velocity.z + endVelocity.z) * 0.5f;
            if (groundPlane) {
                ps.velocity.clipVelocity(ps.groundNormal, OVERCLIP);
            }
        }

        float timeLeft = frametime;

        // Never turn against the ground we are standing on.
        if (groundPlane) {
            planes[numPlanes++].set(ps.groundNormal);
        }
        // ...nor directly against our own direction of travel.
        if (numPlanes < MAX_CLIP_PLANES) {
            planes[numPlanes].set(ps.velocity);
            if (planes[numPlanes].normalize() > 0f) numPlanes++;
        }

        boolean blocked = false;
        for (int bump = 0; bump < 4; bump++) {
            end.setMa(ps.origin, ps.velocity, timeLeft);
            provider.trace(trace, ps.origin, end, mins, maxs, Contents.MASK_PLAYER, ps.index);

            if (trace.allSolid) {
                ps.velocity.z = 0;   // entirely stuck: do not launch into orbit
                return true;
            }
            if (trace.fraction > 0f) {
                ps.origin.set(trace.endPos);
            }
            if (trace.fraction == 1f) break;

            blocked = true;
            timeLeft -= timeLeft * trace.fraction;

            if (numPlanes >= MAX_CLIP_PLANES) {
                ps.velocity.zero();
                return true;
            }

            // Re-hitting a plane we already know about: nudge out along it.
            int i;
            for (i = 0; i < numPlanes; i++) {
                if (trace.normal.dot(planes[i]) > 0.99f) {
                    ps.velocity.addScaled(trace.normal, 1f);
                    break;
                }
            }
            if (i < numPlanes) continue;

            planes[numPlanes++].set(trace.normal);

            // Find a velocity that runs parallel to every plane touched so far.
            for (i = 0; i < numPlanes; i++) {
                if (ps.velocity.dot(planes[i]) >= 0.1f) continue;

                clipVel.set(ps.velocity).clipVelocity(planes[i], OVERCLIP);
                endClipVel.set(endVelocity).clipVelocity(planes[i], OVERCLIP);

                for (int j = 0; j < numPlanes; j++) {
                    if (j == i) continue;
                    if (clipVel.dot(planes[j]) >= 0.1f) continue;

                    clipVel.clipVelocity(planes[j], OVERCLIP);
                    endClipVel.clipVelocity(planes[j], OVERCLIP);
                    if (clipVel.dot(planes[i]) >= 0f) continue;

                    // Slide along the crease where the two planes meet.
                    crease.setCross(planes[i], planes[j]);
                    crease.normalize();
                    float d = crease.dot(ps.velocity);
                    clipVel.set(crease).scale(d);
                    d = crease.dot(endVelocity);
                    endClipVel.set(crease).scale(d);

                    // A third plane in the way means there is nowhere left to go.
                    for (int k = 0; k < numPlanes; k++) {
                        if (k == i || k == j) continue;
                        if (clipVel.dot(planes[k]) >= 0.1f) continue;
                        ps.velocity.zero();
                        return true;
                    }
                }

                ps.velocity.set(clipVel);
                endVelocity.set(endClipVel);
                break;
            }
        }

        if (gravity) {
            ps.velocity.set(endVelocity);
        }
        return blocked;
    }

    /** {@link #slideMove} plus the 18-unit step up that makes stairs walkable. */
    private void stepSlideMove(boolean gravity) {
        startO.set(ps.origin);
        startV.set(ps.velocity);

        if (!slideMove(gravity)) return;

        ps.mins(mins);
        ps.maxs(maxs);

        downPos.set(startO.x, startO.y, startO.z - STEP_SIZE);
        provider.trace(trace, startO, downPos, mins, maxs, Contents.MASK_PLAYER, ps.index);
        // Never step up while still moving upwards, unless there is floor below.
        if (ps.velocity.z > 0 && (trace.fraction == 1f || trace.normal.z < MIN_WALK_NORMAL)) {
            return;
        }

        upPos.set(startO.x, startO.y, startO.z + STEP_SIZE);
        provider.trace(trace, startO, upPos, mins, maxs, Contents.MASK_PLAYER, ps.index);
        if (trace.allSolid) return;

        float stepSize = trace.endPos.z - startO.z;
        ps.origin.set(trace.endPos);
        ps.velocity.set(startV);

        slideMove(gravity);

        // Settle back down onto the step.
        downPos.set(ps.origin.x, ps.origin.y, ps.origin.z - stepSize);
        provider.trace(trace, ps.origin, downPos, mins, maxs, Contents.MASK_PLAYER, ps.index);
        if (!trace.allSolid) {
            ps.origin.set(trace.endPos);
        }
        if (trace.fraction < 1f) {
            ps.velocity.clipVelocity(trace.normal, OVERCLIP);
        }
    }

    /**
     * Fall damage from a landing impact, using the same curve as Quake: nothing
     * below roughly 600 u/s, ten points at terminal speeds.
     */
    public static int fallDamage(float impactSpeed) {
        float delta = impactSpeed * impactSpeed * 0.0001f;
        if (delta > 60f) return 10;
        if (delta > 40f) return 5;
        return 0;
    }
}
