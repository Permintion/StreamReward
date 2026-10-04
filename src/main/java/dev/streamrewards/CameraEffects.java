package dev.streamrewards;

/**
 * Эффект «камера вверх ногами». Хранит только время начала/конца эффекта,
 * а угол наклона вычисляется по часам — поэтому переворот плавный независимо от FPS.
 * Сам угол применяет CameraMixin.
 */
public final class CameraEffects {
    /** Длительность плавного переворота и возврата, мс. */
    private static final long FADE_MS = 700;

    private static long startMs = 0;
    private static long endMs = 0;

    private CameraEffects() {}

    /** Перевернуть камеру на указанное число секунд (повторные покупки продлевают эффект). */
    public static void flip(int seconds) {
        long now = System.currentTimeMillis();
        long hold = Math.max(1, seconds) * 1000L;
        if (now < endMs) {
            endMs += hold;
        } else {
            startMs = now;
            endMs = now + hold;
        }
    }

    public static void reset() {
        startMs = 0;
        endMs = 0;
    }

    /** Текущий угол крена камеры в градусах: 0..180. */
    public static float currentRoll() {
        if (endMs == 0) return 0.0F;
        long now = System.currentTimeMillis();
        if (now < startMs) return 0.0F;
        if (now < startMs + FADE_MS) return 180.0F * ease((now - startMs) / (float) FADE_MS);
        if (now < endMs) return 180.0F;
        if (now < endMs + FADE_MS) return 180.0F * (1.0F - ease((now - endMs) / (float) FADE_MS));
        endMs = 0;
        return 0.0F;
    }

    private static float ease(float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        return t * t * (3.0F - 2.0F * t);
    }
}
