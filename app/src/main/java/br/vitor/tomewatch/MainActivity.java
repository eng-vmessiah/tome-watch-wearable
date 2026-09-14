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
import android.widget.ScrollView;
import android.widget.EditText;
import android.app.AlertDialog;
import android.text.TextUtils;
import android.view.View;
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
                            boolean pairDown = (isDown != invertPair);
                            action = pairDown ? "next" : "prev";
                            lastFlickAt = 0; // consume pair
                        } else {
                            boolean down = (isDown != invertScroll);
                            action = down ? "scroll-down" : "scroll-up";
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

    @Override protected void onResume() { super.onResume(); if (!inSettings) detector.start(); }
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
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.parseColor("#131313"));
        int pad = (int) (getResources().getDisplayMetrics().density * 12);
        box.setPadding(pad * 2, pad * 3, pad * 2, pad * 2);

        TextView title = new TextView(this);
        title.setText("⚙︎  Tomes");
        title.setTextColor(Color.parseColor("#c9b86e"));
        title.setTextSize(19);
        title.setPadding(0, 0, 0, pad);
        box.addView(title);

        box.addView(toggleCard("Flick ↓  (scroll-down)",
                invertScroll ? "in — inverte: 1x↓ sobe" : "padrao: 1x↓ desce",
                invertScroll, v -> { invertScroll = !invertScroll; save(true); }));
        box.addView(toggleCard("Doublê ↑  (next/prev)",
                invertPair ? "inverto: 2x↓=prev" : "padrao: 2x↓=next",
                invertPair, v -> { invertPair = !invertPair; save(true); }));
        box.addView(editCard("Server", serverUrl, v -> promptEdit("Server URL", serverUrl, s -> { serverUrl = s; save(true); })));
        box.addView(editCard("Token", tokenVal, v -> promptEdit("Token", tokenVal, s -> { tokenVal = s; save(true); })));

        TextView done = new TextView(this);
        done.setText("✓  Voltar");
        done.setTextColor(Color.parseColor("#7ee08a"));
        done.setTextSize(18);
        done.setPadding(0, pad * 2, 0, pad);
        done.setOnClickListener(v -> onBackPressed());
        done.setId(View.generateViewId());
        box.addView(done);

        scroll.addView(box);
        setContentView(scroll);
        inSettings = true;
        detector.stop();
    }

    private LinearLayout toggleCard(String title, String sub, boolean on, View.OnClickListener onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad(), pad(), pad(), pad());
        card.setBackgroundResource(on ? R.drawable.card_on : R.drawable.card_off);
        card.setOnClickListener(onClick);

        TextView main = new TextView(this);
        main.setText((on ? "☑ " : "☐ ") + title);
        main.setTextColor(Color.WHITE);
        main.setTextSize(16);
        card.addView(main);

        TextView subT = new TextView(this);
        subT.setText(sub);
        subT.setTextColor(Color.parseColor("#888888"));
        subT.setTextSize(13);
        subT.setPadding(0, dp(4), 0, 0);
        card.addView(subT);
        return card;
    }

    private LinearLayout editCard(String label, String value, View.OnClickListener edit) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad(), pad(), pad(), pad());
        card.setBackgroundResource(R.drawable.card_off);
        card.setOnClickListener(edit);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Color.parseColor("#6fa8dc"));
        l.setTextSize(13);
        card.addView(l);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(Color.WHITE);
        v.setTextSize(14);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        v.setPadding(0, dp(4), 0, 0);
        card.addView(v);
        return card;
    }

    private void save(boolean restartUi) {
        getSharedPreferences("tome", MODE_PRIVATE).edit()
            .putBoolean("invert_scroll", invertScroll)
            .putBoolean("invert_pair", invertPair)
            .putString("server", serverUrl)
            .putString("token", tokenVal)
            .apply();
        if (restartUi && inSettings) openSettings(); // re-render settings in place (keeps you there)
        // if NOT in settings (main screen), preferences apply on next recreate() or re-open
    }

    private interface Textcb { void run(String s); }
    private void promptEdit(String title, String value, Textcb cb) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(title);
        final EditText input = new EditText(this);
        input.setText(value);
        input.setTextColor(Color.WHITE);
        b.setView(input);
        b.setPositiveButton("OK", (d, w) -> cb.run(input.getText().toString().trim()));
        b.setNegativeButton("Cancel", (d, w) -> d.dismiss());
        b.show();
    }

    private int pad()   { return (int) (getResources().getDisplayMetrics().density * 12); }
    private int dp(int d) { return (int) (getResources().getDisplayMetrics().density * d); }

    private void buzz(int ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception e) {
            Log.d(TAG, "buzz failed", e);
        }
    }
}
