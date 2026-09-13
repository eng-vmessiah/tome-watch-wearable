package br.vitor.tomewatch;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.content.Context;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Tome Watch — wrist-gesture page turner.
 *
 * Wrist gestures arrive as KeyEvents (Wear OS "Wrist Gestures", enabled in
 * Settings > Gestures > Wrist Gestures):
 *   KEYCODE_NAVIGATE_NEXT    = flick out   -> next page
 *   KEYCODE_NAVIGATE_PREVIOUS= flick in    -> previous page
 *   KEYCODE_NAVIGATE_IN      = flick down  -> scroll down (fast cascade)
 *   KEYCODE_NAVIGATE_OUT     = flick up    -> scroll up (slow step)
 *
 * Each gesture POSTs {"action": ...} to the Tome server (tome-feature-watch
 * plugin), which broadcasts to readers over its WS.
 */
public class MainActivity extends Activity {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // TODO: settings screen or QR config; hardcoded for first bring-up
    private static final String SERVER = "https://tome.ink";
    private static final String TOKEN = "CHANGE_ME";

    private OkHttpClient http;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setTextSize(20);
        status.setPadding(40, 80, 40, 40);
        status.setText("Tome Watch\n\nFlick: prox/anter.\nDown: scroll rapido\nUp: scroll devagar\n\nServer: " + SERVER);
        setContentView(status);

        http = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build();

        // Keep screen interactive-ish while reading; ambient handled by system
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        String action = mapKey(keyCode);
        if (action != null) {
            sendAction(action);
            return true; // consume
        }
        return super.onKeyDown(keyCode, event);
    }

    private static String mapKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_NAVIGATE_NEXT:     return "next";
            case KeyEvent.KEYCODE_NAVIGATE_PREVIOUS: return "prev";
            case KeyEvent.KEYCODE_NAVIGATE_IN:       return "scroll-down";
            case KeyEvent.KEYCODE_NAVIGATE_OUT:      return "scroll-up";
            default: return null;
        }
    }

    private void sendAction(String action) {
        buzz(20);

        String body = "{\"action\":\"" + action + "\"}";
        Request req = new Request.Builder()
                .url(SERVER + "/api/watch/" + TOKEN)
                .post(RequestBody.create(body, JSON))
                .build();

        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> status.setText("✗ falha: " + e.getMessage()));
            }
            @Override public void onResponse(Call call, Response res) throws IOException {
                final String text = res.isSuccessful() ? "✓ enviado" : "✗ HTTP " + res.code();
                res.close();
                runOnUiThread(() -> status.setText(text));
            }
        });
    }

    private void buzz(int ms) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
    }
}
