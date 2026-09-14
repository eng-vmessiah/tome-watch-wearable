package br.vitor.tomewatch;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Wrist-flick detector using the raw gyroscope.
 *
 * A flick is a fast, short-lived rotation pulse on the watch's roll axis:
 *   flick OUT  = gyro Y spikes negative (rotate wrist away)
 *   flick IN   = gyro Y spikes positive  (rotate wrist toward body)
 *   flick DOWN = gyro X spikes positive  (drop wrist)
 *   flick UP   = gyro X spikes negative  (raise wrist)
 *
 * Thresholds + debounce to avoid noise and machine-gunning.
 */
public class FlickDetector {

    public interface Callback { void onFlick(Flick f); }
    public enum Flick { OUT, IN, DOWN, UP }

    private float mag = 12f;   // rad/s spike threshold (set via setSensitivity)
    private static final long DEBOUNCE_MS = 600;
    private static final long RESET_MS = 350; // window for peak. simple single-spike

    private final SensorManager sm;
    private final Callback cb;
    private long lastFire = 0;

    public FlickDetector(SensorManager sm, Callback cb) {
        this.sm = sm;
        this.cb = cb;
    }

    /** sensitivity: 1=low (20 rad/s), 2=medium (12), 3=high (7) */
    public void setSensitivity(int s) {
        if (s <= 1) mag = 18f;
        else if (s >= 3) mag = 7.5f;
        else mag = 12f;
    }

    public void start() {
        sm.registerListener(listener, sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                SensorManager.SENSOR_DELAY_GAME);
    }

    public void stop() {
        sm.unregisterListener(listener);
    }

    private final SensorEventListener listener = new SensorEventListener() {
        private long lastLog = 0;

        @Override
        public void onSensorChanged(SensorEvent ev) {
            long now = ev.timestamp / 1_000_000L; // ns -> ms approx
            if (now - lastFire < DEBOUNCE_MS) return;

            float x = ev.values[0], y = ev.values[1], z = ev.values[2];
            if (now - lastLog > 1500) {
                lastLog = now;
                android.util.Log.d("TomeWatch", String.format("gyro x=%.1f y=%.1f z=%.1f", x, y, z));
            }
            float ax = Math.abs(x), ay = Math.abs(y);

            Flick flick = null;
            if (ay > mag && ay > ax * 1.4f) {
                flick = (y < 0) ? Flick.OUT : Flick.IN;
            } else if (ax > mag && ax > ay * 1.4f) {
                flick = (x > 0) ? Flick.DOWN : Flick.UP;
            }
            if (flick != null) {
                lastFire = now;
                cb.onFlick(flick);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor s, int a) {}
    };
}
