package dev.streamrewards;

import java.util.HashMap;
import java.util.Map;

/** Один пресет: награда на Twitch + действие в игре. Хранится в config/streamrewards.json. */
public class Preset {
    public String id = "";
    public String title = "";
    public int cost = 100;
    public String prompt = "";
    /** Какое действие выполнить (см. Actions). */
    public String action = "";
    /** "any" — работает везде, "singleplayer" — только в одиночной игре. */
    public String scope = "any";
    public boolean enabled = true;
    /** Глобальный кулдаун награды на Twitch, 0 — без кулдауна. */
    public int cooldownSeconds = 0;
    public String color = "#9146FF";
    public Map<String, String> params = new HashMap<>();

    public Preset() {}

    public Preset(String id, String title, int cost, String prompt, String action,
                  String scope, int cooldownSeconds, String color) {
        this.id = id;
        this.title = title;
        this.cost = cost;
        this.prompt = prompt;
        this.action = action;
        this.scope = scope;
        this.cooldownSeconds = cooldownSeconds;
        this.color = color;
    }

    public Preset param(String key, String value) {
        params.put(key, value);
        return this;
    }

    public int intParam(String key, int def) {
        try {
            return Integer.parseInt(params.getOrDefault(key, ""));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean boolParam(String key, boolean def) {
        String v = params.get(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    /** Описание награды для Twitch; {seconds} заменяется на текущую длительность эффекта. */
    public String promptText() {
        String s = prompt == null ? "" : prompt;
        return s.replace("{seconds}", String.valueOf(intParam("seconds", 10)));
    }

    public boolean singleplayerOnly() {
        return "singleplayer".equalsIgnoreCase(scope);
    }
}
