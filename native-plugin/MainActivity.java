package com.tradetime.alerts;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

/**
 * `npx cap add android` generates its own MainActivity.java at:
 *   android/app/src/main/java/com/tradetime/alerts/MainActivity.java
 *
 * Replace that generated file's contents with this one (or just add the
 * registerPlugin(...) line + onCreate override if you've customized it
 * already). Registering must happen BEFORE super.onCreate().
 */
public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AlarmSoundPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
