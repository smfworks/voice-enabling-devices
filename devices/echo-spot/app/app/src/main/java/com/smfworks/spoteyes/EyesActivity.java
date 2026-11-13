package com.smfworks.spoteyes;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

public final class EyesActivity extends Activity {
    public static final String PREVIEW = "com.smfworks.spoteyes.PREVIEW";

    private EyesView eyes;
    private VoiceLoop voice;
    private PowerManager.WakeLock wake;
    private final BroadcastReceiver preview = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra("state");
            if (state == null || voice == null) {
                return;
            }
            voice.preview(state);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        hideSystemUi();
        eyes = new EyesView(this);
        final SharedPreferences prefs = getSharedPreferences("spoteyes", MODE_PRIVATE);
        int start = Bot.indexOf(prefs.getString("selected_bot", "charlie"));
        eyes.setIndex(start);
        setContentView(eyes);
        holdWake();
        stayAwake();
        voice = new VoiceLoop(this, eyes);
        voice.setBot(Bot.at(start));
        eyes.setListener(new EyesView.Listener() {
            @Override
            public void onTap() {
                if (voice != null) {
                    voice.onTap();
                }
            }

            @Override
            public void onBotChanged(Bot bot) {
                prefs.edit().putString("selected_bot", bot.id).apply();
                if (voice != null) {
                    voice.setBot(bot);
                }
            }

            @Override
            public boolean isSwipeLocked() {
                return voice != null && voice.isRealtimeOpen();
            }
        });
        voice.start();
        registerReceiver(preview, new IntentFilter(PREVIEW));
    }

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void holdWake() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spoteyes:voice");
        wake.setReferenceCounted(false);
        wake.acquire();
    }

    private void stayAwake() {
        try {
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, Integer.MAX_VALUE);
        } catch (SecurityException ignored) {
            // The shell sets this when the app cannot.
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(preview);
        if (voice != null) {
            voice.shutdown();
        }
        if (wake != null && wake.isHeld()) {
            wake.release();
        }
        super.onDestroy();
    }
}
