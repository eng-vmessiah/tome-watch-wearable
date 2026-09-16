package br.vitor.tomewatch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Multi-page settings: root nav → Gestos (mapping) / Sensor / Server.
 * Persisted in SharedPreferences "tome".
 */
public final class Settings {

    public interface Restart { void go(); }

    // ---- persisted keys ----
    public static final String K_MAP_DOWN  = "map_down";    // action for 1x flick down
    public static final String K_MAP_UP    = "map_up";
    public static final String K_MAP_2DOWN = "map_2down";
    public static final String K_MAP_2UP   = "map_2up";
    public static final String K_MAP_2TAP  = "map_2tap";   // action for 2x tap on screen
    public static final String K_SOURCE    = "gesture_source"; // gyro | wearable
    public static final String K_SENS      = "sensitivity";    // 1..3 (low..high)
    public static final String K_SERVER    = "server";

    public static final String[] ACTIONS = {"none", "scroll-down", "scroll-up", "next", "prev", "autoscroll"};

    private final Activity a;
    private final SharedPreferences p;
    private final Restart onBack;

    public Settings(Activity a, Restart onBack) {
        this.a = a; this.p = a.getSharedPreferences("tome", Activity.MODE_PRIVATE); this.onBack = onBack;
    }

    // helpers ---------------------------------------------------------------
    private int pad(){ return (int)(a.getResources().getDisplayMetrics().density*12); }
    private int dp(int d){ return (int)(a.getResources().getDisplayMetrics().density*d); }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(pad(), pad(), pad(), pad());
        c.setBackgroundResource(R.drawable.card_off);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        c.setLayoutParams(lp);
        return c;
    }

    private TextView nav(String emoji, String title, String sub, Runnable open) {
        TextView t = new TextView(a);
        t.setText(emoji + "  " + title + "\n     " + sub);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setPadding(pad(), pad(), pad(), pad());
        t.setBackgroundResource(R.drawable.card_off);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> open.run());
        return t;
    }

    private TextView header(String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(Color.parseColor("#c9b86e"));
        t.setTextSize(17);
        t.setPadding(0, dp(8), 0, dp(4));
        return t;
    }

    private TextView back() {
        TextView t = new TextView(a);
        t.setText("✓  Voltar");
        t.setTextColor(Color.parseColor("#7ee08a"));
        t.setTextSize(15);
        t.setGravity(android.view.Gravity.CENTER);
        t.setBackgroundResource(R.drawable.pill_on);
        t.setPadding(dp(20), dp(10), dp(20), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, dp(10), 0, dp(4));
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> onBack.go());
        return t;
    }

    private TextView title(String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(Color.WHITE); t.setTextSize(15);
        return t;
    }

    // ---- ROOT ----
    public void root() {
        ScrollView sc = new ScrollView(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad()*2, pad()*2, pad()*2, pad());
        box.setBackgroundColor(Color.parseColor("#101010"));

        box.addView(back());

        box.addView(nav("🎚", "Gestos", "mapear movimentos → ações",
            () -> gestures()));
        box.addView(nav("📡", "Sensor", p.getString(K_SOURCE,"gyro").equals("wearable")
            ? "gyro + wrist gestures (samsung)" : "giroscópio (padrão)",
            () -> sensor()));
        box.addView(nav("🌐", "Server", stripUrl(p.getString(K_SERVER, "")),
            () -> server()));
        // NOTE: host() helper defined below; fallback safe

        TextView ver = new TextView(a);
        ver.setText("Tome Watch v2.0");
        ver.setTextColor(Color.parseColor("#555555"));
        ver.setTextSize(11);
        ver.setGravity(android.view.Gravity.CENTER);
        ver.setPadding(0, dp(10), 0, 0);
        box.addView(ver);

        sc.addView(box);
        a.setContentView(sc);
    }

    private static String stripUrl(String s) {
        return s.replaceFirst("^https?://", "").replaceFirst("/$", "");
    }

    // ---- GESTURES ----
    private void gestures() {
        ScrollView sc = new ScrollView(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad()*2, pad()*2, pad()*2, pad());
        box.setBackgroundColor(Color.parseColor("#101010"));

        box.addView(back());
        box.addView(header("Movimento → ação"));

        mapRow(box, "1× flick ↓ (pulso desce)", K_MAP_DOWN, "scroll-down");
        mapRow(box, "1× flick ↑ (pulso sobe)", K_MAP_UP, "scroll-up");
        mapRow(box, "2× flick ↓ (rápido)", K_MAP_2DOWN, "next");
        mapRow(box, "2× flick ↑ (rápido)", K_MAP_2UP, "prev");
        mapRow(box, "2× toque na tela", K_MAP_2TAP, "autoscroll");

        sc.addView(box);
        a.setContentView(sc);
    }

    private void mapRow(LinearLayout box, String label, String key, String def) {
        LinearLayout c = card();
        TextView t = title(label);
        c.addView(t);
        TextView v = new TextView(a);
        v.setText(p.getString(key, def));
        v.setTextColor(Color.parseColor("#7ee08a"));
        v.setTextSize(16);
        v.setPadding(0, dp(6), 0, 0);
        c.addView(v);
        c.setOnClickListener(x -> {
            String cur = p.getString(key, def);
            int i = 0; for (int k = 0; k < ACTIONS.length; k++) if (ACTIONS[k].equals(cur)) i = k;
            v.setText(ACTIONS[(i+1) % ACTIONS.length]);
            p.edit().putString(key, ACTIONS[(i+1) % ACTIONS.length]).apply();
        });
        box.addView(c);
    }

    // ---- SENSOR ----
    private void sensor() {
        ScrollView sc = new ScrollView(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad()*2, pad()*2, pad()*2, pad());
        box.setBackgroundColor(Color.parseColor("#101010"));

        box.addView(back());
        box.addView(header("Detecção"));
        String src = p.getString(K_SOURCE, "gyro");

        TextView g = nav(src.equals("gyro") ? "◉" : "○", "Giroscópio", "padrão, sempre disponível",
            () -> { p.edit().putString(K_SOURCE,"gyro").apply(); sensor(); });
        box.addView(g);
        TextView w = nav(src.equals("wearable") ? "◉" : "○", "Wrist gestures (sistema)",
            "Samsung/pixel: só funciona se o seu relógio enviar",
            () -> { p.edit().putString(K_SOURCE,"wearable").apply(); sensor(); });
        box.addView(w);

        box.addView(header("Sensibilidade (gyro)"));
        int sens = p.getInt(K_SENS, 2);
        LinearLayout row = new LinearLayout(a);
        String[] names = {"baixa", "média", "alta"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView b = new TextView(a);
            b.setText(names[i]);
            b.setTextColor(Color.WHITE); b.setTextSize(13);
            b.setPadding(dp(8), dp(8), dp(8), dp(8));
            b.setBackgroundResource(i == sens-1 ? R.drawable.pill_on : R.drawable.pill_off);
            b.setOnClickListener(v -> { p.edit().putInt(K_SENS, idx+1).apply(); sensor(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(3), dp(6), dp(3), 0);
            row.addView(b, lp);
        }
        box.addView(row);

        sc.addView(box);
        a.setContentView(sc);
    }

    // ---- SERVER ----
    private void server() {
        ScrollView sc = new ScrollView(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad()*2, pad()*2, pad()*2, pad());
        box.setBackgroundColor(Color.parseColor("#101010"));

        box.addView(back());
        box.addView(header("Server"));
        LinearLayout c = card();
        TextView l = new TextView(a); l.setText("URL"); l.setTextColor(Color.parseColor("#6fa8dc")); l.setTextSize(13);
        c.addView(l);
        TextView v = new TextView(a); v.setText(p.getString("server",""));
        v.setTextColor(Color.WHITE); v.setTextSize(14);
        v.setPadding(0, dp(6), 0, 0);
        c.addView(v);
        c.setOnClickListener(x -> {
            AlertDialog.Builder b = new AlertDialog.Builder(a);
            b.setTitle("Server URL");
            final EditText input = new EditText(a);
            input.setText(p.getString("server",""));
            b.setView(input);
            b.setPositiveButton("OK", (d,w) -> {
                String s = input.getText().toString().trim();
                p.edit().putString("server", s).apply(); server();
            });
            b.setNegativeButton("Cancel", (d,w)->d.dismiss());
            b.show();
        });
        box.addView(c);

        sc.addView(box);
        a.setContentView(sc);
    }
}
