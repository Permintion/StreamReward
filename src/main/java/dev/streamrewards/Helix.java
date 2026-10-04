package dev.streamrewards;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Запросы к Twitch Helix API. Все методы блокирующие — вызывать из Net.EXEC. */
public final class Helix {
    private static final String BASE = "https://api.twitch.tv/helix";

    private Helix() {}

    public static Net.Resp call(String method, String path, String json) throws Exception {
        for (int attempt = 0; ; attempt++) {
            Config c = Config.INSTANCE;
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
                    .header("Authorization", "Bearer " + c.accessToken)
                    .header("Client-Id", c.clientId)
                    .timeout(Duration.ofSeconds(15));
            if (json != null) {
                b.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            Net.Resp r = Net.send(b.build());
            if (r.status() == 401 && attempt == 0 && TwitchAuth.refresh()) continue;
            return r;
        }
    }

    /** Награды, созданные этим приложением: название -> id. */
    public static Map<String, String> getManageableRewards() throws Exception {
        String id = Config.INSTANCE.broadcasterId;
        Net.Resp r = call("GET", "/channel_points/custom_rewards?broadcaster_id=" + id
                + "&only_manageable_rewards=true", null);
        if (r.status() != 200) throw new IllegalStateException("Twitch " + r.status() + ": " + r.body());
        Map<String, String> out = new HashMap<>();
        JsonArray data = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("data");
        for (JsonElement e : data) {
            JsonObject o = e.getAsJsonObject();
            out.put(o.get("title").getAsString(), o.get("id").getAsString());
        }
        return out;
    }

    /** Создаёт награду, возвращает её id (или null при ошибке). */
    public static String createReward(Preset p) throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("title", p.title);
        o.addProperty("cost", p.cost);
        o.addProperty("prompt", p.promptText());
        o.addProperty("is_enabled", true);
        o.addProperty("background_color", p.color);
        if (p.cooldownSeconds > 0) {
            o.addProperty("is_global_cooldown_enabled", true);
            o.addProperty("global_cooldown_seconds", p.cooldownSeconds);
        }
        Net.Resp r = call("POST", "/channel_points/custom_rewards?broadcaster_id="
                + Config.INSTANCE.broadcasterId, o.toString());
        if (r.status() != 200) {
            StreamRewardsClient.LOG.warn("Не удалось создать награду «{}»: {} {}", p.title, r.status(), r.body());
            return null;
        }
        return JsonParser.parseString(r.body()).getAsJsonObject()
                .getAsJsonArray("data").get(0).getAsJsonObject().get("id").getAsString();
    }

    /** Обновляет существующую награду по настройкам пресета (название, цена, кулдаун, вкл/выкл). */
    public static void updateReward(String rewardId, Preset p) throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("title", p.title);
        o.addProperty("cost", Math.max(1, p.cost));
        o.addProperty("prompt", p.promptText());
        o.addProperty("is_enabled", p.enabled);
        o.addProperty("background_color", p.color);
        o.addProperty("is_global_cooldown_enabled", p.cooldownSeconds > 0);
        if (p.cooldownSeconds > 0) o.addProperty("global_cooldown_seconds", p.cooldownSeconds);
        Net.Resp r = call("PATCH", "/channel_points/custom_rewards?broadcaster_id="
                + Config.INSTANCE.broadcasterId + "&id=" + rewardId, o.toString());
        if (r.status() != 200) {
            StreamRewardsClient.LOG.warn("Не удалось обновить награду «{}»: {} {}", p.title, r.status(), r.body());
        }
    }

    public static void deleteReward(String rewardId) throws Exception {
        Net.Resp r = call("DELETE", "/channel_points/custom_rewards?broadcaster_id="
                + Config.INSTANCE.broadcasterId + "&id=" + rewardId, null);
        if (r.status() != 204) {
            StreamRewardsClient.LOG.warn("Не удалось удалить награду: {} {}", r.status(), r.body());
        }
    }

    public static void setPaused(String rewardId, boolean paused) throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("is_paused", paused);
        Net.Resp r = call("PATCH", "/channel_points/custom_rewards?broadcaster_id="
                + Config.INSTANCE.broadcasterId + "&id=" + rewardId, o.toString());
        if (r.status() != 200) {
            StreamRewardsClient.LOG.warn("Не удалось поставить награду на паузу: {} {}", r.status(), r.body());
        }
    }

    /** FULFILLED — выполнено, CANCELED — отмена (баллы возвращаются зрителю). */
    public static void setRedemptionStatus(Redemption red, boolean fulfilled) throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("status", fulfilled ? "FULFILLED" : "CANCELED");
        Net.Resp r = call("PATCH", "/channel_points/custom_rewards/redemptions?id=" + red.id()
                + "&broadcaster_id=" + Config.INSTANCE.broadcasterId
                + "&reward_id=" + red.rewardId(), o.toString());
        if (r.status() != 200) {
            StreamRewardsClient.LOG.warn("Не удалось обновить статус покупки: {} {}", r.status(), r.body());
        }
    }

    public static boolean subscribeRedemptions(String sessionId) throws Exception {
        JsonObject cond = new JsonObject();
        cond.addProperty("broadcaster_user_id", Config.INSTANCE.broadcasterId);
        JsonObject transport = new JsonObject();
        transport.addProperty("method", "websocket");
        transport.addProperty("session_id", sessionId);
        JsonObject o = new JsonObject();
        o.addProperty("type", "channel.channel_points_custom_reward_redemption.add");
        o.addProperty("version", "1");
        o.add("condition", cond);
        o.add("transport", transport);
        Net.Resp r = call("POST", "/eventsub/subscriptions", o.toString());
        if (r.status() != 202) {
            StreamRewardsClient.LOG.warn("Подписка на покупки не удалась: {} {}", r.status(), r.body());
            return false;
        }
        return true;
    }
}
