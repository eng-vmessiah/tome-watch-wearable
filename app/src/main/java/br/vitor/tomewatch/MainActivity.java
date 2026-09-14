package br.vitor.tomewatch;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;
import android.content.SharedPreferences;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.graphics.Color;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Tome Watch — wrist-flick page turner (sensor-based, no system gesture deps).
 *
 *   flick OUT  -> next          flick IN -> prev
 *   flick DOWN -> scroll-down   flick UP -> scroll-up
 */
public class MainActivity extends android.app.Activity {

    private static final String TAG = "TomeWatch";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // TODO: settings screen or QR config; hardcoded for bring-up
    private static final String SERVER = "http://192.168.0.2:3997";
    private static final String TOKEN = "13aphbn04oxsot2o";

    private OkHttpClient http;
    private TextView status;
    private boolean invertScroll = false, invertPair = false;
    private String serverUrl, tokenVal;
    private FlickDetector detector;
    private String lastAction = "-";
    private int sent = 0;
    private boolean lastFlickWasDown = false, lastFlickWasUp = false;
    private long lastFlickAt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setTextSize(22);
        status.setPadding(30, 70, 30, 30);
        status.setText("Tome Watch\n1x↓=↓  2x↓=next\n1x↑=↑  2x↑=prev");
        setContentView(status);

        SharedPreferences prefs = getSharedPreferences("tome", MODE_PRIVATE);
        invertScroll = prefs.getBoolean("invert_scroll", false);
        invertPair = prefs.getBoolean("invert_pair", false);
        serverUrl = prefs.getString("server", SERVER);
        tokenVal = prefs.getString("token", TOKEN);

        status.setOnLongClickListener(v -> { openSettings(); return true; });

        http = new OkHttpClient.Builder()
                .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        detector = new FlickDetector((SensorManager) getSystemService(Context.SENSOR_SERVICE),
                flick -> {
                    long now = android.os.SystemClock.elapsedRealtime();
                    boolean isDown = (flick == FlickDetector.Flick.DOWN);
                    boolean isUp   = (flick == FlickDetector.Flick.UP);
                    String action;
                    if (isDown || isUp) {
                        boolean sameAsLast = (isDown && lastFlickWasDown) || (isUp && lastFlickWasUp);
                        boolean withinWindow = (now - lastFlickAt) < 1000;
                        if (sameAsLast && withinWindow) {
                            action = isDown ? "next" : "prev";
                            lastFlickAt = 0; // consume pair
                        } else {
                            action = isDown ? "scroll-down" : "scroll-up";
                            lastFlickWasDown = isDown; lastFlickWasUp = isUp;
                            lastFlickAt = now;
                        }
                        Log.d(TAG, "FLICK " + flick + " -> " + action);
                        updateUi(action);
                        sendAction(action);
                    }
                });
    }

    private boolean inSettings = false;

    @Override
    public void onBackPressed() {
        if (inSettings) { inSettings = false; recreate(); }
        else { inSettings = true; detector.stop(); openSettings(); }
    }

    @Override protected void onResume() { if (!inSettings) detector.start(); }
    @Override protected void onPause()  { super.onPause();  detector.stop();  }

    private void updateUi(String action) {
        lastAction = action;
        sent++;
        runOnUiThread(() -> status.setText(
            "→ " + lastAction + "\n#" + sent + "\n" + SERVER));
    }

    private void sendAction(String action) {
        buzz(20);
        String body = "{\"action\":\"" + action + "\"}";
        Request req = new Request.Builder()
                .url(serverUrl + "/api/watch/" + tokenVal)
                .post(RequestBody.create(body, JSON))
                .build();
        http.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.d(TAG, "POST FAILED", e);
                runOnUiThread(() -> status.setText("✗ " + e.getClass().getSimpleName() + "\n→ " + lastAction));
            }
            @Override public void onResponse(Call call, Response res) throws IOException {
                int code = res.code();
                res.close();
                Log.d(TAG, "POST RESP " + code);
            }
        });
    }

    private void openSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(30, 60, 30, 30);
        box.addView(row("Inverter scroll: " + (invertScroll ? "SIM" : "não"), v -> {
            invertScroll = !invertScroll; save(); recreate();
        }));
        box.addView(row("Inverter next/prev: " + (invertPair ? "SIM" : "não"), v -> {
            invertPair = !invertPair; save(); recreate();
        }));
        box.addView(row("Server: " + serverUrl, v -> {}));
        box.addView(row("Token: " + tokenVal, v -> {}));
        setContentView(box);
    }

    private TextView row(String label, android.view.View.OnClickListener onClick) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setPadding(16, 34, 16, 34);
        t.setTextSize(17);
        t.setOnClickListener(onClick);
        return t;
    }

    private void save() {
        getSharedPreferences("tome", MODE_PRIVATE).edit()
            .putBoolean("invert_scroll", invertScroll)
            .putBoolean("invert_pair", invertPair)
            .putString("server", serverUrl)
            .putString("token", tokenVal)
            .apply();
    }

    private void buzz(int ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception e) {
            Log.d(TAG, "buzz failed", e);
        }
    }
}
