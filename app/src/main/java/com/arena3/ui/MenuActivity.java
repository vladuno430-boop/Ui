package com.arena3.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.arena3.R;
import com.arena3.game.Maps;

/** Front end: arena picker, match settings, and the way into a game. */
public final class MenuActivity extends Activity {

    private static final String[] SKILL_NAMES = {"RECRUIT", "REGULAR", "VETERAN", "ELITE", "NIGHTMARE"};
    private static final int[] FRAG_OPTIONS = {5, 10, 15, 20, 25, 30, 40, 50, 75, 100, 0};
    private static final int[] TIME_OPTIONS = {2, 5, 8, 10, 15, 20, 0};

    private Settings settings;
    private final View[] mapCards = new View[Maps.COUNT];
    private int selectedMap;

    private SeekBar bots, skill, frags, time;
    private TextView botsLabel, skillLabel, fragsLabel, timeLabel, mapDesc;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_menu);
        settings = new Settings(this);
        selectedMap = Math.min(Maps.COUNT - 1, settings.mapIndex());

        buildMapCards();

        bots = findViewById(R.id.sb_bots);
        skill = findViewById(R.id.sb_skill);
        frags = findViewById(R.id.sb_frags);
        time = findViewById(R.id.sb_time);
        botsLabel = findViewById(R.id.lbl_bots);
        skillLabel = findViewById(R.id.lbl_skill);
        fragsLabel = findViewById(R.id.lbl_frags);
        timeLabel = findViewById(R.id.lbl_time);
        mapDesc = findViewById(R.id.map_desc);

        bots.setProgress(clamp(settings.botCount() - 1, 0, bots.getMax()));
        skill.setProgress(clamp(settings.skill(), 0, skill.getMax()));
        frags.setProgress(indexOf(FRAG_OPTIONS, settings.fragLimit(), 2));
        time.setProgress(indexOf(TIME_OPTIONS, settings.timeLimitMinutes(), 3));

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                updateLabels();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        };
        bots.setOnSeekBarChangeListener(listener);
        skill.setOnSeekBarChangeListener(listener);
        frags.setOnSeekBarChangeListener(listener);
        time.setOnSeekBarChangeListener(listener);
        updateLabels();

        Button fight = findViewById(R.id.btn_fight);
        fight.setOnClickListener(v -> startMatch());
        findViewById(R.id.btn_settings).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int indexOf(int[] options, int value, int fallback) {
        for (int i = 0; i < options.length; i++) {
            if (options[i] == value) return i;
        }
        return fallback;
    }

    private void buildMapCards() {
        LinearLayout row = findViewById(R.id.map_row);
        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < Maps.COUNT; i++) {
            View card = inflater.inflate(R.layout.item_map_card, row, false);
            ((TextView) card.findViewById(R.id.name)).setText(Maps.nameOf(i));
            ((TextView) card.findViewById(R.id.sub)).setText(Maps.subtitleOf(i));
            ((MapThumbView) card.findViewById(R.id.thumb)).setMapIndex(i);
            final int index = i;
            card.setOnClickListener(v -> selectMap(index));
            row.addView(card);
            mapCards[i] = card;
        }
        selectMap(selectedMap);
    }

    private void selectMap(int index) {
        selectedMap = index;
        for (int i = 0; i < mapCards.length; i++) {
            mapCards[i].setSelected(i == index);
        }
        if (mapDesc != null) mapDesc.setText(Maps.descriptionOf(index));
    }

    private void updateLabels() {
        int botCount = bots.getProgress() + 1;
        botsLabel.setText(getString(R.string.opponents) + "   " + botCount);
        skillLabel.setText(getString(R.string.skill) + "   " + SKILL_NAMES[skill.getProgress()]);

        int fragLimit = FRAG_OPTIONS[frags.getProgress()];
        fragsLabel.setText(getString(R.string.frag_limit) + "   "
                + (fragLimit == 0 ? "NONE" : String.valueOf(fragLimit)));

        int minutes = TIME_OPTIONS[time.getProgress()];
        timeLabel.setText(getString(R.string.time_limit) + "   "
                + (minutes == 0 ? "NONE" : minutes + " MIN"));
    }

    private void startMatch() {
        int botCount = bots.getProgress() + 1;
        int skillLevel = skill.getProgress();
        int fragLimit = FRAG_OPTIONS[frags.getProgress()];
        int minutes = TIME_OPTIONS[time.getProgress()];

        settings.setMapIndex(selectedMap);
        settings.setBotCount(botCount);
        settings.setSkill(skillLevel);
        settings.setFragLimit(fragLimit);
        settings.setTimeLimitMinutes(minutes);

        Intent intent = new Intent(this, GameActivity.class);
        intent.putExtra(GameActivity.EXTRA_MAP, selectedMap);
        intent.putExtra(GameActivity.EXTRA_BOTS, botCount);
        intent.putExtra(GameActivity.EXTRA_SKILL, skillLevel);
        intent.putExtra(GameActivity.EXTRA_FRAGS, fragLimit);
        intent.putExtra(GameActivity.EXTRA_MINUTES, minutes);
        startActivity(intent);
    }
}
