package baritone.pathing.calc;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

/** 记录搜索起点附近已加载的边界；新地形到来时允许提前续路，不要求身体先走到旧路径尽头。 */
public record LoadedFrontier(BlockPos start, long chunks) {
    public static LoadedFrontier capture(BlockPos start, Predicate<BlockPos> loaded) {
        long chunks = 0;
        int bit = 0, x = start.getX() >> 4, z = start.getZ() >> 4;
        // 只检查路径接续口周围两圈区块；远处无关区块加载不应使当前路线反复重算。
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++, bit++)
            if (loaded.test(new BlockPos((x + dx) * 16 + 8, start.getY(), (z + dz) * 16 + 8))) chunks |= 1L << bit;
        return new LoadedFrontier(start.immutable(), chunks);
    }
    public boolean hasNewTerrain(Predicate<BlockPos> loaded) {
        return (capture(start, loaded).chunks & ~chunks) != 0;
    }
}
