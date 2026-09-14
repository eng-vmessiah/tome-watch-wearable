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
    private String serverUrl;
    private String sessionToken = null;    // current session (per spec: token via create; NOT the tokenVal settings label)
    private boolean inSession = false;
    private FlickDetector detector;
    private String lastAction = "-";
    private int sent = 0;
    private boolean lastFlickWasDown = false, lastFlickWasUp = false;
    private long lastFlickAt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildMainScreen();

        SharedPreferences prefs = getSharedPreferences("tome", MODE_PRIVATE);
        invertScroll = prefs.getBoolean("invert_scroll", false);
        invertPair = prefs.getBoolean("invert_pair", false);
        serverUrl = prefs.getString("server", SERVER);
        sessionToken = prefs.getString("last_token", null);
        if (liveHint != null) refreshMainHint();

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
        String icon = action.equals("next") ? "▶" :
                      action.equals("prev") ? "◀" :
                      action.equals("scroll-down") ? "⬇" : "⬆";
        runOnUiThread(() -> {
            liveAction.setText(icon + " " + action);
            liveCount.setTextColor(Color.parseColor("#7ee08a"));
            liveCount.setText("✓ enviado · ação #" + sent);
        });
    }

    private void sendAction(String action) {
        buzz(20);
        String body = "{\"action\":\"" + action + "\"}";
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
                res.close();
                Log.d(TAG, "POST RESP " + code);
                if (code == 404) {
                    // session expired per spec: surface New session, don't die
                    sessionToken = null;
                    runOnUiThread(() -> {
                        liveAction.setText("sessão expirou");
                        liveCount.setTextColor(Color.parseColor("#e07e7e"));
                        liveCount.setText("toque no rodapé p/ nova sessão");
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
        mainScreen.setPadding(dp(16), pad, dp(16), pad);

        TextView title = new TextView(this);
        title.setText("📖  Tome Watch");
        title.setTextColor(Color.parseColor("#c9b86e"));
        title.setTextSize(18);
        title.setGravity(android.view.Gravity.CENTER);
        mainScreen.addView(title);

        liveAction = new TextView(this);
        liveAction.setText("— pronto");
        liveAction.setTextColor(Color.WHITE);
        liveAction.setTextSize(24);
        liveAction.setGravity(android.view.Gravity.CENTER);
        liveAction.setPadding(0, dp(18), 0, dp(4));
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
        liveHint.setTextSize(12);
        liveHint.setPadding(0, dp(14), 0, 0);
        mainScreen.addView(liveHint);

        TextView sessionRow = new TextView(this);
        sessionRow.setId(View.generateViewId());
        sessionRow.setGravity(android.view.Gravity.CENTER);
        sessionRow.setTextColor(Color.parseColor("#6fa8dc"));
        sessionRow.setTextSize(15);
        sessionRow.setPadding(0, dp(10), 0, 0);
        sessionRow.setOnClickListener(v -> openSessionScreen());
        mainScreen.addView(sessionRow);
        sessionRowRef = sessionRow;
        refreshSessionRow();

        status = liveAction;
        setContentView(mainScreen);
        refreshMainHint();
    }

    private TextView sessionRowRef;

    private void refreshSessionRow() {
        if (sessionRowRef == null) return;
        String last = getSharedPreferences("tome", MODE_PRIVATE)
                .getString("last_token", null);
        sessionRowRef.setText(sessionToken != null
                ? "sessão: " + sessionToken.substring(0, 4) + "… (tocar p/ nova)"
                : (last != null ? "retomar sessão " + last.substring(0, 4) + "…"
                                : "+ nova sessão"));
    }

    private void refreshMainHint() {
        String up = invertScroll ? "↑" : "↓";
        String pair = invertPair ? "prev" : "next";
        liveHint.setText("1x↓ rola " + (invertScroll ? "↑" : "↓") + "   •   2x↓ " + pair +
            "\nsegura p/ config");
    }

    // ============ SESSION FLOW ============
    private void openSessionScreen() {
        if (sessionToken == null) {
            String last = getSharedPreferences("tome", MODE_PRIVATE).getString("last_token", null);
            if (last != null) { sessionToken = last; }
        }
        if (sessionToken != null) {
            showSessionReady(sessionToken);
            return;
        }
        inSession = true;
        detector.stop();
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

        box.addView(choiceCard("ROLAGEM — o que o flick ↓ faz",
                new String[]{"↓ desce a página", "↓ sobe a página"},
                invertScroll ? 1 : 0, i -> { invertScroll = (i == 1); save(true); })); 
        box.addView(choiceCard("PÁGINA INTEIRA — dois flicks rápidos ↓",
                new String[]{"2x↓ = próxima", "2x↓ = anterior"},
                invertPair ? 1 : 0, i -> { invertPair = (i == 1); save(true); }));
        box.addView(editCard("Server", serverUrl, v -> promptEdit("Server URL", serverUrl, s -> { serverUrl = s; save(true); })));

        TextView done = new TextView(this);
        done.setText("✓  Voltar");
        done.setTextColor(Color.parseColor("#7ee08a"));
        done.setTextSize(17);
        done.setGravity(android.view.Gravity.CENTER);
        done.setBackgroundResource(R.drawable.pill_on);
        done.setPadding(dp(20), dp(12), dp(20), dp(12));
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        doneLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        doneLp.setMargins(0, pad * 2, 0, dp(6));
        done.setLayoutParams(doneLp);
        done.setOnClickListener(v -> onBackPressed());
        done.setId(View.generateViewId());
        box.addView(done);

        scroll.addView(box);
        setContentView(scroll);
        inSettings = true;
        detector.stop();
    }

    public interface Intcb { void take(int i); }
    private LinearLayout choiceCard(String title, String[] options, int selected, Intcb cb) {
        LinearLayout card = cardShell();
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.parseColor("#c9b86e"));
        t.setTextSize(13);
        card.addView(t);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            TextView b = new TextView(this);
            b.setText(options[i]);
            b.setTextSize(13.5f);
            b.setTextColor(Color.WHITE);
            b.setPadding(dp(10), dp(10), dp(10), dp(10));
            b.setBackgroundResource(i == selected ? R.drawable.pill_on : R.drawable.pill_off);
            b.setOnClickListener(v -> cb.take(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(4), dp(8), dp(4), 0);
            row.addView(b, lp);
        }
        card.addView(row);
        return card;
    }

    private LinearLayout cardShell() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(pad(), pad(), pad(), pad());
        c.setBackgroundResource(R.drawable.card_off);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(8));
        c.setLayoutParams(lp);
        return c;
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
            .putString("last_token", sessionToken)
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
