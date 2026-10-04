package dev.streamrewards;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Меню настроек наград: включить/выключить, длительность эффекта, цена, кулдаун,
 * проверка эффекта, применение изменений на Twitch, выход из аккаунта.
 * Открывается командой /streamrewards. Все тексты переводятся вместе с языком Minecraft.
 */
public class RewardsScreen extends Screen {
    private static final String CREDIT_TEXT = "Made by permintion";
    private static final String CREDIT_URL = "https://t.me/permintion_of";

    private static final int ROW_H = 30;
    /** Верх панели (под строкой статуса). */
    private static final int PANEL_TOP = 38;
    /** Где начинаются строки наград (под заголовками колонок). */
    private static final int HEADER_H = 58;
    private static final int FOOTER_H = 52;

    private static final int COLOR_PANEL = 0xD0101018;
    private static final int COLOR_ROW = 0x38FFFFFF;
    private static final int COLOR_ROW_OFF = 0x18FFFFFF;

    private final Screen parent;
    private int page = 0;
    private int perPage = 4;
    private int panelLeft;
    private int panelWidth;
    private String status = "";

    private Button logoutBtn;
    /** Сколько тиков ещё ждём подтверждения выхода (0 — не ждём). */
    private int logoutConfirm = 0;

    public RewardsScreen(Screen parent) {
        super(Component.literal("Stream Rewards"));
        this.parent = parent;
    }

    // ---------- Построение интерфейса ----------

    @Override
    protected void init() {
        panelWidth = Math.min(this.width - 24, 480);
        panelLeft = (this.width - panelWidth) / 2;
        perPage = Math.max(1, (this.height - HEADER_H - FOOTER_H) / ROW_H);

        List<Preset> list = Config.INSTANCE.presets;
        int pages = pageCount();
        if (page >= pages) page = pages - 1;

        for (int i = visibleStart(); i < visibleEnd(); i++) {
            Preset p = list.get(i);
            int y = rowY(i);

            addRenderableWidget(Button.builder(toggleLabel(p), btn -> {
                p.enabled = !p.enabled;
                btn.setMessage(toggleLabel(p));
            }).bounds(panelLeft + 6, y + 5, 46, 20).build());

            if (hasDuration(p)) {
                addNumberBox(durX(), y + 5, 36, 4, p.intParam("seconds", 10), 1,
                        v -> p.params.put("seconds", String.valueOf(Math.min(v, 3600))), "Duration");
            }
            addNumberBox(costX(), y + 5, 46, 6, p.cost, 1, v -> p.cost = v, "Cost");
            addNumberBox(cooldownX(), y + 5, 38, 5, p.cooldownSeconds, 0, v -> p.cooldownSeconds = v, "Cooldown");

            addRenderableWidget(Button.builder(I18n.tr("menu.test"), btn -> test(p))
                    .bounds(testX(), y + 5, 40, 20).build());
        }

        // верхняя строка: выход из аккаунта (слева) и переключатель чата (справа)
        logoutBtn = Button.builder(logoutLabel(), btn -> onLogoutPressed())
                .bounds(panelLeft, 2, 130, 18).build();
        logoutBtn.active = Config.INSTANCE.loggedIn();
        addRenderableWidget(logoutBtn);

        addRenderableWidget(Button.builder(chatLabel(), btn -> {
            Config.INSTANCE.chatAnnounce = !Config.INSTANCE.chatAnnounce;
            btn.setMessage(chatLabel());
        }).bounds(panelLeft + panelWidth - 96, 2, 96, 18).build());

        // нижняя строка кнопок
        int fy = this.height - 36;

        Button prev = Button.builder(Component.literal("<"), btn -> {
            page--;
            this.rebuildWidgets();
        }).bounds(panelLeft, fy, 24, 20).build();
        prev.active = page > 0;
        addRenderableWidget(prev);

        Button next = Button.builder(Component.literal(">"), btn -> {
            page++;
            this.rebuildWidgets();
        }).bounds(panelLeft + 28, fy, 24, 20).build();
        next.active = page < pages - 1;
        addRenderableWidget(next);

        addRenderableWidget(Button.builder(I18n.tr("menu.apply"), btn -> apply())
                .bounds(this.width / 2 - 85, fy, 170, 20).build());

        addRenderableWidget(Button.builder(I18n.tr("menu.done"), btn -> onClose())
                .bounds(panelLeft + panelWidth - 80, fy, 80, 20).build());

        // подпись автора — в правом нижнем углу экрана, ниже основных кнопок
        addRenderableWidget(Button.builder(Component.literal(CREDIT_TEXT), btn -> Links.open(CREDIT_URL))
                .bounds(this.width - 122, this.height - 16, 118, 14).build());
    }

