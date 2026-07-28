package com.arena3.ui;

import android.app.Activity;
import android.opengl.GLSurfaceView;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.arena3.R;
import com.arena3.audio.SoundEngine;
import com.arena3.game.GameConfig;
import com.arena3.game.GameWorld;
import com.arena3.game.MapDef;
import com.arena3.game.Maps;
import com.arena3.game.PlayerState;
import com.arena3.gl.GameRenderer;

/** Hosts the match: GL surface, controls, pause overlay and the audio engine. */
public final class GameActivity extends Activity implements GameRenderer.Listener {

    public static final String EXTRA_MAP = "map";
    public static final String EXTRA_BOTS = "bots";
    public static final String EXTRA_SKILL = "skill";
    public static final String EXTRA_FRAGS = "frags";
    public static final String EXTRA_MINUTES = "minutes";

    private GameView view;
    private GameRenderer renderer;
    private TouchControls controls;
    private SoundEngine sound;
    private Settings settings;
    private GameWorld world;
    private View pauseOverlay;
    private View loadingView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        goFullscreen();

        settings = new Settings(this);

        GameConfig config = new GameConfig();
        Intent intent = getIntent();
        config.mapIndex = intent.getIntExtra(EXTRA_MAP, settings.mapIndex());
        config.botCount = intent.getIntExtra(EXTRA_BOTS, settings.botCount());
        config.skill = intent.getIntExtra(EXTRA_SKILL, settings.skill());
        config.fragLimit = intent.getIntExtra(EXTRA_FRAGS, settings.fragLimit());
        config.timeLimitSeconds = intent.getIntExtra(EXTRA_MINUTES, settings.timeLimitMinutes()) * 60f;
        config.autoSwitchWeapons = settings.autoSwitch();
        config.seed = System.nanoTime();

        MapDef map = Maps.build(config.mapIndex);
        world = new GameWorld(map, config);

        controls = new TouchControls();
        applyControlSettings();

        sound = new SoundEngine();
        sound.start(settings.volume());

        renderer = new GameRenderer(world, settings, controls, sound, this);

        view = new GameView(this);
        view.setControls(controls);
        view.setRenderer(renderer);
        view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        FrameLayout root = new FrameLayout(this);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Textures and level lighting are generated on the render thread, which
        // takes a moment; cover it rather than showing a black screen.
        loadingView = buildLoadingView(map.name);
        root.addView(loadingView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private View buildLoadingView(String mapName) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xFF08090C);

        TextView title = new TextView(this);
        title.setText(mapName);
        title.setTextColor(0xFFD8DEE9);
        title.setTextSize(28f);
        title.setLetterSpacing(0.25f);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("BUILDING ARENA");
        sub.setTextColor(0xFFE8A33D);
        sub.setTextSize(12f);
        sub.setLetterSpacing(0.4f);
        sub.setGravity(Gravity.CENTER);
        box.addView(sub);
        return box;
    }

    @Override
    public void onReady() {
        runOnUiThread(() -> {
            if (loadingView == null) return;
            loadingView.animate().alpha(0f).setDuration(220).withEndAction(() -> {
                if (loadingView != null && loadingView.getParent() != null) {
                    ((ViewGroup) loadingView.getParent()).removeView(loadingView);
                }
                loadingView = null;
            }).start();
        });
    }

    private void applyControlSettings() {
        controls.sensitivity = 0.16f;
        controls.scale = settings.controlScale();
        controls.invertY = settings.invertY();
        controls.southpaw = settings.southpaw();
        controls.showZones = settings.showZones();
    }

    private void goFullscreen() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goFullscreen();
    }

    // ---------------------------------------------------------------- pausing

    @Override
    public void onPauseRequested() {
        runOnUiThread(this::showPause);
    }

    @Override
    public void onMatchEnded(GameWorld finished) {
        // The scoreboard is drawn by the HUD; offer the exits after a beat.
        runOnUiThread(() -> view.postDelayed(this::showPause, 2600));
    }

    private void showPause() {
        if (pauseOverlay != null) return;
        renderer.setPaused(true);

        pauseOverlay = getLayoutInflater().inflate(R.layout.overlay_pause, null);
        TextView title = pauseOverlay.findViewById(R.id.pause_title);
        TextView sub = pauseOverlay.findViewById(R.id.pause_sub);
        if (world.state == GameWorld.STATE_OVER) {
            PlayerState winner = world.players[Math.max(0, world.winner)];
            title.setText(world.winner == 0 ? "VICTORY" : "DEFEAT");
            sub.setText(winner.name + " won with " + winner.frags + " frags");
        } else {
            title.setText("PAUSED");
            sub.setText(world.map.name);
        }

        Button resume = pauseOverlay.findViewById(R.id.btn_resume);
        resume.setText(world.state == GameWorld.STATE_OVER ? R.string.restart : R.string.resume);
        resume.setOnClickListener(v -> {
            if (world.state == GameWorld.STATE_OVER) {
                restartMatch();
            } else {
                hidePause();
            }
        });
        pauseOverlay.findViewById(R.id.btn_restart).setOnClickListener(v -> restartMatch());
        pauseOverlay.findViewById(R.id.btn_leave).setOnClickListener(v -> finish());

        ((FrameLayout) findViewById(android.R.id.content)).addView(pauseOverlay,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void hidePause() {
        if (pauseOverlay == null) return;
        ((ViewGroup) pauseOverlay.getParent()).removeView(pauseOverlay);
        pauseOverlay = null;
        controls.releaseAll();
        renderer.setPaused(false);
        goFullscreen();
    }

    private void restartMatch() {
        Intent intent = new Intent(this, GameActivity.class);
        intent.putExtras(getIntent());
        finish();
        startActivity(intent);
    }

    @Override
    public void onBackPressed() {
        if (pauseOverlay != null) {
            if (world.state == GameWorld.STATE_OVER) {
                finish();
            } else {
                hidePause();
            }
        } else {
            showPause();
        }
    }

    // -------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        view.onResume();
        goFullscreen();
        if (sound != null) sound.setVolume(settings.volume());
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (renderer != null) renderer.setPaused(true);
        if (controls != null) controls.releaseAll();
        view.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sound != null) sound.stop();
    }

    // --------------------------------------------------------- gamepad support

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_START || keyCode == KeyEvent.KEYCODE_MENU) {
            showPause();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
