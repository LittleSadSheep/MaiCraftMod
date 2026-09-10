package org.maiwithu.maicraft.core.task.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.Comparator;

/**
 * 提供建筑目标的排序规则。当前施工和供料仍使用这个比较器。
 * 时长公式供 BuildTool 估算任务期限，不控制施工器每刻放置几格，也不保证实际完工时间。
 */
public final class BuildOrder {

    private BuildOrder() {}

    /**
     * 期限估算的参数，单位是每刻格数和游戏刻数。公式只按格数估计，不包含走路、瞄准、挖掘和等待服务器。
     */
    static final double SURVIVAL_MIN_RATE = 2.0 / 20.0;    // 估算的生存模式最低速率
    static final double SURVIVAL_TARGET_TICKS = 12 * 60 * 20;   // 仅作为速率公式的十二分钟目标
    static final double FREE_MAX_RATE = 100.0 / 20.0;      // 估算的创造模式最高速率
    static final double FREE_TARGET_TICKS = 25 * 20;       // 仅作为速率公式的二十五秒目标

    /**
     * 先按高度从低到高；同层里先排普通格，再排需要依附的物件，随后区分清空、实心与其他目标。
     * 最后按 z 逐排走，偶数排 x 递增、奇数排 x 递减，让相邻两排首尾接近。
     */
    public static final Comparator<BuildTaskRecord.Target> BUILD_ORDER = Comparator
            .comparingInt((BuildTaskRecord.Target t) -> t.pos().getY())
            .thenComparingInt(t -> needsSupport(t.desiredState()) ? 1 : 0)
            .thenComparingInt(BuildOrder::stage)
            .thenComparingInt(t -> t.pos().getZ())
            .thenComparingInt(t -> (t.pos().getZ() & 1) == 0 ? t.pos().getX() : -t.pos().getX());

    /**
     * 根据类型把梯子、火把、植物、雪层等排在同层骨架后面；有 HANGING 属性也算这一类。
     * 这是排序提示，既不检查现场支撑，也没有保证列全所有模组的依附方块。
     */
    public static boolean needsSupport(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.hasProperty(BlockStateProperties.HANGING)) {
            return true;   // 挂着的灯笼与告示牌
        }
        var b = state.getBlock();
        return b instanceof net.minecraft.world.level.block.LadderBlock
                || b instanceof net.minecraft.world.level.block.BaseTorchBlock
                || b instanceof net.minecraft.world.level.block.SignBlock
                || b instanceof net.minecraft.world.level.block.BasePressurePlateBlock
                || b instanceof net.minecraft.world.level.block.BaseRailBlock
                || b instanceof net.minecraft.world.level.block.DiodeBlock
                || b instanceof net.minecraft.world.level.block.RedStoneWireBlock
                || b instanceof net.minecraft.world.level.block.CarpetBlock
                || b instanceof net.minecraft.world.level.block.BushBlock
                || b instanceof net.minecraft.world.level.block.FlowerPotBlock
                || b instanceof net.minecraft.world.level.block.SnowLayerBlock
                // 按钮、拉杆这类贴面件;砂轮同属这一族但它自己立得住
                || (b instanceof net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
                        && !(b instanceof net.minecraft.world.level.block.GrindstoneBlock));
    }

    /** 层内阶段:清障 0 → 骨架 1 → 贴附 2。 */
    public static int stage(BuildTaskRecord.Target target) {
        if (BuildCellRules.isAirTarget(target)) {
            return 0;
        }
        BlockState state = target.desiredState();
        return state != null && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE,
                BlockPos.ZERO) ? 1 : 2;
    }

    /**
     * 期限估算使用的速率：生存至少每秒两格、按十二分钟目标提高估值；创造按二十五秒目标估计、上限每秒一百格。
     * 该数值只用于下面的 estimatedTicks，不是实际执行速率。
     */
    public static double paceFor(int cellCount, boolean consumeMaterials) {
        int cells = Math.max(1, cellCount);
        return consumeMaterials
                ? Math.max(SURVIVAL_MIN_RATE, cells / SURVIVAL_TARGET_TICKS)
                : Math.min(FREE_MAX_RATE, cells / FREE_TARGET_TICKS);
    }

    /**
     * 用格数除以估算速率并向上取整，交给 BuildTool.timeoutTicksFor 加上行程预算和余量。
     */
    public static long estimatedTicks(int cellCount, boolean consumeMaterials) {
        return (long) Math.ceil(Math.max(1, cellCount) / paceFor(cellCount, consumeMaterials));
    }
}
