package dev.streamrewards;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Готовые пресеты. После первого запуска их можно менять прямо в config/streamrewards.json. */
public final class Presets {
    private Presets() {}

    public static List<Preset> defaults() {
        List<Preset> l = new ArrayList<>();

        // ---- Работают везде (и на чужом сервере, и в одиночной игре) ----
        l.add(new Preset("drop_held", "Выбросить предмет из руки", 200,
                "Стример выбросит то, что держит в руке", "drop_held", "any", 30, "#F5A623"));

        l.add(new Preset("drop_inventory", "Выкинуть инвентарь", 1000,
                "Стример выкинет ВЕСЬ инвентарь на землю", "drop_inventory", "any", 120, "#E5484D"));

        l.add(new Preset("random_hotbar", "Случайный слот хотбара", 100,
                "Переключим слот хотбара на случайный", "random_hotbar", "any", 15, "#3E63DD"));

        l.add(new Preset("fov_narrow", "Туннельное зрение", 150,
                "Угол обзора станет минимальным на {seconds} сек.", "fov", "any", 60, "#8E4EC6")
                .param("value", "30").param("seconds", "15"));

        l.add(new Preset("flip_camera", "Вверх ногами", 300,
                "Камера стримера перевернётся вверх ногами на {seconds} сек.", "flip_camera", "any", 90, "#F76B15")
                .param("seconds", "10"));

        l.add(new Preset("turn_180", "Развернуться на 180°", 150,
                "Стримера плавно развернёт на 180 градусов", "turn_180", "any", 30, "#12A594")
                .param("ticks", "10"));

        l.add(new Preset("invert_controls", "Инвертировать управление", 400,
                "Управление стримера инвертируется на {seconds} сек. (вперёд — назад, влево — вправо)",
                "invert_controls", "any", 120, "#D6409F")
                .param("seconds", "15"));

        l.add(new Preset("block_mouse", "Заблокировать мышь", 500,
                "Мышь стримера заблокирована на {seconds} сек. (камера и клики не работают)",
                "block_mouse", "any", 120, "#6E56CF")
                .param("seconds", "8"));

        l.add(new Preset("block_keyboard", "Заблокировать клавиатуру", 500,
                "Клавиатура стримера заблокирована на {seconds} сек. (клавиши управления не работают, чат работает)",
                "block_keyboard", "any", 120, "#0090FF")
                .param("seconds", "8"));

        l.add(new Preset("creeper_hiss", "Шипение крипера", 50,
                "Стример услышит шипение крипера", "creeper_hiss", "any", 20, "#46A758"));

        // ---- Только одиночная игра (на сервере награды автоматически ставятся на паузу) ----
        l.add(new Preset("delete_held_item", "Удалить предмет из руки", 800,
                "Предмет в руке исчезнет навсегда", "delete_held_item", "singleplayer", 60, "#E5484D"));

        l.add(new Preset("delete_random_item", "Удалить случайный предмет", 500,
                "Один случайный предмет из инвентаря исчезнет", "delete_random_item", "singleplayer", 60, "#E54666"));

        l.add(new Preset("delete_armor", "Удалить броню", 1500,
                "Вся надетая броня исчезнет", "delete_armor", "singleplayer", 300, "#CE2C31"));

        l.add(new Preset("delete_chunk_above", "Стереть всё над чанком", 3000,
                "Всё в этом чанке от уровня стримера и выше исчезнет (есть отмена командой)",
                "delete_chunk", "singleplayer", 600, "#900B0B")
                .param("depth_below", "0").param("skip_block_entities", "true"));

        return l;
    }

    private static Map<String, String> defaultTitles;

    /** Название пресета по умолчанию (то, с чем он создаётся). */
    public static synchronized String defaultTitle(String id) {
        if (defaultTitles == null) {
            defaultTitles = new HashMap<>();
            for (Preset d : defaults()) defaultTitles.put(d.id, d.title);
        }
        return defaultTitles.get(id);
    }

    /**
     * Название для показа в игре. Если пресет не переименован, берём перевод на текущий язык Minecraft;
     * если ты задал своё название в конфиге, показываем его как есть.
     */
    public static String displayName(Preset p) {
        if (I18n.has("preset." + p.id) && p.title.equals(defaultTitle(p.id))) {
            return I18n.str("preset." + p.id);
        }
        return p.title;
    }
}
