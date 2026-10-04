package dev.streamrewards;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Все действия, которые могут выполнять награды.
 * Чтобы добавить своё: напиши метод и зарегистрируй его в static-блоке ниже.
 * Метод вызывается в потоке игры и возвращает true, если эффект применён
 * (false — награда будет отменена, баллы вернутся зрителю).
 */
public final class Actions {
    public interface Action {
        boolean run(Ctx ctx);
    }

    public record Ctx(Minecraft mc, Preset preset, Redemption redemption) {}

    private static final Map<String, Action> MAP = new HashMap<>();
    private static final Random RNG = new Random();

    static {
        // --- работают везде ---
        MAP.put("drop_held", Actions::dropHeld);
        MAP.put("drop_inventory", Actions::dropInventory);
        MAP.put("random_hotbar", Actions::randomHotbar);
        MAP.put("fov", Actions::fov);
        MAP.put("creeper_hiss", Actions::creeperHiss);
        MAP.put("flip_camera", Actions::flipCamera);
        MAP.put("turn_180", Actions::turn180);
        MAP.put("invert_controls", Actions::invertControls);
        MAP.put("block_mouse", Actions::blockMouse);
        MAP.put("block_keyboard", Actions::blockKeyboard);
        // --- только одиночная игра ---
        MAP.put("delete_held_item", Actions::deleteHeldItem);
        MAP.put("delete_random_item", Actions::deleteRandomItem);
        MAP.put("delete_armor", Actions::deleteArmor);
        MAP.put("delete_chunk", Actions::deleteChunk);
    }

    private Actions() {}

    public static Action get(String id) {
        return MAP.get(id);
    }

    public static List<String> names() {
        List<String> l = new ArrayList<>(MAP.keySet());
        Collections.sort(l);
        return l;
    }

    // ============ Клиентские действия (работают и на сервере) ============

    private static boolean dropHeld(Ctx c) {
        LocalPlayer p = c.mc().player;
        if (p == null || p.getMainHandItem().isEmpty()) return false;
        p.drop(true); // true = выбросить всю стопку
        return true;
    }

    /** Выбрасывает все слоты инвентаря, по одному раз в 2 тика (чтобы не словить кик за спам пакетами). */
    private static boolean dropInventory(Ctx c) {
        Minecraft mc = c.mc();
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) return false;
        if (p.containerMenu != p.inventoryMenu) p.closeContainer();

        AbstractContainerMenu menu = p.inventoryMenu;
        List<Integer> slots = new ArrayList<>();
        // слоты 5..45: броня, основной инвентарь, хотбар, вторая рука
        for (int i = 5; i < menu.slots.size(); i++) {
            if (menu.slots.get(i).hasItem()) slots.add(i);
        }
        if (slots.isEmpty()) return false;
        Collections.shuffle(slots);

