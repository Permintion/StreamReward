package dev.streamrewards;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Очередь покупок: берёт по одной, выполняет действие, сообщает Twitch результат. */
public final class RewardRunner {
    private static final Queue<Redemption> QUEUE = new ConcurrentLinkedQueue<>();
    private static int wait;

    private RewardRunner() {}

    public static void enqueue(Redemption r) {
        QUEUE.add(r);
    }

    public static int pending() {
        return QUEUE.size();
    }

    /** Вызывается каждый клиентский тик. */
    public static void tick(Minecraft mc) {
        if (wait > 0) {
            wait--;
            return;
        }
        Redemption r = QUEUE.poll();
        if (r == null) return;

        Preset p = findPreset(r.rewardId());
        if (p == null) return; // награда не из нашего списка — игнорируем

        boolean ok = p.enabled && mc.player != null && mc.level != null && execute(mc, p, r);

        final boolean result = ok;
        Net.EXEC.execute(() -> {
            try {
                Helix.setRedemptionStatus(r, result);
            } catch (Exception e) {
                StreamRewardsClient.LOG.warn("Не удалось сообщить Twitch результат", e);
            }
        });

        if (ok) {
            toast(mc, r.userName(), p.title);
            announce(mc, r);
            wait = Math.max(0, Config.INSTANCE.minGapSeconds) * 20;
        } else {
            toast(mc, I18n.str("toast.cancelled.title"),
                    I18n.str("toast.cancelled.msg", Presets.displayName(p)));
        }
    }

    /** Выполняет действие пресета. true — эффект применён. */
    public static boolean execute(Minecraft mc, Preset p, Redemption r) {
        Actions.Action a = Actions.get(p.action);
        if (a == null) {
            StreamRewardsClient.LOG.warn("Неизвестное действие «{}» в пресете {}", p.action, p.id);
            return false;
        }
        if (p.singleplayerOnly() && mc.getSingleplayerServer() == null) return false;
        try {
            return a.run(new Actions.Ctx(mc, p, r));
        } catch (Throwable t) {
            StreamRewardsClient.LOG.error("Ошибка при выполнении действия " + p.action, t);
            return false;
        }
    }

    public static void toast(Minecraft mc, String title, String message) {
        SystemToast.add(mc.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal(title), Component.literal(message));
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(user|reward|cost)}");

    /** Пишет в чат игры (виден только тебе и зрителям на стриме): «{Ник} Купил награду {Название}». */
    public static void announce(Minecraft mc, Redemption r) {
        Config cfg = Config.INSTANCE;
        if (!cfg.chatAnnounce || mc.gui == null) return;
        String format = cfg.chatFormat;
        if (format == null || format.isBlank()) format = I18n.str("chat.format");
        mc.gui.getChat().addMessage(announcement(format, r));
    }

    static Component announcement(String format, Redemption r) {
        MutableComponent out = Component.empty();
        Matcher m = PLACEHOLDER.matcher(format);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                out.append(Component.literal(format.substring(last, m.start())).withStyle(ChatFormatting.GRAY));
            }
            switch (m.group(1)) {
                case "user" -> out.append(Component.literal(r.userName()).withStyle(ChatFormatting.LIGHT_PURPLE));
                case "reward" -> out.append(Component.literal(r.rewardTitle()).withStyle(ChatFormatting.GOLD));
                default -> out.append(Component.literal(String.valueOf(r.cost())).withStyle(ChatFormatting.AQUA));
            }
            last = m.end();
        }
        if (last < format.length()) {
            out.append(Component.literal(format.substring(last)).withStyle(ChatFormatting.GRAY));
        }
        return out;
    }

    private static Preset findPreset(String rewardId) {
        for (Preset p : Config.INSTANCE.presets) {
            String id = Config.INSTANCE.rewardIds.get(p.id);
            if (id != null && id.equals(rewardId)) return p;
        }
        return null;
    }
}
