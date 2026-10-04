package dev.streamrewards;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Настройки мода: config/streamrewards.json.
 * ВНИМАНИЕ: в файле лежат токены доступа Twitch — не показывай его на стриме!
 */
public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("streamrewards.json");

    /** Версия формата конфига — нужна, чтобы при обновлении мода сами добавлялись/убирались пресеты. */
    private static final int CURRENT_VERSION = 4;

    public static Config INSTANCE = new Config();

    public int configVersion = 0;

    public String clientId = "u9u0amfc8ywgi5947kih8o4w9uab5r";
    public String accessToken = "";
    public String refreshToken = "";
    public String broadcasterId = "";
    public String login = "";
    /** Минимальная пауза между эффектами, секунды. */
    public int minGapSeconds = 2;
    /** Писать в чат игры, кто какую награду купил. */
    public boolean chatAnnounce = true;
    /**
     * Шаблон сообщения: можно использовать {user}, {reward} и {cost}.
     * Пустая строка — берётся шаблон на текущем языке Minecraft.
     */
    public String chatFormat = "";
    public List<Preset> presets = Presets.defaults();
    /** id пресета -> id награды на Twitch. */
    public Map<String, String> rewardIds = new HashMap<>();
    /** Исходные клавиши движения на время эффекта «Инвертировать управление» (для восстановления после сбоя). */
    public Map<String, String> keyBackup = null;

    public static synchronized void load() {
        boolean existed = Files.exists(PATH);
        if (existed) {
            try (Reader r = Files.newBufferedReader(PATH)) {
                Config c = GSON.fromJson(r, Config.class);
                if (c != null) INSTANCE = c;
            } catch (Exception e) {
                StreamRewardsClient.LOG.error("Не удалось прочитать конфиг, используются значения по умолчанию", e);
            }
        }
        if (INSTANCE.presets == null) INSTANCE.presets = Presets.defaults();
        if (INSTANCE.rewardIds == null) INSTANCE.rewardIds = new HashMap<>();
        if (!existed) INSTANCE.configVersion = CURRENT_VERSION;
        migrate(INSTANCE);
        save();
    }

    /** Обновляет старые конфиги под новую версию мода. */
    private static void migrate(Config c) {
        if (c.configVersion < 2) {
            // v2: убран «Рыбий глаз», добавлены «Вверх ногами» и «Развернуться на 180°».
            // Награда «Рыбий глаз» на Twitch удалится при следующем /streamrewards sync.
            c.presets.removeIf(p -> "fov_wide".equals(p.id));
            addMissing(c, "flip_camera", "turn_180");
            c.configVersion = 2;
        }
        if (c.configVersion < 3) {
            // v3: добавлено «Инвертировать управление»; описания наград теперь учитывают длительность.
            addMissing(c, "invert_controls");
            for (Preset def : Presets.defaults()) {
                if (!def.prompt.contains("{seconds}")) continue;
                for (Preset p : c.presets) {
                    if (p.id.equals(def.id)) p.prompt = def.prompt;
                }
            }
            c.configVersion = 3;
        }
        if (c.configVersion < 4) {
            // v4: «Заблокировать мышь/клавиатуру», перевод интерфейса. Шаблон чата по умолчанию
            // теперь зависит от языка Minecraft (пустая строка).
            addMissing(c, "block_mouse", "block_keyboard");
            if ("{user} Купил награду {reward}".equals(c.chatFormat)) c.chatFormat = "";
            c.configVersion = 4;
        }
    }

    private static void addMissing(Config c, String... ids) {
        for (Preset def : Presets.defaults()) {
            for (String id : ids) {
                if (def.id.equals(id) && c.presets.stream().noneMatch(p -> p.id.equals(id))) {
                    c.presets.add(def);
                }
            }
        }
    }

    public static synchronized void save() {
        try (Writer w = Files.newBufferedWriter(PATH)) {
            GSON.toJson(INSTANCE, w);
        } catch (IOException e) {
            StreamRewardsClient.LOG.error("Не удалось сохранить конфиг", e);
        }
    }

    public boolean loggedIn() {
        return !accessToken.isEmpty() && !broadcasterId.isEmpty();
    }
}
