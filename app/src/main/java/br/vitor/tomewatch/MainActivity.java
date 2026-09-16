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
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.GestureDetector;
import android.view.ScaleGestureDetector;
import org.json.JSONObject;

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
    private String mapDown, mapUp, map2Down, map2Up, map2Tap;
    private String serverUrl;
    private String sessionToken = null;    // current session (per spec: token via create; NOT the tokenVal settings label)
    private boolean inSession = false;
    private FlickDetector detector;
    private String gestureSource = "gyro";
    private String lastAction = "-";
    private int sent = 0;
    private boolean lastFlickWasDown = false, lastFlickWasUp = false;
    private long lastFlickAt = 0;

    // ==== GESTURE PROBE (diagnóstico — mapear o que o Ultra entrega hoje) ====
    private GestureDetector probeGesture;
    private ScaleGestureDetector probeScale;
    private float probeDownX, probeDownY;
    private long probeRotaryBuzzAt = 0;
    // bezel digital (rotary) -> scroll-by {px}, batched
    private static final int ROTARY_TICK_PX = 12;   // px per bezel tick
    private static final int ROTARY_FLUSH_MS = 100; // batching window
    private static final boolean ROTARY_INVERT = false; // flip if the direction feels backwards
    private int rotaryTicks = 0;
    private boolean rotaryFlushScheduled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildMainScreen();

        probeGesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) { Log.d(TAG, "PROBE tap"); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                Log.d(TAG, "double-tap -> " + map2Tap);
                if (!"none".equals(map2Tap)) { updateUi(map2Tap); sendAction(map2Tap); }
                return true;
            }
        });
        probeScale = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public void onScaleEnd(ScaleGestureDetector d) {
                Log.d(TAG, "PROBE pinch factor=" + d.getScaleFactor());
                buzz(18);
            }
        });

        SharedPreferences prefs = getSharedPreferences("tome", MODE_PRIVATE);
        mapDown  = prefs.getString(Settings.K_MAP_DOWN,  "scroll-down");
        mapUp    = prefs.getString(Settings.K_MAP_UP,    "scroll-up");
        map2Down = prefs.getString(Settings.K_MAP_2DOWN, "next");
        map2Up   = prefs.getString(Settings.K_MAP_2UP,   "prev");
        map2Tap  = prefs.getString(Settings.K_MAP_2TAP,  "autoscroll");
        serverUrl = prefs.getString("server", SERVER);
        sessionToken = prefs.getString("last_token", null);
        if (liveHint != null) refreshMainHint();

        status.setOnLongClickListener(v -> {
            openSettingsRoot();
            return true;
        });

        http = new OkHttpClient.Builder()
                .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        int sens = prefs.getInt(Settings.K_SENS, 2);
        gestureSource = prefs.getString(Settings.K_SOURCE, "gyro");

        detector = new FlickDetector((SensorManager) getSystemService(Context.SENSOR_SERVICE),
                new FlickDetector.Callback() {
                    @Override public void onFlick(FlickDetector.Flick flick) {
                        long now = android.os.SystemClock.elapsedRealtime();
                        boolean isDown = (flick == FlickDetector.Flick.DOWN);
                        boolean isUp   = (flick == FlickDetector.Flick.UP);
                        String action;
                        if (isDown || isUp) {
                            boolean sameAsLast = (isDown && lastFlickWasDown) || (isUp && lastFlickWasUp);
                            boolean withinWindow = (now - lastFlickAt) < 1000;
                            if (sameAsLast && withinWindow) {
                                action = isDown ? map2Down : map2Up;
                                lastFlickAt = 0; // consume pair
                            } else {
                                action = isDown ? mapDown : mapUp;
                                lastFlickWasDown = isDown; lastFlickWasUp = isUp;
                                lastFlickAt = now;
                            }
                            if ("none".equals(action)) return; // gesture disabled
                            Log.d(TAG, "FLICK " + flick + " -> " + action);
                            updateUi(action);
                            sendAction(action);
                        } else {
                            Log.d(TAG, "FLICK " + flick + " (classe sem ação mapeada)");
                        }
                    }
                });
    }

    private boolean inSettings = false;
    private Settings settings;

    @Override
    public void onBackPressed() {
        if (inSettings) { inSettings = false; recreate(); }
        else { openSettingsRoot(); }
    }

    private void openSettingsRoot() {
        inSettings = true;
        if (detector != null) detector.stop();
        settings = new Settings(this, () -> { inSettings = false; recreate(); });
        settings.root();
    }

    @Override protected void onResume() {
        super.onResume();
        if (inSettings) return;
        if (!"wearable".equals(gestureSource)) detector.start();
    }

    // ==== probe overrides (não consomem eventos; só logam) ====
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (probeGesture != null) probeGesture.onTouchEvent(ev);
        if (probeScale != null) probeScale.onTouchEvent(ev);
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            probeDownX = ev.getX(); probeDownY = ev.getY();
        } else if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
            float dy = ev.getY() - probeDownY, dx = ev.getX() - probeDownX;
            if (Math.abs(dy) > 24 || Math.abs(dx) > 24) {
                Log.d(TAG, "PROBE drag dx=" + Math.round(dx) + " dy=" + Math.round(dy));
                buzz(12);
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent ev) {
        if (ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
                && ev.getAction() == MotionEvent.ACTION_SCROLL) {
            // settings/session screens keep native bezel scrolling
            if (inSettings || inSession) return super.onGenericMotionEvent(ev);
            float d = ev.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (d == 0) return super.onGenericMotionEvent(ev);
            int dir = (d > 0 ? 1 : -1) * (ROTARY_INVERT ? -1 : 1);
            rotaryTicks += dir;
            scheduleRotaryFlush();
            return true; // consumed — the bezel drives the reader, not this screen
        }
        return super.onGenericMotionEvent(ev);
    }

    /** Batch bezel ticks and ship them as one scroll-by every ~120ms. */
    private void scheduleRotaryFlush() {
        if (rotaryFlushScheduled) return;
        rotaryFlushScheduled = true;
        mainScreen.postDelayed(() -> {
            rotaryFlushScheduled = false;
            int ticks = rotaryTicks;
            rotaryTicks = 0;
            if (ticks == 0) return;
            int px = ticks * ROTARY_TICK_PX;
            if (px > 600) px = 600; else if (px < -600) px = -600;
            Log.d(TAG, "rotary flush px=" + px + " ticks=" + ticks);
            sendScrollBy(px);
        }, ROTARY_FLUSH_MS);
    }

    /** Wrist-gesture keyevents (opt-in; One UI 8 may not deliver — sensor path is primary). */
    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        Log.d(TAG, "PROBE key " + keyCode);
        if ("wearable".equals(gestureSource)) {
            String fromKey = null;
            if (keyCode == android.view.KeyEvent.KEYCODE_NAVIGATE_NEXT) fromKey = "next";
            else if (keyCode == android.view.KeyEvent.KEYCODE_NAVIGATE_PREVIOUS) fromKey = "prev";
            if (fromKey != null) {
                updateUi(fromKey);
                sendAction(fromKey);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }
    @Override protected void onPause()  { super.onPause();  detector.stop();  }

    private void updateUi(String action) {
        lastAction = action;
        sent++;
        String icon = action.equals("next") ? "▶" :
                      action.equals("prev") ? "◀" :
                      action.equals("scroll-down") ? "⬇" :
                      action.equals("autoscroll") ? "∞" : "⬆";
        runOnUiThread(() -> {
            liveAction.setText(icon + " " + action);
            liveCount.setTextColor(Color.parseColor("#7ee08a"));
            liveCount.setText("✓ enviado · ação #" + sent);
        });
    }

    private void sendAction(String action) {
        buzz(20);
        post("{\"action\":\"" + action + "\"}", action);
    }

    /** Bezel fine-scroll — silent (no buzz/HUD), batched by the caller. */
    private void sendScrollBy(int px) {
        post("{\"action\":\"scroll-by\",\"px\":" + px + "}", "scroll-by");
    }

    private void post(String body, String tag) {
        if (sessionToken == null) return;
        Request req = new Request.Builder()
                .url(serverUrl + "/api/watch/" + sessionToken)
                .header("Authorization", "Bearer " + sessionToken)
                .post(RequestBody.create(body, JSON))
                .build();
        http.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.d(TAG, "POST FAILED", e);
                if (call.isExecuted()) {
                    runOnUiThread(() -> {
                        liveAction.setText("✗ " + e.getClass().getSimpleName());
                        liveCount.setTextColor(Color.parseColor("#e07e7e"));
                        liveCount.setText("falha de rede · último: " + lastAction);
                    });
                } else {
                    // silent single retry
                    http.newCall(call.request()).enqueue(this);
                }
            }
            @Override public void onResponse(Call call, Response res) throws IOException {
                int code = res.code();
                String bodyStr = "";
                if (res.body() != null) {
                    try { bodyStr = res.body().string(); } catch (IOException ignored) {}
                }
                res.close();
                Log.d(TAG, "POST RESP " + code + " " + bodyStr);
                if (code == 200 && "scroll-by".equals(tag)) return; // bezel: no HUD updates
                if (code == 400 && !"scroll-by".equals(tag)) {
                    runOnUiThread(() -> {
                        liveCount.setTextColor(Color.parseColor("#e09a5e"));
                        liveCount.setText("⚠ ação não aceita pelo server");
                    });
                }
                if (code == 200) {
                    // readers: how many readers the plugin broadcast to (0 = no
                    // device has the reader open). -1/absent = older plugin: keep ✓.
                    int readers = -1;
                    try {
                        JSONObject o = new JSONObject(bodyStr);
                        if (o.has("readers")) readers = o.optInt("readers", -1);
                    } catch (Exception ignored) {}
                    if (readers == 0) {
                        runOnUiThread(() -> {
                            liveCount.setTextColor(Color.parseColor("#e09a5e"));
                            liveCount.setText("⚠ sem dispositivo · ação #" + sent);
                        });
                    } else if (readers > 0) {
                        runOnUiThread(() -> {
                            liveCount.setTextColor(Color.parseColor("#7ee08a"));
                            liveCount.setText("✓ enviado · ação #" + sent);
                        });
                    }
                }
                if (code == 404) {
                    // 404 = token unknown to THAT server. Show WHICH server answered
                    // so a config mismatch (wrong origin/expiry) is visible at a glance.
                    sessionToken = null;
                    final String host = serverUrl.replaceFirst("^https?://", "").replaceFirst("/$", "");
                    runOnUiThread(() -> {
                        liveAction.setText("sessão expirada");
                        liveCount.setTextColor(Color.parseColor("#e07e7e"));
                        liveCount.setText(host + "\ntoque no rodapé p/ nova sessão");
                        refreshSessionRow();
                    });
                }
            }
        });
    }

    private LinearLayout mainScreen;
    private TextView liveAction;   // big icon+action
    private TextView liveCount;    // actions sent
    private TextView liveHint;     // mapping hint

    private void buildMainScreen() {
        mainScreen = new LinearLayout(this);
        mainScreen.setOrientation(LinearLayout.VERTICAL);
        mainScreen.setGravity(android.view.Gravity.CENTER);
        mainScreen.setBackgroundColor(Color.parseColor("#101010"));
        int pad = dp(10);
        mainScreen.setPadding(dp(16), dp(4), dp(16), dp(0));

        TextView title = new TextView(this);
        title.setText("📖  Tome Watch");
        title.setTextColor(Color.parseColor("#c9b86e"));
        title.setTextSize(18);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(2));
        mainScreen.addView(title);

        liveAction = new TextView(this);
        liveAction.setText("— pronto");
        liveAction.setTextColor(Color.WHITE);
        liveAction.setTextSize(24);
        liveAction.setGravity(android.view.Gravity.CENTER);
        liveAction.setPadding(0, dp(8), 0, dp(2));
        mainScreen.addView(liveAction);

        liveCount = new TextView(this);
        liveCount.setText("ação #0");
        liveCount.setTextColor(Color.parseColor("#666666"));
        liveCount.setTextSize(13);
        liveCount.setGravity(android.view.Gravity.CENTER);
        mainScreen.addView(liveCount);

        liveHint = new TextView(this);
        liveHint.setGravity(android.view.Gravity.CENTER);
        liveHint.setTextColor(Color.parseColor("#555555"));
        liveHint.setTextSize(11);
        liveHint.setPadding(0, dp(4), 0, 0);
        mainScreen.addView(liveHint);

        TextView sessionRow = new TextView(this);
        sessionRow.setId(View.generateViewId());
        sessionRow.setGravity(android.view.Gravity.CENTER);
        sessionRow.setTextColor(Color.WHITE);
        sessionRow.setTextSize(13.5f);
        sessionRow.setBackgroundResource(R.drawable.pill_on);
        sessionRow.setPadding(dp(18), dp(9), dp(18), dp(9));
        LinearLayout.LayoutParams srl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        srl.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        srl.setMargins(0, dp(5), 0, 0);
        sessionRow.setLayoutParams(srl);
        sessionRow.setOnClickListener(v -> openSessionScreen(false));
        mainScreen.addView(sessionRow);
        sessionRowRef = sessionRow;

        TextView newRow = new TextView(this);
        newRow.setId(View.generateViewId());
        newRow.setGravity(android.view.Gravity.CENTER);
        newRow.setTextColor(Color.parseColor("#c9b86e"));
        newRow.setTextSize(13.5f);
        newRow.setBackgroundResource(R.drawable.card_off);
        newRow.setPadding(dp(18), dp(9), dp(18), dp(9));
        LinearLayout.LayoutParams nrl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nrl.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        nrl.setMargins(0, dp(3), 0, 0);
        newRow.setLayoutParams(nrl);
        newRow.setOnClickListener(v -> {
            openSessionScreen(true);          // force fresh create
        });
        mainScreen.addView(newRow);
        newSessionRowRef = newRow;

        refreshSessionRow();
        refreshSessionRow();

        status = liveAction;
        setContentView(mainScreen);
        refreshMainHint();
    }

    private TextView sessionRowRef;
    private TextView newSessionRowRef;

    private void refreshSessionRow() {
        if (newSessionRowRef == null) return;
        String last = getSharedPreferences("tome", MODE_PRIVATE)
                .getString("last_token", null);
        if (sessionToken != null) {
            if (sessionRowRef != null) sessionRowRef.setText("⏱ sessão ativa");
            newSessionRowRef.setText("+ nova sessão");
            newSessionRowRef.setVisibility(View.VISIBLE);
        } else if (last != null) {
            if (sessionRowRef != null) sessionRowRef.setVisibility(View.VISIBLE);
            sessionRowRef.setText("↺ retomar sessão");
            newSessionRowRef.setText("+ nova sessão");
            newSessionRowRef.setVisibility(View.VISIBLE);
        } else {
            if (sessionRowRef != null) sessionRowRef.setVisibility(View.GONE);
            newSessionRowRef.setText("+ nova sessão");
        }
    }

    private void refreshMainHint() {
        SharedPreferences p = getSharedPreferences("tome", MODE_PRIVATE);
        String d = p.getString(Settings.K_MAP_DOWN, "scroll-down");
        String d2 = p.getString(Settings.K_MAP_2DOWN, "next");
        String arrowD = d.equals("scroll-down") ? "↓" : d.equals("scroll-up") ? "↑"
                      : d.equals("next") ? "▶" : d.equals("prev") ? "◀" : "—";
        String pp = d2.startsWith("scroll") ? "rola " + (d2.equals("scroll-down") ? "↓" : "↑") : d2;
        String tap = p.getString(Settings.K_MAP_2TAP, "autoscroll");
        String tapPart = "none".equals(tap) ? "" : "  •  2×toque " + ("autoscroll".equals(tap) ? "auto" : tap);
        liveHint.setText("1x↓ " + arrowD + "  •  2x↓ " + pp + tapPart + "\nsegura p/ config");
    }

    // ============ SESSION FLOW ============
    private void openSessionScreen() { openSessionScreen(false); }

    private void openSessionScreen(boolean forceNew) {
        inSession = true;
        detector.stop();
        if (!forceNew && sessionToken == null) {
            String last = getSharedPreferences("tome", MODE_PRIVATE).getString("last_token", null);
            if (last != null) { sessionToken = last; }
        }
        if (!forceNew && sessionToken != null) {
            showSessionReady(sessionToken);
            return;
        }
        sessionToken = null;   // clean slate for fresh pairing
        createSession();
    }

    private void createSession() {
        TextView t = new TextView(this);
        t.setGravity(android.view.Gravity.CENTER);
        t.setTextColor(Color.WHITE);
        t.setTextSize(17);
        t.setPadding(dp(20), dp(40), dp(20), dp(20));
        t.setText("criando sessão…");
        setContentView(t);

        okhttp3.Request req = new okhttp3.Request.Builder()
                .url(serverUrl + "/api/watch/create")
                .header("Authorization", "Bearer " + (sessionToken == null ? "" : sessionToken))
                .post(RequestBody.create("{}", JSON))
                .build();
        http.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, IOException e) {
                runOnUiThread(() -> sessionError("rede: " + e.getClass().getSimpleName()));
            }
            @Override public void onResponse(Call call, Response res) throws IOException {
                final int code = res.code();
                final String body1 = res.body() != null ? res.body().string() : "";
                res.close();
                runOnUiThread(() -> {
                    if (code != 200) {
                        sessionError(code);
                        return;
                    }
                    try {
                        org.json.JSONObject j = new org.json.JSONObject(body1);
                        sessionToken = j.getString("token");
                        SharedPreferences.Editor e = getSharedPreferences("tome", MODE_PRIVATE).edit();
                        e.putString("last_token", sessionToken);
                        e.apply();
                        showSessionReady(sessionToken);
                    } catch (Exception ex) {
                        sessionError(ex.getClass().getSimpleName());
                    }
                });
            }
        });
    }

    private void sessionError(String what) {
        TextView t = new TextView(this);
        t.setGravity(android.view.Gravity.CENTER);
        t.setTextColor(Color.parseColor("#e07e7e"));
        t.setTextSize(16);
        t.setPadding(dp(20), dp(60), dp(20), 0);
        t.setText(what + "\n\ntocar p/ tentar de novo");
        t.setOnClickListener(v -> createSession());
        setContentView(t);
    }

    private void sessionError(int code) {
        TextView t = new TextView(this);
        t.setGravity(android.view.Gravity.CENTER);
        t.setTextColor(Color.parseColor("#e07e7e"));
        t.setTextSize(16);
        t.setPadding(dp(20), dp(60), dp(20), 0);
        t.setText(code == 302 || code == 401
                ? "server bloqueou create (auth)\naguarde fix do server\n\ntocar p/ tentar de novo"
                : "HTTP " + code + "\n\ntocar p/ tentar de novo");
        t.setOnClickListener(v -> createSession());
        setContentView(t);
    }

    private void createSession2() { createSession(); }

    private void showSessionReady(String token) {
        if (token == null) token = sessionToken;
        inSession = true;
        detector.stop();
        showQr();
    }

    private void showQr() {
        // full pairing screen: QR encoding {server}/watch/pair?token={token} + token big
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER);
        box.setBackgroundColor(Color.parseColor("#101010"));

        String url = serverUrl + "/watch/pair/" + sessionToken;
        int genPx = 300;
        android.widget.ImageView iv = new android.widget.ImageView(this);
        android.graphics.Bitmap bmp = Qr.encode(url, genPx);
        if (bmp != null) {
            iv.setImageBitmap(bmp);
            int viewSize = (int) (getResources().getDisplayMetrics().widthPixels * 0.56);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    viewSize, viewSize);
            lp.gravity = android.view.Gravity.CENTER;
            lp.setMargins(0, dp(4), 0, dp(4));
            iv.setLayoutParams(lp);
            box.addView(iv);
        }

        TextView tk = new TextView(this);
        tk.setPadding(0, dp(6), 0, 0);
        String raw = sessionToken;
        StringBuilder chunks = new StringBuilder();
        for (int i = 0; i < raw.length(); i += 4) {
            chunks.append(raw, i, Math.min(raw.length(), i + 4));
            if (i + 4 < raw.length()) chunks.append(' ');
        }
        tk.setText(chunks.toString());
        tk.setTextColor(Color.WHITE);
        tk.setTextSize(13);
        tk.setGravity(android.view.Gravity.CENTER);
        box.addView(tk);

        TextView note = new TextView(this);
        note.setText("escaneie / insira na web");
        note.setTextColor(Color.parseColor("#8a8a8a"));
        note.setTextSize(11);
        note.setGravity(android.view.Gravity.CENTER);
        box.addView(note);

        TextView back = new TextView(this);
        back.setText("✓ usar app");
        back.setTextColor(Color.parseColor("#7ee08a"));
        back.setTextSize(15);
        back.setGravity(android.view.Gravity.CENTER);
        back.setBackgroundResource(R.drawable.pill_on);
        back.setOnClickListener(v -> { inSession = false; recreate(); });
        box.addView(back);

        setContentView(box);
    }

    // legacy settings UI removed → see Settings.java



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
