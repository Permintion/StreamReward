package dev.streamrewards;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Вход через Twitch по коду (Device Code Flow) — секретный ключ не нужен. */
public final class TwitchAuth {
    public static final String SCOPES = "channel:read:redemptions channel:manage:redemptions";

    public record DeviceCode(String deviceCode, String userCode, String verificationUri,
                             int expiresIn, int interval) {}

    private TwitchAuth() {}

    private static Net.Resp postForm(String url, Map<String, String> form) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(Net.form(form)))
                .build();
        return Net.send(req);
    }

    /** Шаг 1: получить код, который стример введёт на twitch.tv/activate. */
    public static DeviceCode start() throws Exception {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("client_id", Config.INSTANCE.clientId);
        f.put("scopes", SCOPES);
        Net.Resp r = postForm("https://id.twitch.tv/oauth2/device", f);
        if (r.status() != 200) throw new IllegalStateException("Twitch ответил " + r.status() + ": " + r.body());
        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
        return new DeviceCode(
                o.get("device_code").getAsString(),
                o.get("user_code").getAsString(),
                o.get("verification_uri").getAsString(),
                o.get("expires_in").getAsInt(),
                o.has("interval") ? o.get("interval").getAsInt() : 5);
    }

    /** Шаг 2: ждать, пока стример подтвердит вход. Блокирующий метод — вызывать не из потока игры. */
    public static boolean waitForAuthorization(DeviceCode dc) throws Exception {
        long deadline = System.currentTimeMillis() + dc.expiresIn() * 1000L;
        int interval = Math.max(dc.interval(), 1);
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);
            Map<String, String> f = new LinkedHashMap<>();
            f.put("client_id", Config.INSTANCE.clientId);
            f.put("scopes", SCOPES);
            f.put("device_code", dc.deviceCode());
            f.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            Net.Resp r = postForm("https://id.twitch.tv/oauth2/token", f);
            if (r.status() == 200) {
                storeTokens(JsonParser.parseString(r.body()).getAsJsonObject());
                return validate();
            }
            String msg = "";
            try {
                msg = JsonParser.parseString(r.body()).getAsJsonObject().get("message").getAsString();
            } catch (Exception ignored) {
            }
            if (msg.contains("authorization_pending")) continue;
            if (msg.contains("slow_down")) {
                interval += 5;
                continue;
            }
            StreamRewardsClient.LOG.warn("Вход не удался: {} {}", r.status(), r.body());
            return false;
        }
        return false;
    }

    private static void storeTokens(JsonObject o) {
        Config c = Config.INSTANCE;
        c.accessToken = o.get("access_token").getAsString();
        if (o.has("refresh_token")) c.refreshToken = o.get("refresh_token").getAsString();
        Config.save();
    }

    public static synchronized boolean refresh() {
        Config c = Config.INSTANCE;
        if (c.refreshToken.isEmpty()) return false;
        try {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("client_id", c.clientId);
            f.put("grant_type", "refresh_token");
            f.put("refresh_token", c.refreshToken);
            Net.Resp r = postForm("https://id.twitch.tv/oauth2/token", f);
            if (r.status() != 200) {
                StreamRewardsClient.LOG.warn("Не удалось обновить токен: {} {}", r.status(), r.body());
                return false;
            }
            storeTokens(JsonParser.parseString(r.body()).getAsJsonObject());
            return true;
        } catch (Exception e) {
            StreamRewardsClient.LOG.warn("Ошибка обновления токена", e);
            return false;
        }
    }

    /** Отзывает токен на стороне Twitch (при выходе из аккаунта). Ошибки не критичны. */
    public static void revoke() {
        Config c = Config.INSTANCE;
        if (c.accessToken.isEmpty()) return;
        try {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("client_id", c.clientId);
            f.put("token", c.accessToken);
            Net.Resp r = postForm("https://id.twitch.tv/oauth2/revoke", f);
            if (r.status() != 200) {
                StreamRewardsClient.LOG.warn("Не удалось отозвать токен: {} {}", r.status(), r.body());
            }
        } catch (Exception e) {
            StreamRewardsClient.LOG.warn("Ошибка при отзыве токена", e);
        }
    }

    /** Проверяет токен и заполняет id и логин канала. */
    public static boolean validate() throws Exception {
        Config c = Config.INSTANCE;
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://id.twitch.tv/oauth2/validate"))
                .header("Authorization", "OAuth " + c.accessToken)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        Net.Resp r = Net.send(req);
        if (r.status() != 200) return false;
        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
        c.broadcasterId = o.get("user_id").getAsString();
        c.login = o.get("login").getAsString();
        Config.save();
        return true;
    }

    /** Проверить токен, при необходимости обновить. */
    public static boolean ensureValid() {
        try {
            if (Config.INSTANCE.accessToken.isEmpty()) return false;
            if (validate()) return true;
            return refresh() && validate();
        } catch (Exception e) {
            StreamRewardsClient.LOG.warn("Не удалось проверить токен", e);
            return false;
        }
    }
}
