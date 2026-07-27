package com.arena3.game;

/** Match setup chosen in the menu. */
public final class GameConfig {

    public int mapIndex = 0;
    public int botCount = 3;
    /** 0..4 — from "can barely aim" to "reads your movement". */
    public int skill = 2;
    public int fragLimit = 15;
    public float timeLimitSeconds = 10 * 60;
    public String playerName = "Player";
    public boolean autoSwitchWeapons = true;
    /** Extra seed so a rematch on the same map does not replay identically. */
    public long seed = System.nanoTime();

    public GameConfig copy() {
        GameConfig c = new GameConfig();
        c.mapIndex = mapIndex;
        c.botCount = botCount;
        c.skill = skill;
        c.fragLimit = fragLimit;
        c.timeLimitSeconds = timeLimitSeconds;
        c.playerName = playerName;
        c.autoSwitchWeapons = autoSwitchWeapons;
        c.seed = seed;
        return c;
    }
}
