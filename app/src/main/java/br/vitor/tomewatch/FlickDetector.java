package br.vitor.tomewatch;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Wrist-gesture detector using the raw gyroscope.
 *
 * Flicks are single fast rotation pulses:
 *   OUT  = gyro Y spikes negative   IN  = gyro Y spikes positive (roll)
 *   DOWN = gyro X spikes positive   UP  = gyro X spikes negative (pitch)
 *
 * Simplified by decision (2026-09-16): air gestures (shake/twist) were removed
 * — on a treadmill the arm-swing collides with them. Final set: plain flicks
 * (here) + double-tap + digital bezel (both in MainActivity).
 */
public class FlickDetector {

    public interface Callback {
        void onFlick(Flick f);
    }
    public enum Flick { OUT, IN, DOWN, UP }

    private float mag = 12f;  // rad/s spike threshold (set via setSensitivity)
    private static final long DEBOUNCE_MS = 600;

    private final SensorManager sm;
    private final Callback cb;
    private long lastFire = 0;

    public FlickDetector(SensorManager sm, Callback cb) {
        this.sm = sm;
        this.cb = cb;
    }

    /** sensitivity: 1=low (18 rad/s), 2=medium (12), 3=high (7.5) */
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
        private long lastSpikeLog = 0;

        @Override
        public void onSensorChanged(SensorEvent ev) {
            long now = ev.timestamp / 1_000_000L; // ns -> ms approx
            float x = ev.values[0], y = ev.values[1], z = ev.values[2];
            if (now - lastLog > 1500) {
                lastLog = now;
                android.util.Log.d("TomeWatch", String.format("gyro x=%.1f y=%.1f z=%.1f", x, y, z));
            }
            float ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);

            // diagnostic aid: notable rotations (sparse, muted)
            float mx = Math.max(ax, Math.max(ay, az));
            if (mx > 3.5f && now - lastSpikeLog > 150) {
                lastSpikeLog = now;
                android.util.Log.d("TomeWatch", String.format("SPIKE x=%.1f y=%.1f z=%.1f", x, y, z));
            }

            // ---- FLICK (x = pitch, y = roll) ----
            if (now - lastFire < DEBOUNCE_MS) return;
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
