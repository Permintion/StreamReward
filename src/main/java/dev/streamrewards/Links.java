package dev.streamrewards;

import java.util.Locale;

/** Открытие ссылки в браузере без обращения к внутренностям Minecraft. */
public final class Links {
    private Links() {}

    public static void open(String url) {
        Net.EXEC.execute(() -> {
            try {
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                ProcessBuilder pb;
                if (os.contains("win")) {
                    pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
                } else if (os.contains("mac")) {
                    pb = new ProcessBuilder("open", url);
                } else {
                    pb = new ProcessBuilder("xdg-open", url);
                }
                pb.start();
            } catch (Exception e) {
                StreamRewardsClient.LOG.warn("Не удалось открыть ссылку {}", url, e);
            }
        });
    }
}
