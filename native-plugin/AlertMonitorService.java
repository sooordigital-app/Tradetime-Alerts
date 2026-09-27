package com.tradetime.alerts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * AlertMonitorService
 * --------------------
 * Runs as a foreground Service so Android doesn't freeze/kill it when the
 * screen is off or another app is in front. It re-implements the same
 * EMA-crossover and candle-cross-both-EMA logic that lives in www/index.html,
 * but in plain Java, so it works independently of the WebView (which Chrome
 * throttles heavily once it's not visible).
 *
 * Data flow:
 *   - JS calls AlertMonitor.sync({alerts}) whenever the alert list changes.
 *     That gets written to SharedPreferences and this Service reads it.
 *   - JS calls AlertMonitor.setAlarmTone({uri}) whenever the user picks a
 *     tone, also stored in SharedPreferences.
 *   - When this Service detects a crossover, it (a) shows a notification,
 *     (b) plays the saved tone, and (c) appends the signal to a
 *     "pending_signals_json" list in SharedPreferences so the JS side can
 *     pick it up (via AlertMonitor.getPendingSignals()) next time the app
 *     is opened, and show it in the on-screen history.
 */
public class AlertMonitorService extends Service {

    private static final String TAG = "AlertMonitorService";
    static final String PREFS = "tradetime_alerts_prefs";
    private static final String CHANNEL_PERSISTENT = "tradetime_monitor";
    private static final String CHANNEL_ALERTS = "tradetime_alerts";
    private static final int NOTIF_PERSISTENT_ID = 1000;

    // How often the loop wakes up to see if any alert is due for a check.
    // Individual alerts are only actually re-checked once their own
    // timeframe-based interval (see intervalForTimeframe) has elapsed.
    private static final long TICK_MS = 15000;

    private Handler handler;
    private Runnable loop;
    private MediaPlayer mediaPlayer;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_PERSISTENT_ID, buildPersistentNotification());
        startLoop();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null && loop != null) handler.removeCallbacks(loop);
        stopTone();
    }

    private void startLoop() {
        if (loop != null) return;
        loop = new Runnable() {
            @Override
            public void run() {
                new Thread(() -> {
                    try {
                        checkAllAlerts();
                    } catch (Exception e) {
                        Log.e(TAG, "check loop failed", e);
                    }
                }).start();
                handler.postDelayed(this, TICK_MS);
            }
        };
        handler.post(loop);
    }

    private void checkAllAlerts() throws Exception {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray alerts = new JSONArray(prefs.getString("alerts_json", "[]"));
        long now = System.currentTimeMillis();

        // Nothing to watch — stop ourselves so we don't sit in the
        // background doing nothing (JS restarts us via AlertMonitor.sync()
        // as soon as there's an active alert again).
        boolean anyActive = false;
        for (int i = 0; i < alerts.length(); i++) {
            if (alerts.getJSONObject(i).optBoolean("active", true)) {
                anyActive = true;
                break;
            }
        }
        if (!anyActive) {
            stopSelf();
            return;
        }

        for (int i = 0; i < alerts.length(); i++) {
            JSONObject a = alerts.getJSONObject(i);
            if (!a.optBoolean("active", true)) continue;

            String id = a.optString("id", String.valueOf(i));
            String timeframe = a.optString("timeframe", "5m");
            long intervalMs = intervalForTimeframe(timeframe);
            long lastChecked = prefs.getLong("lastChecked_" + id, 0);
            if (now - lastChecked < intervalMs) continue;

            try {
                checkOneAlert(a, id, prefs);
            } catch (Exception e) {
                Log.e(TAG, "checkOneAlert failed for " + id, e);
            }
            prefs.edit().putLong("lastChecked_" + id, now).apply();
        }
    }

    private long intervalForTimeframe(String tf) {
        switch (tf) {
            case "1m": return 10000;
            case "3m":
            case "5m": return 30000;
            case "15m": return 60000;
            case "30m": return 120000;
            case "1h": return 300000;
            case "2h": return 600000;
            case "4h": return 900000;
            case "6h": return 1200000;
            case "1d": return 3600000;
            default: return 60000;
        }
    }

    private void checkOneAlert(JSONObject a, String id, SharedPreferences prefs) throws Exception {
        String symbol = a.getString("symbol");
        String marketType = a.optString("marketType", "futures");
        String timeframe = a.getString("timeframe");
        int ema1Period = a.getInt("ema1");
        int ema2Period = a.getInt("ema2");
        boolean sound = a.optBoolean("enableSound", true);
        boolean popup = a.optBoolean("enablePopup", true);

        String endpoint = "futures".equals(marketType)
            ? "https://fapi.binance.com/fapi/v1/klines"
            : "https://api.binance.com/api/v3/klines";
        int limit = Math.max(ema1Period, ema2Period) + 50;
        String urlStr = endpoint + "?symbol=" + symbol + "&interval=" + timeframe + "&limit=" + limit;

        JSONArray klines = fetchJsonArray(urlStr);
        if (klines == null || klines.length() == 0) return;

        double[] closes = new double[klines.length()];
        for (int i = 0; i < klines.length(); i++) {
            closes[i] = Double.parseDouble(klines.getJSONArray(i).getString(4));
        }
        double price = closes[closes.length - 1];

        Double ema1 = calculateEMA(closes, ema1Period);
        Double ema2 = calculateEMA(closes, ema2Period);
        if (ema1 == null || ema2 == null) return;

        detectCrossover(prefs, id, symbol, timeframe, ema1, ema2, price, sound, popup);
        detectPriceCrossBothEma(prefs, id, symbol, timeframe, ema1, ema2, price, sound, popup);
    }

    private JSONArray fetchJsonArray(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        } finally {
            conn.disconnect();
        }
        return new JSONArray(sb.toString());
    }

    // Same SMA-seeded EMA formula as calculateEMA() in www/index.html.
    private Double calculateEMA(double[] prices, int period) {
        if (prices.length < period) return null;
        double ema = 0;
        double multiplier = 2.0 / (period + 1);
        for (int i = 0; i < period; i++) ema += prices[i];
        ema /= period;
        for (int i = period; i < prices.length; i++) {
            ema = (prices[i] - ema) * multiplier + ema;
        }
        return ema;
    }

    // Mirrors detectCrossover() in www/index.html.
    private void detectCrossover(SharedPreferences prefs, String id, String symbol, String timeframe,
                                  double ema1, double ema2, double price, boolean sound, boolean popup) {
        boolean fastAboveSlowNow = ema1 > ema2;
        String key = "state_" + id;
        String lastState = prefs.getString(key, null);
        if (lastState != null) {
            boolean lastFastAboveSlow = "bullish".equals(lastState);
            if (fastAboveSlowNow != lastFastAboveSlow) {
                fireSignal(prefs, symbol, timeframe, fastAboveSlowNow ? "bullish" : "bearish",
                    price, ema1, ema2, "ema-cross", sound, popup);
            }
        }
        prefs.edit().putString(key, fastAboveSlowNow ? "bullish" : "bearish").apply();
    }

    // Mirrors detectPriceCrossBothEma() in www/index.html.
    private void detectPriceCrossBothEma(SharedPreferences prefs, String id, String symbol, String timeframe,
                                          double ema1, double ema2, double price, boolean sound, boolean popup) {
        String currentSide = null;
        if (price > ema1 && price > ema2) currentSide = "above";
        else if (price < ema1 && price < ema2) currentSide = "below";
        if (currentSide == null) return;

        String key = "price_state_" + id;
        String lastSide = prefs.getString(key, null);
        if (lastSide != null && !lastSide.equals(currentSide)) {
            fireSignal(prefs, symbol, timeframe, "above".equals(currentSide) ? "bullish" : "bearish",
                price, ema1, ema2, "price-cross", sound, popup);
        }
        prefs.edit().putString(key, currentSide).apply();
    }

    private void fireSignal(SharedPreferences prefs, String symbol, String timeframe, String signal,
                             double price, double ema1, double ema2, String type, boolean sound, boolean popup) {
        try {
            JSONArray pending = new JSONArray(prefs.getString("pending_signals_json", "[]"));
            JSONObject entry = new JSONObject();
            entry.put("symbol", symbol);
            entry.put("timeframe", timeframe);
            entry.put("signal", signal);
            entry.put("price", price);
            entry.put("ema1", ema1);
            entry.put("ema2", ema2);
            entry.put("type", type);
            entry.put("time", System.currentTimeMillis());
            pending.put(entry);
            while (pending.length() > 50) pending.remove(0);
            prefs.edit().putString("pending_signals_json", pending.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "failed to store pending signal", e);
        }

        if (popup) showAlertNotification(symbol, timeframe, signal, type, price);
        if (sound) playTone(prefs);
    }

    private void showAlertNotification(String symbol, String timeframe, String signal, String type, double price) {
        String label = "bullish".equals(signal) ? "\uD83D\uDCC8 BULLISH" : "\uD83D\uDCC9 BEARISH";
        String typeSuffix = "price-cross".equals(type) ? " (Candle x Both EMAs)" : "";
        String title = label + typeSuffix + " - " + symbol;
        String text = timeframe.toUpperCase() + " @ " + price;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true);

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launchIntent != null) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(this, 0, launchIntent, flags);
            builder.setContentIntent(pi);
        }

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify((int) System.currentTimeMillis(), builder.build());
        }
    }

    private void playTone(SharedPreferences prefs) {
        String uriString = prefs.getString("alarm_tone_uri", null);
        if (uriString == null) return; // no custom tone picked yet

        try {
            stopTone();
            Uri uri = Uri.parse(uriString);
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
            mediaPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mediaPlayer.setOnPreparedListener(MediaPlayer::start);
            mediaPlayer.setOnCompletionListener(mp -> stopTone());
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                stopTone();
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "tone playback failed", e);
        }
    }

    private void stopTone() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {
                // Player was already in a bad state — nothing more to do.
            }
            mediaPlayer = null;
        }
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;

            NotificationChannel persistent = new NotificationChannel(
                CHANNEL_PERSISTENT, "Monitoring status", NotificationManager.IMPORTANCE_LOW);
            persistent.setDescription("Shows that TradeTime Alerts is actively watching the market.");
            nm.createNotificationChannel(persistent);

            NotificationChannel alertsChannel = new NotificationChannel(
                CHANNEL_ALERTS, "EMA / Candle Alerts", NotificationManager.IMPORTANCE_HIGH);
            alertsChannel.setDescription("Signal notifications when an EMA or candle-cross alert fires.");
            alertsChannel.setSound(null, null); // the chosen tone is played manually via MediaPlayer
            nm.createNotificationChannel(alertsChannel);
        }
    }

    private Notification buildPersistentNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_PERSISTENT)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("TradeTime Alerts")
            .setContentText("Monitoring your EMA & candle-cross alerts in the background")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build();
    }
}
