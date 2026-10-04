package dev.streamrewards;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class StreamRewardsClient implements ClientModInitializer {
    public static final String MOD_ID = "streamrewards";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        Config.load();
        ServerTasks.init();

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            ClientTasks.tick();
            ControlsEffects.tick();
            RewardRunner.tick(mc);
        });

        // Если игра закрылась посреди эффекта управления — возвращаем клавиши на место
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> ControlsEffects.restore());

        // Зашли в мир / вышли — ставим на паузу награды, которые сейчас не сработают
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) ->
                RewardSync.applyContextAsync(mc.getSingleplayerServer() != null, true));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            Actions.cleanup();
            RewardSync.applyContextAsync(false, false);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            Actions.cleanup();
            RewardSync.applyContext(false, false); // перед закрытием игры ставим всё на паузу
            EventSubClient.stop();
        });

        registerCommands();

        // Если уже входили раньше — подключаемся автоматически
        if (Config.INSTANCE.loggedIn()) {
            Net.EXEC.execute(() -> {
                if (TwitchAuth.ensureValid()) {
                    EventSubClient.start();
                    LOG.info("Подключено к Twitch как {}", Config.INSTANCE.login);
                } else {
                    LOG.warn("Токен Twitch недействителен — выполни /streamrewards login");
                }
            });
        }
    }

    // ================= Команды =================

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("streamrewards")
                        .executes(ctx -> {
                            openMenu();
                            return 1;
                        })
                        .then(literal("menu").executes(ctx -> {
                            openMenu();
                            return 1;
                        }))
                        .then(literal("login").executes(ctx -> {
                            login(ctx.getSource());
                            return 1;
                        }))
                        .then(literal("logout").executes(ctx -> {
                            logoutCommand(ctx.getSource());
                            return 1;
                        }))
                        .then(literal("sync").executes(ctx -> {
                            sync(ctx.getSource());
                            return 1;
                        }))
                        .then(literal("connect").executes(ctx -> {
                            connect(ctx.getSource());
                            return 1;
                        }))
                        .then(literal("disconnect").executes(ctx -> {
                            EventSubClient.stop();
                            msg(ctx.getSource(), I18n.str("cmd.disconnected"));
                            return 1;
                        }))
                        .then(literal("status").executes(ctx -> {
                            status(ctx.getSource());
                            return 1;
                        }))
                        .then(literal("undo").executes(ctx -> undo(ctx.getSource())))
                        .then(literal("test").then(argument("action", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Actions.names(), b))
                                .executes(ctx -> test(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "action")))))));
    }

    public static void msg(FabricClientCommandSource src, String text) {
        Minecraft.getInstance().execute(() -> src.sendFeedback(Component.literal(text)));
    }

    /** Меню открываем с небольшой задержкой: иначе закрывающийся чат сразу закроет и его. */
    private static void openMenu() {
        ClientTasks.later(2, () -> Minecraft.getInstance().setScreen(new RewardsScreen(null)));
    }

    private static void login(FabricClientCommandSource src) {
        Net.EXEC.execute(() -> {
            try {
                TwitchAuth.DeviceCode dc = TwitchAuth.start();
                msg(src, I18n.str("cmd.login.open", dc.verificationUri(), dc.userCode()));
                msg(src, I18n.str("cmd.login.wait", dc.expiresIn() / 60));
                if (TwitchAuth.waitForAuthorization(dc)) {
                    msg(src, I18n.str("cmd.login.ok", Config.INSTANCE.login));
                    EventSubClient.start();
                } else {
                    msg(src, I18n.str("cmd.login.fail"));
                }
            } catch (Exception e) {
                LOG.error("Ошибка входа", e);
                msg(src, I18n.str("cmd.login.error", String.valueOf(e.getMessage())));
            }
        });
    }

    private static void logoutCommand(FabricClientCommandSource src) {
        Net.EXEC.execute(() -> msg(src, logout()));
    }

    /**
     * Выход из аккаунта Twitch: награды на канале ставятся на паузу, токен отзывается,
     * локальные данные входа стираются. Блокирующий метод — вызывать не из потока игры.
     */
    public static String logout() {
        Config c = Config.INSTANCE;
        if (c.accessToken.isEmpty() && !c.loggedIn()) return I18n.str("logout.not_logged");
        String name = c.login.isEmpty() ? "Twitch" : c.login;
        EventSubClient.stop();
        try {
            RewardSync.pauseAll(); // чтобы зрители не покупали награды, пока мод не слушает канал
        } catch (Exception e) {
            LOG.warn("Не удалось поставить награды на паузу при выходе", e);
        }
        TwitchAuth.revoke();
        c.accessToken = "";
        c.refreshToken = "";
        c.broadcasterId = "";
        c.login = "";
        c.rewardIds.clear();
        Config.save();
        return I18n.str("logout.done", name);
    }

    private static void sync(FabricClientCommandSource src) {
        if (!Config.INSTANCE.loggedIn()) {
            msg(src, I18n.str("need_login"));
            return;
        }
        Net.EXEC.execute(() -> {
            try {
                msg(src, RewardSync.sync());
                Minecraft mc = Minecraft.getInstance();
                RewardSync.applyContext(mc.getSingleplayerServer() != null, mc.level != null);
            } catch (Exception e) {
                LOG.error("Ошибка синхронизации", e);
                msg(src, I18n.str("cmd.sync.error", String.valueOf(e.getMessage())));
            }
        });
    }

    private static void connect(FabricClientCommandSource src) {
        if (!Config.INSTANCE.loggedIn()) {
            msg(src, I18n.str("need_login"));
            return;
        }
        Net.EXEC.execute(() -> {
            if (TwitchAuth.ensureValid()) {
                EventSubClient.start();
                msg(src, I18n.str("cmd.connect.connecting", Config.INSTANCE.login));
            } else {
                msg(src, I18n.str("cmd.connect.invalid"));
            }
        });
    }

    private static void status(FabricClientCommandSource src) {
        Config c = Config.INSTANCE;
        msg(src, c.loggedIn() ? I18n.str("cmd.status.in", c.login) : I18n.str("cmd.status.out"));
        msg(src, I18n.str("cmd.status.listening",
                I18n.str(EventSubClient.isConnected() ? "yes" : "no")));
        msg(src, I18n.str("cmd.status.rewards", c.rewardIds.size(), RewardRunner.pending()));
        Minecraft mc = Minecraft.getInstance();
        msg(src, I18n.str(mc.getSingleplayerServer() != null ? "cmd.status.mode.sp" : "cmd.status.mode.server"));
    }

    private static int undo(FabricClientCommandSource src) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer s = mc.getSingleplayerServer();
        if (s == null) {
            msg(src, I18n.str("cmd.undo.sp_only"));
            return 0;
        }
        s.execute(() -> msg(src, I18n.str(ServerTasks.undo() ? "cmd.undo.restoring" : "cmd.undo.nothing")));
        return 1;
    }

    private static int test(FabricClientCommandSource src, String action) {
        Minecraft mc = Minecraft.getInstance();
        Preset p = Config.INSTANCE.presets.stream()
                .filter(x -> x.action.equals(action))
                .findFirst()
                .orElse(null);
        if (p == null) {
            msg(src, I18n.str("cmd.test.unknown", action));
            return 0;
        }
        String name = Presets.displayName(p);
        Redemption red = new Redemption("test", "", name, p.cost, "Test", "");
        boolean ok = RewardRunner.execute(mc, p, red);
        if (ok) RewardRunner.announce(mc, red);
        msg(src, ok ? I18n.str("cmd.test.ok", name) : I18n.str("cmd.test.fail"));
        return ok ? 1 : 0;
    }
}
