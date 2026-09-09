package org.maiwithu.maicraft.core.task.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.Comparator;

/**
 * 提供建筑目标的排序规则。当前施工和供料仍使用这个比较器。
 * 下面的旧速率与时长公式当前没有生产调用者，不能用它们承诺实际完工时间。
 */
public final class BuildOrder {

    private BuildOrder() {}

    /**
     * 旧节奏公式的参数，单位是每刻格数和游戏刻数。公式只按格数估计，不包含走路、瞄准、挖掘和等待服务器。
     */
    static final double SURVIVAL_MIN_RATE = 2.0 / 20.0;    // 每秒 2 格,慢的那一头
    static final double SURVIVAL_TARGET_TICKS = 12 * 60 * 20;   // 仅作为速率公式的十二分钟目标
    static final double FREE_MAX_RATE = 100.0 / 20.0;      // 创造快,但不瞬移
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
     * 旧速率公式：生存模式至少每秒两格，按十二分钟目标可进一步加速；创造模式按二十五秒目标计算，最多每秒一百格。
     * 返回计划速率，不代表角色真的能在这一时间内完成相应操作。
     */
    public static double paceFor(int cellCount, boolean consumeMaterials) {
        int cells = Math.max(1, cellCount);
        return consumeMaterials
                ? Math.max(SURVIVAL_MIN_RATE, cells / SURVIVAL_TARGET_TICKS)
                : Math.min(FREE_MAX_RATE, cells / FREE_TARGET_TICKS);
    }

    /**
     * 用同一旧速率公式计算格数除以速率，并向上取整。它只是该公式的估计值，目前没有生产调用者。
     */
    public static long estimatedTicks(int cellCount, boolean consumeMaterials) {
        return (long) Math.ceil(Math.max(1, cellCount) / paceFor(cellCount, consumeMaterials));
    }
}
