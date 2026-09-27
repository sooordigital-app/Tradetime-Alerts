package com.tradetime.alerts;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * AlarmSoundPlugin
 * -----------------
 * Exposes two things to the web app (via window.Capacitor.Plugins.AlarmSound):
 *
 *   1. pickRingtone() -> opens Android's own tone picker, the same list of
 *      Alarm / Ringtone / Notification tones you see in the phone's Settings
 *      app. Returns the chosen tone's URI + display title.
 *
 *   2. playSound({ uri }) -> plays that tone immediately, on the ALARM
 *      audio stream (so it respects the phone's alarm volume, not media
 *      volume). Used whenever an EMA / candle alert fires.
 *
 * This plugin file must be copied into the generated Android project at:
 *   android/app/src/main/java/com/tradetime/alerts/AlarmSoundPlugin.java
 * and registered in MainActivity.java (see MainActivity.java in this folder).
 */
@CapacitorPlugin(name = "AlarmSound")
public class AlarmSoundPlugin extends Plugin {

    private MediaPlayer mediaPlayer;

    @PluginMethod
    public void pickRingtone(PluginCall call) {
        saveCall(call);

        int type = RingtoneManager.TYPE_ALARM | RingtoneManager.TYPE_NOTIFICATION | RingtoneManager.TYPE_RINGTONE;

        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);

        startActivityForResult(call, intent, "ringtonePickerResult");
    }

    @ActivityCallback
    private void ringtonePickerResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }

        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("No tone selected");
            return;
        }

        Uri uri = result.getData().getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
        if (uri == null) {
            call.reject("No tone selected");
            return;
        }

        String title = "Selected tone";
        try {
            Ringtone ringtone = RingtoneManager.getRingtone(getContext(), uri);
            if (ringtone != null) {
                title = ringtone.getTitle(getContext());
            }
        } catch (Exception ignored) {
            // Keep the generic title if the OS can't resolve a display name.
        }

        JSObject ret = new JSObject();
        ret.put("uri", uri.toString());
        ret.put("title", title);
        call.resolve(ret);
    }

    @PluginMethod
    public void playSound(PluginCall call) {
        String uriString = call.getString("uri");
        if (uriString == null) {
            call.reject("uri is required");
            return;
        }

        try {
            stopInternal();
            Uri uri = Uri.parse(uriString);

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(getContext(), uri);
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
            mediaPlayer.setOnPreparedListener(MediaPlayer::start);
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                stopInternal();
                return true;
            });
            mediaPlayer.prepareAsync();

            call.resolve();
        } catch (Exception e) {
            call.reject("Could not play tone: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopInternal();
        call.resolve();
    }

    private void stopInternal() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Exception ignored) {
                // Player was already in a bad state — nothing more to clean up.
            }
            mediaPlayer = null;
        }
    }
}