        int k = 0;
        for (int slot : slots) {
            final int s = slot;
            ClientTasks.later(k * 2, () -> {
                LocalPlayer pl = mc.player;
                if (pl == null || mc.gameMode == null) return;
                mc.gameMode.handleInventoryMouseClick(pl.inventoryMenu.containerId, s, 1, ClickType.THROW, pl);
            });
            k++;
        }
        return true;
    }

    private static boolean randomHotbar(Ctx c) {
        LocalPlayer p = c.mc().player;
        if (p == null) return false;
        Inventory inv = p.getInventory();
        int cur = inv.getSelectedSlot();
        int next;
        do {
            next = RNG.nextInt(9);
        } while (next == cur);
        inv.setSelectedSlot(next);
        return true;
    }

    private static int fovOriginal = -1;
    private static int fovActive = 0;

    private static boolean fov(Ctx c) {
        Minecraft mc = c.mc();
        OptionInstance<Integer> opt = mc.options.fov();
        int target = c.preset().intParam("value", 110);
        int seconds = c.preset().intParam("seconds", 15);
        if (fovActive == 0) fovOriginal = opt.get();
        fovActive++;
        opt.set(target);
        ClientTasks.later(seconds * 20, () -> {
            fovActive--;
            if (fovActive <= 0) {
                fovActive = 0;
                restoreFov();
            }
        });
        return true;
    }

    private static void restoreFov() {
        if (fovOriginal >= 0) {
            Minecraft.getInstance().options.fov().set(fovOriginal);
            fovOriginal = -1;
        }
    }

    /** Вызывается при выходе из мира/игры, чтобы не остался изменённый угол обзора. */
    public static void cleanup() {
        CameraEffects.reset();
        ControlsEffects.restore();
        if (fovActive > 0) {
            fovActive = 0;
            restoreFov();
        }
    }

    private static boolean flipCamera(Ctx c) {
        CameraEffects.flip(c.preset().intParam("seconds", 10));
        return true;
    }

    /** Плавно разворачивает игрока на 180 градусов за несколько тиков (по умолчанию 10 = полсекунды). */
    private static boolean turn180(Ctx c) {
        Minecraft mc = c.mc();
        if (mc.player == null) return false;
        int steps = Math.max(1, c.preset().intParam("ticks", 10));
        final float step = 180.0F / steps;
        for (int i = 0; i < steps; i++) {
            ClientTasks.later(i, () -> {
                LocalPlayer p = mc.player;
                if (p == null) return;
                p.yRotO = p.getYRot();
                p.setYRot(p.getYRot() + step);
            });
        }
        return true;
    }

    private static boolean blockMouse(Ctx c) {
        ControlsEffects.blockMouse(c.preset().intParam("seconds", 8));
        return true;
    }

    private static boolean blockKeyboard(Ctx c) {
        ControlsEffects.blockKeyboard(c.preset().intParam("seconds", 8));
        return true;
    }

    private static boolean invertControls(Ctx c) {
        ControlsEffects.invert(c.preset().intParam("seconds", 15));
        return true;
    }

    private static boolean creeperHiss(Ctx c) {
        c.mc().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.CREEPER_PRIMED, 1.0F));
        return true;
    }

    // ============ Только одиночная игра (нужен встроенный сервер) ============

    private static boolean deleteHeldItem(Ctx c) {
        Minecraft mc = c.mc();
        IntegratedServer s = mc.getSingleplayerServer();
        if (s == null || mc.player == null || mc.player.getMainHandItem().isEmpty()) return false;
        UUID id = mc.player.getUUID();
        s.execute(() -> {
            ServerPlayer sp = s.getPlayerList().getPlayer(id);
            if (sp != null) sp.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        });
        return true;
    }

    private static boolean deleteRandomItem(Ctx c) {
        Minecraft mc = c.mc();
        IntegratedServer s = mc.getSingleplayerServer();
        if (s == null || mc.player == null) return false;
        Inventory inv = mc.player.getInventory();
        List<Integer> filled = new ArrayList<>();
        for (int i = 0; i < 36; i++) { // хотбар + основной инвентарь
            if (!inv.getItem(i).isEmpty()) filled.add(i);
        }
        if (filled.isEmpty()) return false;
        final int slot = filled.get(RNG.nextInt(filled.size()));
        UUID id = mc.player.getUUID();
        s.execute(() -> {
            ServerPlayer sp = s.getPlayerList().getPlayer(id);
            if (sp != null) sp.getInventory().setItem(slot, ItemStack.EMPTY);
        });
        return true;
    }

    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private static boolean deleteArmor(Ctx c) {
        Minecraft mc = c.mc();
        IntegratedServer s = mc.getSingleplayerServer();
        if (s == null || mc.player == null) return false;
        boolean any = false;
        for (EquipmentSlot es : ARMOR) {
            if (!mc.player.getItemBySlot(es).isEmpty()) any = true;
        }
        if (!any) return false;
        UUID id = mc.player.getUUID();
        s.execute(() -> {
            ServerPlayer sp = s.getPlayerList().getPlayer(id);
            if (sp == null) return;
            for (EquipmentSlot es : ARMOR) sp.setItemSlot(es, ItemStack.EMPTY);
        });
        return true;
    }

    private static boolean deleteChunk(Ctx c) {
        Minecraft mc = c.mc();
        IntegratedServer s = mc.getSingleplayerServer();
        if (s == null || mc.player == null || mc.level == null) return false;
        ChunkPos cp = mc.player.chunkPosition();
        int feetY = mc.player.getBlockY();
        int depth = Math.max(0, c.preset().intParam("depth_below", 0));
        boolean skipBE = c.preset().boolParam("skip_block_entities", true);
        ResourceKey<Level> dim = mc.level.dimension();
        s.execute(() -> {
            ServerLevel level = s.getLevel(dim);
            if (level == null) return;
            ServerTasks.start(new ServerTasks.ChunkEraser(
                    level, cp.getMinBlockX(), cp.getMinBlockZ(), feetY, depth, skipBE));
        });
        return true;
    }
}
