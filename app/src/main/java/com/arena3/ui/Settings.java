package com.arena3.ui;

import android.content.Context;
import android.content.SharedPreferences;

/** Player preferences, backed by SharedPreferences. */
public final class Settings {

    private static final String FILE = "arena3";

    private final SharedPreferences prefs;

    public Settings(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---- look and feel ----

    /** Multiplier applied to raw touch deltas. */
    public float sensitivity() {
        return 0.35f + prefs.getInt("sens", 45) / 100f * 1.6f;
    }

    public int sensitivityRaw() {
        return prefs.getInt("sens", 45);
    }

    public void setSensitivityRaw(int v) {
        prefs.edit().putInt("sens", v).apply();
    }

    /** Horizontal field of view in degrees. */
    public float fov() {
        return 75f + prefs.getInt("fov", 25);
    }

    public int fovRaw() {
        return prefs.getInt("fov", 25);
    }

    public void setFovRaw(int v) {
        prefs.edit().putInt("fov", v).apply();
    }

    public float controlScale() {
        return 0.75f + prefs.getInt("scale", 50) / 100f * 0.6f;
    }

    public int controlScaleRaw() {
        return prefs.getInt("scale", 50);
    }

    public void setControlScaleRaw(int v) {
        prefs.edit().putInt("scale", v).apply();
    }

    public float volume() {
        return prefs.getInt("vol", 80) / 100f;
    }

    public int volumeRaw() {
        return prefs.getInt("vol", 80);
    }

    public void setVolumeRaw(int v) {
        prefs.edit().putInt("vol", v).apply();
    }

    // ---- toggles ----

    public boolean invertY() {
        return prefs.getBoolean("invert", false);
    }

    public void setInvertY(boolean v) {
        prefs.edit().putBoolean("invert", v).apply();
    }

    public boolean autoSwitch() {
        return prefs.getBoolean("autoswitch", true);
    }

    public void setAutoSwitch(boolean v) {
        prefs.edit().putBoolean("autoswitch", v).apply();
    }

    public boolean southpaw() {
        return prefs.getBoolean("southpaw", false);
    }

    public void setSouthpaw(boolean v) {
        prefs.edit().putBoolean("southpaw", v).apply();
    }

    public boolean gore() {
        return prefs.getBoolean("gore", true);
    }

    public void setGore(boolean v) {
        prefs.edit().putBoolean("gore", v).apply();
    }

    public boolean showFps() {
        return prefs.getBoolean("fps", false);
    }

    public void setShowFps(boolean v) {
        prefs.edit().putBoolean("fps", v).apply();
    }

    public boolean haptics() {
        return prefs.getBoolean("haptics", true);
    }

    public void setHaptics(boolean v) {
        prefs.edit().putBoolean("haptics", v).apply();
    }

    public boolean showZones() {
        return prefs.getBoolean("zones", false);
    }

    public void setShowZones(boolean v) {
        prefs.edit().putBoolean("zones", v).apply();
    }

    // ---- last match setup ----

    public int mapIndex() {
        return prefs.getInt("map", 0);
    }

    public void setMapIndex(int v) {
        prefs.edit().putInt("map", v).apply();
    }

    public int botCount() {
        return prefs.getInt("bots", 3);
    }

    public void setBotCount(int v) {
        prefs.edit().putInt("bots", v).apply();
    }

    public int skill() {
        return prefs.getInt("skill", 2);
    }

    public void setSkill(int v) {
        prefs.edit().putInt("skill", v).apply();
    }

    public int fragLimit() {
        return prefs.getInt("frags", 15);
    }

    public void setFragLimit(int v) {
        prefs.edit().putInt("frags", v).apply();
    }

    public int timeLimitMinutes() {
        return prefs.getInt("time", 10);
    }

    public void setTimeLimitMinutes(int v) {
        prefs.edit().putInt("time", v).apply();
    }
}
