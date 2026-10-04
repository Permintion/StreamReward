package dev.streamrewards;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletionStage;

/** Слушает покупки наград через Twitch EventSub (WebSocket). */
public final class EventSubClient implements WebSocket.Listener {
    private static final String URL = "wss://eventsub.wss.twitch.tv/ws";
    private static final Deque<String> SEEN = new ArrayDeque<>();

    private static EventSubClient current;
    private static boolean wanted;

    private final EventSubClient replaces;
    private final StringBuilder buf = new StringBuilder();
    private volatile WebSocket ws;
    private volatile boolean connected;

    private EventSubClient(EventSubClient replaces) {
        this.replaces = replaces;
    }

    public static synchronized void start() {
        if (wanted && current != null) return;
        wanted = true;
        open(URL, null);
    }

    public static synchronized void stop() {
        wanted = false;
        EventSubClient c = current;
        current = null;
        if (c != null) c.closeQuietly();
    }

    public static boolean isConnected() {
        EventSubClient c = current;
        return c != null && c.connected;
    }

    private static synchronized void open(String url, EventSubClient replaces) {
        EventSubClient c = new EventSubClient(replaces);
        if (replaces == null) current = c;
        Net.HTTP.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(url), c)
                .whenComplete((w, err) -> {
                    if (err != null) {
                        StreamRewardsClient.LOG.warn("EventSub: не удалось подключиться: {}", err.toString());
                        c.onLost();
                    }
                });
    }

    private void closeQuietly() {
        connected = false;
        WebSocket w = ws;
        if (w != null) {
            try {
                w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Exception ignored) {
            }
        }
    }

    private void onLost() {
        connected = false;
        synchronized (EventSubClient.class) {
            if (!wanted || current != this) return;
        }
        Net.EXEC.execute(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (EventSubClient.class) {
                if (wanted) open(URL, null);
            }
        });
    }

    // ---- WebSocket.Listener ----

    @Override
    public void onOpen(WebSocket webSocket) {
        this.ws = webSocket;
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        buf.append(data);
        if (last) {
            String msg = buf.toString();
            buf.setLength(0);
            try {
                handle(msg);
            } catch (Exception e) {
                StreamRewardsClient.LOG.warn("EventSub: ошибка обработки сообщения", e);
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        StreamRewardsClient.LOG.info("EventSub: соединение закрыто ({} {})", statusCode, reason);
        onLost();
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        StreamRewardsClient.LOG.warn("EventSub: ошибка соединения: {}", error.toString());
        onLost();
    }

    // ---- Обработка сообщений ----

    private void handle(String raw) throws Exception {
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        JsonObject meta = root.getAsJsonObject("metadata");
        String type = meta.get("message_type").getAsString();
        String messageId = meta.get("message_id").getAsString();

        synchronized (SEEN) {
            if (SEEN.contains(messageId)) return;
            SEEN.addLast(messageId);
            while (SEEN.size() > 50) SEEN.removeFirst();
        }

        JsonObject payload = root.has("payload") && root.get("payload").isJsonObject()
                ? root.getAsJsonObject("payload") : new JsonObject();

        switch (type) {
            case "session_welcome" -> {
                String sessionId = payload.getAsJsonObject("session").get("id").getAsString();
                connected = true;
                if (replaces == null) {
                    Net.EXEC.execute(() -> {
                        try {
                            boolean ok = Helix.subscribeRedemptions(sessionId);
                            StreamRewardsClient.LOG.info("EventSub: подписка на покупки {}", ok ? "активна" : "не удалась");
                        } catch (Exception e) {
                            StreamRewardsClient.LOG.warn("EventSub: ошибка подписки", e);
                        }
                    });
                } else {
                    synchronized (EventSubClient.class) {
                        current = this;
                    }
                    replaces.closeQuietly();
                }
            }
            case "session_reconnect" -> {
                JsonElement url = payload.getAsJsonObject("session").get("reconnect_url");
                if (url != null && !url.isJsonNull()) open(url.getAsString(), this);
            }
            case "notification" -> {
                JsonObject event = payload.getAsJsonObject("event");
                JsonObject reward = event.getAsJsonObject("reward");
                Redemption r = new Redemption(
                        event.get("id").getAsString(),
                        reward.get("id").getAsString(),
                        reward.get("title").getAsString(),
                        reward.get("cost").getAsInt(),
                        event.get("user_name").getAsString(),
                        event.has("user_input") ? event.get("user_input").getAsString() : "");
                RewardRunner.enqueue(r);
            }
            default -> {
                // session_keepalive, revocation и т.д. — ничего делать не нужно
            }
        }
    }
}
