package baritone.pathing.movement;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/** 垫块尚未成功时，只有整只脚已被真实地面托住，才允许撤销路线并交还身体。 */
public final class PlacementHandoff {
    private PlacementHandoff() {}

    public static boolean supported(BlockGetter level, AABB body, Predicate<BlockPos> loaded) {
        // 检查完整脚底薄层，不能把贴边潜行的一小块接触面当成松开按键后仍安全的站位。
        double inset = 1.0e-5;
        var missing = Shapes.create(new AABB(body.minX + inset, body.minY - .005, body.minZ + inset,
                body.maxX - inset, body.minY, body.maxZ - inset));
        int y = Mth.floor(body.minY - .005);
        for (int x = Mth.floor(body.minX + inset); x <= Mth.floor(body.maxX - inset); x++)
            for (int z = Mth.floor(body.minZ + inset); z <= Mth.floor(body.maxZ - inset); z++) {
                var pos = new BlockPos(x, y, z);
                if (!loaded.test(pos)) return false;
                var state = level.getBlockState(pos);
                if (!state.getFluidState().isEmpty()) return false;
                missing = Shapes.joinUnoptimized(missing,
                        state.getCollisionShape(level, pos).move(x, y, z), BooleanOp.ONLY_FIRST);
            }
        return missing.isEmpty();
    }
}
