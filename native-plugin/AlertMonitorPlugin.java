package com.tradetime.alerts;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;

/**
 * AlertMonitor
 * ------------
 * JS-facing bridge for the native AlertMonitorService. Exposed to the web
 * app as window.Capacitor.Plugins.AlertMonitor.
 *
 *   sync({ alerts })        - save the current alert list + (re)start or
 *                              stop the background service as needed.
 *   setAlarmTone({ uri })   - save the picked tone so the native service
 *                              can play it too.
 *   getPendingSignals()     - returns + clears any signals the native
 *                              service fired while the app was backgrounded.
 *   stop()                  - stop the background service entirely.
 */
@CapacitorPlugin(name = "AlertMonitor")
public class AlertMonitorPlugin extends Plugin {

    @PluginMethod
    public void sync(PluginCall call) {
        JSArray alerts = call.getArray("alerts");
        if (alerts == null) {
            call.reject("alerts array required");
            return;
        }

        SharedPreferences prefs = getContext().getSharedPreferences(AlertMonitorService.PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString("alerts_json", alerts.toString()).apply();

        boolean hasActive = false;
        try {
            JSONArray arr = new JSONArray(alerts.toString());
            for (int i = 0; i < arr.length(); i++) {
                if (arr.getJSONObject(i).optBoolean("active", true)) {
                    hasActive = true;
                    break;
                }
            }
        } catch (Exception ignored) {
            // Malformed payload — treat as "nothing to monitor".
        }

        if (hasActive) {
            startService();
        } else {
            stopService();
        }
        call.resolve();
    }

    @PluginMethod
    public void setAlarmTone(PluginCall call) {
        String uri = call.getString("uri");
        if (uri == null) {
            call.reject("uri required");
            return;
        }
        SharedPreferences prefs = getContext().getSharedPreferences(AlertMonitorService.PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString("alarm_tone_uri", uri).apply();
        call.resolve();
    }

    @PluginMethod
    public void getPendingSignals(PluginCall call) {
        SharedPreferences prefs = getContext().getSharedPreferences(AlertMonitorService.PREFS, Context.MODE_PRIVATE);
        String pending = prefs.getString("pending_signals_json", "[]");
        prefs.edit().remove("pending_signals_json").apply();

        JSObject ret = new JSObject();
        try {
            ret.put("signals", new JSONArray(pending));
        } catch (Exception e) {
            ret.put("signals", new JSONArray());
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopService();
        call.resolve();
    }

    private void startService() {
        Intent intent = new Intent(getContext(), AlertMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(intent);
        } else {
            getContext().startService(intent);
        }
    }

    private void stopService() {
        Intent intent = new Intent(getContext(), AlertMonitorService.class);
        getContext().stopService(intent);
    }
}
