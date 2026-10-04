package dev.streamrewards;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Отложенные действия на стороне клиента (выполняются в потоке игры, раз в тик). */
public final class ClientTasks {
    private static final class Entry {
        int ticks;
        final Runnable task;

        Entry(int ticks, Runnable task) {
            this.ticks = ticks;
            this.task = task;
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private ClientTasks() {}

    /** Выполнить через указанное число тиков (20 тиков = 1 секунда). */
    public static void later(int ticks, Runnable task) {
        ENTRIES.add(new Entry(Math.max(0, ticks), task));
    }

    public static void tick() {
        if (ENTRIES.isEmpty()) return;
        List<Runnable> due = new ArrayList<>();
        Iterator<Entry> it = ENTRIES.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (--e.ticks < 0) {
                due.add(e.task);
                it.remove();
            }
        }
        for (Runnable r : due) {
            try {
                r.run();
            } catch (Throwable t) {
                StreamRewardsClient.LOG.error("Ошибка в отложенной задаче", t);
            }
        }
    }
}