    private void addNumberBox(int x, int y, int w, int maxLen, int value, int min, IntConsumer onChange, String hint) {
        EditBox box = new EditBox(this.font, x, y, w, 20, Component.literal(hint));
        box.setMaxLength(maxLen);
        box.setFilter(s -> s.matches("\\d*"));
        box.setValue(String.valueOf(value));
        box.setResponder(s -> {
            if (s.isEmpty()) return;
            try {
                onChange.accept(Math.max(min, Integer.parseInt(s)));
            } catch (NumberFormatException ignored) {
            }
        });
        addRenderableWidget(box);
    }

    private static boolean hasDuration(Preset p) {
        return p.params.containsKey("seconds");
    }

    private static Component onOff(boolean on) {
        return I18n.tr(on ? "menu.on" : "menu.off");
    }

    private static Component toggleLabel(Preset p) {
        return onOff(p.enabled).copy().withStyle(p.enabled ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private static Component chatLabel() {
        boolean on = Config.INSTANCE.chatAnnounce;
        return I18n.tr("menu.chat", onOff(on)).copy().withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private Component logoutLabel() {
        return logoutConfirm > 0
                ? I18n.tr("menu.logout.confirm").copy().withStyle(ChatFormatting.RED)
                : I18n.tr("menu.logout");
    }

    // ---------- Геометрия ----------

    private int pageCount() {
        return Math.max(1, (Config.INSTANCE.presets.size() + perPage - 1) / perPage);
    }

    private int visibleStart() {
        return page * perPage;
    }

    private int visibleEnd() {
        return Math.min(Config.INSTANCE.presets.size(), visibleStart() + perPage);
    }

    private int rowY(int index) {
        return HEADER_H + (index - visibleStart()) * ROW_H;
    }

    private int durX() {
        return panelLeft + panelWidth - 178;
    }

    private int costX() {
        return panelLeft + panelWidth - 138;
    }

    private int cooldownX() {
        return panelLeft + panelWidth - 88;
    }

    private int testX() {
        return panelLeft + panelWidth - 46;
    }

    // ---------- Отрисовка ----------

    /** Фон и плашки рисуем здесь, чтобы они были под кнопками и полями ввода. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);

        g.fill(panelLeft - 6, PANEL_TOP, panelLeft + panelWidth + 6, this.height - FOOTER_H + 12, COLOR_PANEL);

        List<Preset> list = Config.INSTANCE.presets;
        for (int i = visibleStart(); i < visibleEnd(); i++) {
            Preset p = list.get(i);
            int y = rowY(i);
            g.fill(panelLeft, y + 1, panelLeft + panelWidth, y + ROW_H - 1,
                    p.enabled ? COLOR_ROW : COLOR_ROW_OFF);
            g.fill(panelLeft, y + 1, panelLeft + 3, y + ROW_H - 1, accent(p));
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        g.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);

        // строка статуса над панелью (панель начинается ниже, поэтому текст на неё не заходит)
        Config cfg = Config.INSTANCE;
        String twitch;
        int twitchColor;
        if (!cfg.loggedIn()) {
            twitch = I18n.str("menu.twitch.loggedout");
            twitchColor = 0xFFFFAA00;
        } else if (EventSubClient.isConnected()) {
            twitch = I18n.str("menu.twitch.connected", cfg.login);
            twitchColor = 0xFF55FF55;
        } else {
            twitch = I18n.str("menu.twitch.disconnected", cfg.login);
            twitchColor = 0xFFFF5555;
        }
        g.drawString(this.font, twitch, panelLeft, 24, twitchColor);

        boolean singleplayer = inSingleplayer();
        String mode = I18n.str(singleplayer ? "menu.mode.singleplayer" : "menu.mode.server");
        g.drawString(this.font, mode, panelLeft + panelWidth - this.font.width(mode), 24,
                singleplayer ? 0xFF55FFFF : 0xFFFFAA00);

        // заголовки колонок (внутри панели, над строками)
        int labelsY = PANEL_TOP + 6;
        g.drawString(this.font, I18n.str("menu.col.duration"), durX() + 2, labelsY, 0xFFAAAAAA);
        g.drawString(this.font, I18n.str("menu.col.cost"), costX() + 4, labelsY, 0xFFAAAAAA);
        g.drawString(this.font, I18n.str("menu.col.cooldown"), cooldownX() + 2, labelsY, 0xFFAAAAAA);

        List<Preset> list = cfg.presets;
        for (int i = visibleStart(); i < visibleEnd(); i++) {
            Preset p = list.get(i);
            int y = rowY(i);
            int tx = panelLeft + 60;
            g.drawString(this.font, Presets.displayName(p), tx, y + 5, p.enabled ? 0xFFFFFFFF : 0xFF777777);

            String scope;
            int scopeColor;
            if (p.singleplayerOnly()) {
                scope = I18n.str(singleplayer ? "menu.scope.sp" : "menu.scope.sp.paused");
                scopeColor = singleplayer ? 0xFFFFC857 : 0xFF8A7A50;
            } else {
                scope = I18n.str("menu.scope.anywhere");
                scopeColor = 0xFF6FCF97;
            }
            g.drawString(this.font, scope, tx, y + 16, p.enabled ? scopeColor : 0xFF666666);

            if (!hasDuration(p)) {
                g.drawString(this.font, "—", durX() + 15, y + 11, 0xFF666666);
            }
        }

        g.drawString(this.font, I18n.str("menu.page", page + 1, pageCount()),
                panelLeft + 58, this.height - 30, 0xFFAAAAAA);

        if (!status.isEmpty()) {
            g.drawString(this.font, status, panelLeft, this.height - 49, 0xFFFFFFFF);
        }
    }

    private static int accent(Preset p) {
        try {
            return 0xFF000000 | Integer.parseInt(p.color.replace("#", ""), 16);
        } catch (Exception e) {
            return 0xFF9146FF;
        }
    }

    private static boolean inSingleplayer() {
        return Minecraft.getInstance().getSingleplayerServer() != null;
    }

    // ---------- Действия ----------

    private void apply() {
        Config.save();
        if (!Config.INSTANCE.loggedIn()) {
            status = I18n.str("need_login");
            return;
        }
        status = I18n.str("menu.status.syncing");
        Net.EXEC.execute(() -> {
            String result;
            try {
                result = RewardSync.sync();
                Minecraft mc = Minecraft.getInstance();
                RewardSync.applyContext(mc.getSingleplayerServer() != null, mc.level != null);
            } catch (Exception e) {
                StreamRewardsClient.LOG.error("Ошибка синхронизации из меню", e);
                result = I18n.str("menu.status.error", String.valueOf(e.getMessage()));
            }
            final String text = result;
            Minecraft.getInstance().execute(() -> this.status = text);
        });
    }

    /** Выход из аккаунта с подтверждением: первое нажатие просит подтвердить, второе выходит. */
    private void onLogoutPressed() {
        if (!Config.INSTANCE.loggedIn()) return;
        if (logoutConfirm == 0) {
            logoutConfirm = 80; // 4 секунды на подтверждение
            logoutBtn.setMessage(logoutLabel());
            return;
        }
        logoutConfirm = 0;
        logoutBtn.setMessage(logoutLabel());
        logoutBtn.active = false;
        status = I18n.str("menu.status.logging_out");
        Net.EXEC.execute(() -> {
            String result = StreamRewardsClient.logout();
            Minecraft.getInstance().execute(() -> this.status = result);
        });
    }

    /** Закрывает меню и через долю секунды запускает эффект, чтобы его было видно. */
    private void test(Preset p) {
        Config.save();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            status = I18n.str("menu.status.enter_world");
            return;
        }
        onClose();
        ClientTasks.later(5, () -> {
            String name = Presets.displayName(p);
            Redemption red = new Redemption("test", "", name, p.cost, "Test", "");
            boolean ok = RewardRunner.execute(mc, p, red);
            if (ok) RewardRunner.announce(mc, red);
            RewardRunner.toast(mc, I18n.str(ok ? "toast.test.ok" : "toast.test.fail"), name);
        });
    }

    @Override
    public void tick() {
        super.tick();
        if (logoutConfirm > 0 && --logoutConfirm == 0 && logoutBtn != null) {
            logoutBtn.setMessage(logoutLabel());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false; // мир продолжает идти, пока меню открыто
    }

    @Override
    public void onClose() {
        Config.save();
        this.minecraft.setScreen(this.parent);
    }
}
