package dev.streamrewards;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Создание наград на Twitch по пресетам и автопауза в зависимости от того, где играет стример. */
public final class RewardSync {
    private static final Map<String, Boolean> PAUSED = new ConcurrentHashMap<>();

    private RewardSync() {}

    /**
     * Приводит награды на Twitch в соответствие с пресетами: создаёт недостающие,
     * обновляет цену/кулдаун/вкл-выкл у существующих, удаляет награды убранных пресетов.
     * Блокирующий метод. Возвращает отчёт для чата.
     */
    public static String sync() throws Exception {
        Config cfg = Config.INSTANCE;
        Map<String, String> existing = Helix.getManageableRewards();
        Set<String> existingIds = new HashSet<>(existing.values());
        Set<String> presetIds = new HashSet<>();
        int created = 0;
        int updated = 0;
        int removed = 0;
        int failed = 0;

        for (Preset p : cfg.presets) {
            presetIds.add(p.id);
            String id = cfg.rewardIds.get(p.id);
            if (id != null && !existingIds.contains(id)) {
                // награду удалили вручную на Twitch
                cfg.rewardIds.remove(p.id);
                id = null;
            }
            if (id == null) {
                String byTitle = existing.get(p.title);
                if (byTitle != null) {
                    id = byTitle;
                    cfg.rewardIds.put(p.id, id);
                }
            }
            if (id == null) {
                if (!p.enabled) continue; // выключенную награду не создаём
                String newId = Helix.createReward(p);
                if (newId != null) {
                    cfg.rewardIds.put(p.id, newId);
                    created++;
                } else {
                    failed++;
                }
                continue;
            }
            Helix.updateReward(id, p);
            updated++;
        }

        // награды пресетов, которых больше нет (например, убранный «Рыбий глаз»)
        Iterator<Map.Entry<String, String>> it = cfg.rewardIds.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            if (presetIds.contains(e.getKey())) continue;
            try {
                Helix.deleteReward(e.getValue());
                removed++;
            } catch (Exception ex) {
                StreamRewardsClient.LOG.warn("Не удалось удалить старую награду", ex);
            }
            it.remove();
        }

        Config.save();
        PAUSED.clear();
        return I18n.str("sync.report", created, updated, removed)
                + (failed > 0 ? I18n.str("sync.report.failed", failed) : "");
    }

    /**
     * Ставит на паузу награды, которые сейчас выполнить нельзя:
     * — стример не в мире — все награды;
     * — стример на чужом сервере — награды «только одиночная игра».
     */
    public static void applyContext(boolean singleplayer, boolean inWorld) {
        Config cfg = Config.INSTANCE;
        if (!cfg.loggedIn()) return;
        for (Preset p : cfg.presets) {
            String id = cfg.rewardIds.get(p.id);
            if (id == null || !p.enabled) continue;
            boolean paused = !inWorld || (p.singleplayerOnly() && !singleplayer);
            Boolean prev = PAUSED.put(p.id, paused);
            if (prev != null && prev == paused) continue;
            try {
                Helix.setPaused(id, paused);
            } catch (Exception e) {
                StreamRewardsClient.LOG.warn("Не удалось изменить паузу награды " + p.title, e);
            }
        }
    }

    /** Ставит на паузу все награды (например, перед выходом из аккаунта). */
    public static void pauseAll() {
        PAUSED.clear();
        applyContext(false, false);
    }

    public static void applyContextAsync(boolean singleplayer, boolean inWorld) {
        Net.EXEC.execute(() -> applyContext(singleplayer, inWorld));
    }
}
