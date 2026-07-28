package com.arena3.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.arena3.R;

/** Preferences screen. Everything writes straight through to {@link Settings}. */
public final class SettingsActivity extends Activity {

    private Settings settings;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        settings = new Settings(this);

        slider(R.id.sb_sens, R.id.lbl_sens, R.string.look_sensitivity, settings.sensitivityRaw(),
                v -> settings.setSensitivityRaw(v), v -> String.valueOf(v));
        slider(R.id.sb_fov, R.id.lbl_fov, R.string.field_of_view, settings.fovRaw(),
                v -> settings.setFovRaw(v), v -> String.valueOf(75 + v));
        slider(R.id.sb_scale, R.id.lbl_scale, R.string.control_scale, settings.controlScaleRaw(),
                v -> settings.setControlScaleRaw(v), v -> String.valueOf(v));
        slider(R.id.sb_vol, R.id.lbl_vol, R.string.sound, settings.volumeRaw(),
                v -> settings.setVolumeRaw(v), v -> v + "%");

        toggle(R.id.sw_invert, settings.invertY(), settings::setInvertY);
        toggle(R.id.sw_autoswitch, settings.autoSwitch(), settings::setAutoSwitch);
        toggle(R.id.sw_southpaw, settings.southpaw(), settings::setSouthpaw);
        toggle(R.id.sw_gore, settings.gore(), settings::setGore);
        toggle(R.id.sw_fps, settings.showFps(), settings::setShowFps);
        toggle(R.id.sw_haptics, settings.haptics(), settings::setHaptics);
        toggle(R.id.sw_zones, settings.showZones(), settings::setShowZones);
        toggle(R.id.sw_enhanced, settings.enhancedLighting(), settings::setEnhancedLighting);
        toggle(R.id.sw_sharpshadows, settings.sharpShadows(), settings::setSharpShadows);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
    }

    private interface IntSetter {
        void set(int value);
    }

    private interface IntFormatter {
        String format(int value);
    }

    private interface BoolSetter {
        void set(boolean value);
    }

    private void slider(int barId, int labelId, int titleRes, int initial,
                        IntSetter setter, IntFormatter formatter) {
        SeekBar bar = findViewById(barId);
        TextView label = findViewById(labelId);
        bar.setProgress(initial);
        label.setText(getString(titleRes) + "   " + formatter.format(initial));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                label.setText(getString(titleRes) + "   " + formatter.format(progress));
                setter.set(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    private void toggle(int id, boolean initial, BoolSetter setter) {
        Switch view = findViewById(id);
        view.setChecked(initial);
        view.setOnCheckedChangeListener((button, checked) -> setter.set(checked));
    }
}
