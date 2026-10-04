package dev.streamrewards;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Задачи, которые выполняются на встроенном сервере одиночной игры.
 * На чужом сервере НЕ работают (там нет доступа к миру) — такие награды ставятся на паузу.
 * Все методы вызывать только из потока сервера (через server.execute).
 */
public final class ServerTasks {
    public interface Task {
        /** @return true, если задача закончена. */
        boolean step();
    }

    private static final List<Task> TASKS = new ArrayList<>();
    private static ChunkEraser last;

    private ServerTasks() {}

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> TASKS.removeIf(Task::step));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            TASKS.clear();
            last = null;
        });
    }

    public static void start(Task t) {
        TASKS.add(t);
    }

    /** Откатить последнее стирание чанка. */
    public static boolean undo() {
        if (last == null) return false;
        TASKS.add(new Restorer(last));
        last = null;
        return true;
    }

    /** Стирает блоки в чанке слой за слоем (сверху вниз), запоминая их для отмены. */
    public static final class ChunkEraser implements Task {
        private static final int LAYERS_PER_TICK = 2;

        final ServerLevel level;
        private final int minX;
        private final int minZ;
        private final int yBottom;
        private final boolean skipBlockEntities;
        private int y;
        final List<BlockPos> positions = new ArrayList<>();
        final List<BlockState> states = new ArrayList<>();

        /**
         * @param feetY      уровень ног игрока
         * @param depthBelow на сколько блоков ниже ног тоже стирать (0 — только от ног и выше)
         */
        public ChunkEraser(ServerLevel level, int minX, int minZ, int feetY, int depthBelow, boolean skipBlockEntities) {
            this.level = level;
            this.minX = minX;
            this.minZ = minZ;
            this.skipBlockEntities = skipBlockEntities;
            this.y = level.getMinY() + level.getHeight() - 1;
            this.yBottom = Math.max(level.getMinY() + 1, feetY - depthBelow);
        }

        @Override
        public boolean step() {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int layers = 0;
            while (layers < LAYERS_PER_TICK && y >= yBottom) {
                for (int x = minX; x < minX + 16; x++) {
                    for (int z = minZ; z < minZ + 16; z++) {
                        pos.set(x, y, z);
                        BlockState st = level.getBlockState(pos);
                        if (st.isAir()) continue;
                        if (skipBlockEntities && st.hasBlockEntity()) continue; // сундуки и печки не трогаем
                        if (st.getDestroySpeed(level, pos) < 0) continue;       // бедрок и т.п.
                        positions.add(pos.immutable());
                        states.add(st);
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(),
                                Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                    }
                }
                y--;
                layers++;
            }
            if (y < yBottom) {
                last = this;
                return true;
            }
            return false;
        }
    }

    /** Возвращает блоки обратно (снизу вверх). */
    private static final class Restorer implements Task {
        private static final int BLOCKS_PER_TICK = 6000;
        private final ChunkEraser src;
        private int i;

        Restorer(ChunkEraser src) {
            this.src = src;
            this.i = src.positions.size() - 1;
        }

        @Override
        public boolean step() {
            int n = 0;
            while (i >= 0 && n < BLOCKS_PER_TICK) {
                src.level.setBlock(src.positions.get(i), src.states.get(i), Block.UPDATE_CLIENTS);
                i--;
                n++;
            }
            return i < 0;
        }
    }
}
