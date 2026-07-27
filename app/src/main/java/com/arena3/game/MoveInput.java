package com.arena3.game;

/** One frame of intent from a human or a bot. */
public final class MoveInput {

    /** -1..1, positive is forwards. */
    public float forward;
    /** -1..1, positive is to the right. */
    public float right;
    public boolean jump;
    public boolean crouch;
    public boolean fire;
    /** Weapon the controller wants to hold, or -1 to keep the current one. */
    public int selectWeapon = -1;
    /** Delta applied to the view this frame, in degrees. */
    public float deltaYaw;
    public float deltaPitch;

    public void clear() {
        forward = 0;
        right = 0;
        jump = false;
        crouch = false;
        fire = false;
        selectWeapon = -1;
        deltaYaw = 0;
        deltaPitch = 0;
    }
}
