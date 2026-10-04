// Trimmed shape of com.oplus.aiunit.vision.ui90 (Gallery 17.9.24), the temperature provider.
//
// Anchors used by EnhancementLocator:
//   * <clinit> loads "debug.gallery.temperature.test"
//   * a() loads "debug.gallery.temperature.level" and "getCurrentTemperature: "
//   * a() is a static, no-argument, float-returning method
package com.oplus.aiunit.vision;

public final class ui90 {
    private static final boolean b = q9k.d("debug.gallery.temperature.test", false);

    public static final float a() {
        int level = q9k.e("debug.gallery.temperature.level", -1);
        // Horae skin-thermal / BATTERY_CHANGED temperature probe.
        return 0.0f;
    }

    public final boolean b(TemperatureThreshold threshold) {
        return a() <= threshold.getTemp();
    }
}
