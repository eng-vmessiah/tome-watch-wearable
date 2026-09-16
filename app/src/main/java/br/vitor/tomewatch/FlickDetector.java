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
 * Air gestures (for toggles that must not need a tap on the watch):
 *   SHAKE = rapid back-and-forth alternation (>=5 direction changes in ~1.4s)
 *   TWIST = deliberate "turn the key" spike on the Z axis (dominant over x/y)
 *
 * Thresholds + debounces to avoid noise and machine-gunning. During a shake
 * in progress the flick path is suppressed so a shake never scrolls.
 */
public class FlickDetector {

    public interface Callback {
        void onFlick(Flick f);
        void onShake();
        void onTwist(int dir);
    }
    public enum Flick { OUT, IN, DOWN, UP }

    private float mag = 12f;  // rad/s spike threshold (set via setSensitivity)
    private float magZ = 9f;  // twist (rotate the hand) threshold — z-axis
    private static final long DEBOUNCE_MS = 600;
    private static final long TWIST_DEBOUNCE_MS = 900;
    private static final float SHAKE_MAG = 5.5f; // gentle swings still count
    private static final int SHAKE_CHANGES = 5;  // direction changes to fire
    private static final long SHAKE_WINDOW_MS = 1400;
    private static final long SHAKE_COOLDOWN_MS = 1500;

    private final SensorManager sm;
    private final Callback cb;
    private boolean shakeEnabled = true;
    private long lastFire = 0;
    private long lastTwistFire = 0;
    private long lastShakeFire = 0;
    private int shakeChanges = 0;
    private int shakeSign = 0;
    private long shakeLastAt = 0;

    public FlickDetector(SensorManager sm, Callback cb) {
        this.sm = sm;
        this.cb = cb;
    }

    /** sensitivity: 1=low (18 rad/s), 2=medium (12), 3=high (7.5) */
    public void setSensitivity(int s) {
        if (s <= 1) { mag = 18f; magZ = 13.5f; }
        else if (s >= 3) { mag = 7.5f; magZ = 5.6f; }
        else { mag = 12f; magZ = 9f; }
    }

    public void start() {
        sm.registerListener(listener, sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                SensorManager.SENSOR_DELAY_GAME);
    }

    public void stop() {
        sm.unregisterListener(listener);
    }

    /** When disabled, the shake path is not tracked at all — it must never
     *  suppress flicks (running arm-swing would otherwise eat them). */
    public void setShakeEnabled(boolean enabled) {
        shakeEnabled = enabled;
    }

    private final SensorEventListener listener = new SensorEventListener() {
        private long lastLog = 0;

        @Override
        public void onSensorChanged(SensorEvent ev) {
            long now = ev.timestamp / 1_000_000L; // ns -> ms approx
            float x = ev.values[0], y = ev.values[1], z = ev.values[2];
            if (now - lastLog > 1500) {
                lastLog = now;
                android.util.Log.d("TomeWatch", String.format("gyro x=%.1f y=%.1f z=%.1f", x, y, z));
            }
            float ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);

            // ---- SHAKE: rapid direction alternation (dominant axis) ----
            // Only tracked when shake is mapped — disabled, it must not
            // suppress flicks (running arm-swing must never eat them).
            if (shakeEnabled) {
                float dom = (ax >= ay && ax >= az) ? x : (ay >= az ? y : z);
                if (Math.abs(dom) > SHAKE_MAG) {
                    int sgn = dom > 0 ? 1 : -1;
                    if (shakeSign == 0 || sgn == shakeSign) {
                        shakeSign = sgn;
                        shakeLastAt = now;
                    } else if (now - shakeLastAt <= SHAKE_WINDOW_MS) {
                        shakeChanges++;
                        shakeLastAt = now;
                        shakeSign = sgn;
                        if (shakeChanges >= SHAKE_CHANGES && now - lastShakeFire >= SHAKE_COOLDOWN_MS) {
                            lastShakeFire = now;
                            shakeChanges = 0;
                            shakeSign = 0;
                            lastFire = now; // eat flicks right after a shake
                            cb.onShake();
                            return;
                        }
                    } else {
                        shakeChanges = 1;
                        shakeLastAt = now;
                        shakeSign = sgn;
                    }
                } else if (now - shakeLastAt > SHAKE_WINDOW_MS) {
                    shakeChanges = 0;
                    shakeSign = 0;
                }

                // suppress flicks while a shake is brewing or just fired
                if (shakeChanges >= 2) return;
                if (now - lastShakeFire < 800) return;
            }

            // ---- TWIST: deliberate z-axis spike ("turn the key") ----
            if (az > magZ && az > ax * 1.4f && az > ay * 1.4f) {
                if (now - lastTwistFire >= TWIST_DEBOUNCE_MS) {
                    lastTwistFire = now;
                    cb.onTwist(z > 0 ? 1 : -1);
                }
                return;
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
