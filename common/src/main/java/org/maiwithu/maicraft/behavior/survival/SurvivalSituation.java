// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.navigation.util.SwimAirBudget;
import org.maiwithu.maicraft.game.player.FallDamage;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BucketWater;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 角色此刻的生存处境：坠落、溺水、被埋三个判断各自要读的处境，一次取齐。
 *
 * <p>字段都是从角色身上读到的观察事实，不含结论；结论由三个判断纯函数给出。
 * 每刻由 {@link SituationReader} 重新取一份，不留到下一刻用。
 *
 * @param health               当前生命（一颗心算 2 点）
 * @param feetY                脚的高度；换气判断上浮有没有真实进展
 * @param facingYaw            水平朝向（度）；换气、低头放水时保持它不变
 * @param falling              正在往下掉
 * @param overVoid             下面一直到世界底都是空的
 * @param landsInWater         落点是水
 * @param survivesLanding      按预计落地伤害算落地后还活着
 * @param waterCell            落地前放水该放进的那一格（落点上面那格，矮草这类会被水冲掉的往上越过）；没有落点时为 null
 * @param headInWater          眼睛在水里
 * @param canBreatheUnderwater 有水下呼吸类效果
 * @param airTicks             剩下的氧气（刻）
 * @param maxAirTicks          氧气满值（刻）
 * @param airNeededToSurface   直着游上去要用的氧气（刻），含三秒反应余量
 * @param stuckInSolidBlock    头卡在会窒息的实心方块里
 * @param buriedCell           被埋时最先要刨开的那一格（先眼睛所在格、再头顶格）；没被埋时为 null
 * @param waterCeiling         头在水里时，往上那一柱水顶上压着的实心方块（冰面、石头）；水面上是空气或不在水里时为 null
 */
public record SurvivalSituation(
        double health,
        double feetY,
        float facingYaw,
        boolean falling,
        boolean overVoid,
        boolean landsInWater,
        boolean survivesLanding,
        BlockPos waterCell,
        boolean headInWater,
        boolean canBreatheUnderwater,
        int airTicks,
        int maxAirTicks,
        int airNeededToSurface,
        boolean stuckInSolidBlock,
        BlockPos buriedCell,
        BlockPos waterCeiling) implements FallDanger.View, DrowningDanger.View, BuriedDanger.View {

    /** 往上数水深最多数多少格；再深按这个深度估，照样会早早叫人上浮。类别：玩家常识。 */
    private static final int MAX_DEPTH_PROBE = 16;

    /**
     * 从真实游戏状态读一份处境。只在客户端刻内、拿着当刻的角色上下文调用。
     * 只读世界，不写任何东西；落点只在往下掉时才往下扫，平时不花这个开销。
     */
    public static SurvivalSituation read(PlayerContext context) {
        LocalPlayer player = context.localPlayer();
        Level level = context.level();
        BlockPos feet = player.blockPosition();
        BlockPos eyeCell = BlockPos.containing(player.getEyePosition());
        BlockPos headTopCell = BlockPos.containing(player.getX(), player.getBoundingBox().maxY - 1.0e-3, player.getZ());
        BlockPos buried = suffocates(level, eyeCell) ? eyeCell
                : suffocates(level, headTopCell) ? headTopCell : null;

        boolean falling = !player.onGround() && player.getDeltaMovement().y < 0 && !player.isInWater()
                && !player.onClimbable() && !player.isFallFlying() && !player.getAbilities().flying
                && player.getVehicle() == null;
        Landing landing = falling ? landingBelow(level, feet) : Landing.NONE;
        boolean survives = true;
        if (falling && landing.cell() != null && !landing.water()) {
            BlockState support = level.getBlockState(landing.cell());
            double top = landing.cell().getY()
                    + support.getCollisionShape(level, landing.cell()).max(Direction.Axis.Y);
            survives = FallDamage.capture(player).survives(Math.max(0.0, player.getY() - top),
                    FallDamage.Landing.of(support), true);
        }

        boolean headWet = player.isEyeInFluid(FluidTags.WATER);
        int needed = headWet ? SwimAirBudget.requiredAirForAscent(waterAbove(level, eyeCell), 1.0) : 0;
        return new SurvivalSituation(
                player.getHealth(),
                player.getY(),
                player.getYRot(),
                falling,
                falling && landing.cell() == null,
                landing.water(),
                survives,
                landing.cell() == null || landing.water()
                        ? null : BucketWater.exposedWaterCell(level, landing.cell().above()),
                headWet,
                player.canBreatheUnderwater() || player.getAbilities().invulnerable,
                player.getAirSupply(),
                player.getMaxAirSupply(),
                needed,
                buried != null,
                buried,
                headWet ? ceilingAbove(level, eyeCell) : null);
    }

    // 往上那一柱水的顶上：是空气就能浮出去；是实心方块（冰面、石头）就是压在头顶的盖子，给出那一格。
    private static BlockPos ceilingAbove(Level level, BlockPos eyeCell) {
        BlockPos cell = eyeCell;
        for (int depth = 0; depth < MAX_DEPTH_PROBE && level.getFluidState(cell).is(FluidTags.WATER); depth++) {
            cell = cell.above();
        }
        if (level.getFluidState(cell).is(FluidTags.WATER)) return null;
        return level.getBlockState(cell).getCollisionShape(level, cell).isEmpty() ? null : cell;
    }

    // 这一格会让人窒息：实心、挡视线的方块（玻璃、树叶不算）。
    private static boolean suffocates(Level level, BlockPos cell) {
        return level.isLoaded(cell) && level.getBlockState(cell).isSuffocating(level, cell);
    }

    // 从脚下一格往下扫：先碰到水就是落进水里；先碰到能落脚的方块就是落点；一直扫到世界底都没有就是虚空。
    private static Landing landingBelow(Level level, BlockPos feet) {
        for (int y = feet.getY() - 1; y >= level.getMinBuildHeight(); y--) {
            BlockPos cell = new BlockPos(feet.getX(), y, feet.getZ());
            if (!level.isLoaded(cell)) {
                // 没加载的格子不能当成空：按这里有落点处理，出手前会再看。
                return new Landing(cell, false);
            }
            if (level.getFluidState(cell).is(FluidTags.WATER)) {
                return new Landing(cell, true);
            }
            if (!level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()) {
                return new Landing(cell, false);
            }
        }
        return Landing.NONE;
    }

    // 眼睛往上还有几格水；到顶都是水时按探测深度算。
    private static double waterAbove(Level level, BlockPos eyeCell) {
        int depth = 0;
        for (BlockPos cell = eyeCell; depth < MAX_DEPTH_PROBE; cell = cell.above()) {
            if (!level.getFluidState(cell).is(FluidTags.WATER)) break;
            depth++;
        }
        return depth;
    }

    /** 往下扫到的落点：哪一格、是不是水；没有落点时 cell 为 null。 */
    private record Landing(BlockPos cell, boolean water) {
        static final Landing NONE = new Landing(null, false);
    }

    /** 每刻从角色上下文取一份生存处境；测试里换成脚本给的处境。 */
    @FunctionalInterface
    public interface SituationReader {
        SurvivalSituation read(TickContext context);
    }

    /** 生产用读取器：从当刻的角色上下文读真实游戏状态。 */
    public static final class FromPlayer implements SituationReader {
        /** 没有角色上下文（例如角色还没进世界）就没有处境，按什么都不急处理。 */
        @Override
        public SurvivalSituation read(TickContext context) {
            PlayerContext player = context.player();
            return player == null ? null : SurvivalSituation.read(player);
        }
    }
}
