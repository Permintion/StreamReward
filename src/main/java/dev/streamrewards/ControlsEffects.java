package dev.streamrewards;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

import java.util.HashMap;
import java.util.Map;

/**
 * Эффекты управления: «Инвертировать управление», «Заблокировать клавиатуру», «Заблокировать мышь».
 * Работают через временную подмену клавиш в настройках. Исходные клавиши записываются в конфиг,
 * поэтому даже если игра закроется или упадёт во время эффекта, при следующем запуске всё вернётся.
 * Несколько эффектов могут действовать одновременно.
 */
public final class ControlsEffects {
    private static long invertUntil = 0;
    private static long keyboardUntil = 0;
    private static long mouseUntil = 0;

    private ControlsEffects() {}

    // ---------- Запуск эффектов ----------

    public static void invert(int seconds) {
        boolean was = invertUntil != 0;
        invertUntil = extend(invertUntil, seconds);
        if (!was) refresh();
    }

    public static void blockKeyboard(int seconds) {
        boolean was = keyboardUntil != 0;
        keyboardUntil = extend(keyboardUntil, seconds);
        if (!was) refresh();
    }

    public static void blockMouse(int seconds) {
        boolean was = mouseUntil != 0;
        mouseUntil = extend(mouseUntil, seconds);
        if (!was) refresh();
    }

    /** Заблокирован ли сейчас поворот камеры мышью (читает CameraMixin/EntityTurnMixin). */
    public static boolean mouseLookBlocked() {
        long until = mouseUntil;
        return until != 0 && System.currentTimeMillis() < until;
    }

    /** Вызывается каждый клиентский тик: выключает эффекты по истечении времени. */
    public static void tick() {
        if (invertUntil == 0 && keyboardUntil == 0 && mouseUntil == 0) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        if (invertUntil != 0 && now >= invertUntil) {
            invertUntil = 0;
            changed = true;
        }
        if (keyboardUntil != 0 && now >= keyboardUntil) {
            keyboardUntil = 0;
            changed = true;
        }
        if (mouseUntil != 0 && now >= mouseUntil) {
            mouseUntil = 0;
            changed = true;
        }
        if (changed) refresh();
    }

    /** Выключает все эффекты и возвращает клавиши на место. Безопасно вызывать в любой момент. */
    public static void restore() {
        invertUntil = 0;
        keyboardUntil = 0;
        mouseUntil = 0;
        refresh();
    }

    // ---------- Внутреннее ----------

    private static long extend(long until, int seconds) {
        long now = System.currentTimeMillis();
        long hold = Math.max(1, seconds) * 1000L;
        return until > now ? until + hold : now + hold;
    }

    /** Пересчитывает, какие клавиши на что назначены с учётом активных эффектов. */
    private static void refresh() {
        Options o = Minecraft.getInstance().options;
        if (o == null) return;
        KeyMapping[] all = o.keyMappings;
        Config cfg = Config.INSTANCE;

        boolean invert = invertUntil != 0;
        boolean keyboard = keyboardUntil != 0;
        boolean mouse = mouseUntil != 0;

        if (!invert && !keyboard && !mouse) {
            Map<String, String> backup = cfg.keyBackup;
            if (backup != null && !backup.isEmpty()) {
                try {
                    for (KeyMapping km : all) {
                        String saved = backup.get(km.getName());
                        if (saved != null) km.setKey(InputConstants.getKey(saved));
                    }
                    KeyMapping.resetMapping();
                    KeyMapping.releaseAll();
                } catch (Exception e) {
                    StreamRewardsClient.LOG.error("Не удалось вернуть клавиши управления", e);
                }
            }
            if (cfg.keyBackup != null) {
                cfg.keyBackup = null;
                Config.save();
            }
            return;
        }

        // первое изменение — запоминаем исходные клавиши (для восстановления, в том числе после сбоя)
        if (cfg.keyBackup == null || cfg.keyBackup.isEmpty()) {
            Map<String, String> backup = new HashMap<>();
            for (KeyMapping km : all) backup.put(km.getName(), km.saveString());
            cfg.keyBackup = backup;
            Config.save();
        }
        Map<String, String> orig = cfg.keyBackup;

        for (KeyMapping km : all) {
            String target = orig.get(km.getName());
            if (target == null) continue;

            if (invert) {
                if (km == o.keyUp) target = orig.getOrDefault(o.keyDown.getName(), target);
                else if (km == o.keyDown) target = orig.getOrDefault(o.keyUp.getName(), target);
                else if (km == o.keyLeft) target = orig.getOrDefault(o.keyRight.getName(), target);
                else if (km == o.keyRight) target = orig.getOrDefault(o.keyLeft.getName(), target);
            }

            boolean unbound = target.endsWith(".unknown");
            boolean isMouse = target.startsWith("key.mouse");
            boolean blocked = false;
            if (!unbound) {
                if (isMouse && mouse) {
                    blocked = true;
                } else if (!isMouse && keyboard && km != o.keyChat && km != o.keyCommand) {
                    blocked = true; // чат и команды остаются доступны
                }
            }
            km.setKey(blocked ? InputConstants.UNKNOWN : InputConstants.getKey(target));
        }
        KeyMapping.resetMapping();
        KeyMapping.releaseAll(); // чтобы зажатая в момент смены клавиша не «залипла»
    }
}
