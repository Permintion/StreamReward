package dev.streamrewards;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/**
 * Переводы мода. Тексты лежат в assets/streamrewards/lang/*.json и автоматически
 * переключаются вместе с языком Minecraft (русский, английский, украинский).
 */
public final class I18n {
    private static final String PREFIX = "streamrewards.";

    private I18n() {}

    public static Component tr(String key, Object... args) {
        return Component.translatable(PREFIX + key, args);
    }

    public static String str(String key, Object... args) {
        return tr(key, args).getString();
    }

    public static boolean has(String key) {
        return Language.getInstance().has(PREFIX + key);
    }
}
