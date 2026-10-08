// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 角色此刻的生存处境：坠落、溺水、被埋三个判断各自要读的处境，一次取齐。
 *
 * <p>字段都是从角色身上读到的观察事实，不含任何结论；结论由三个判断纯函数给出。
 * 每刻由 {@link SituationReader} 重新取一份，不留到下一刻用。
 */
public final class SurvivalSituation implements FallDanger.View, DrowningDanger.View, BuriedDanger.View {

    /** 已经下落的距离（格）。站着或贴地时为 0。 */
    private final double fallDistance;
    /** 当前生命（一颗心算 2 点）。 */
    private final double health;
    /** 脚的所在高度，换气任务用它判断上浮有没有真实进展。 */
    private final double feetY;
    /** 角色当前水平朝向（度）。换气抬头时保持原朝向，只把镜头压到向上。 */
    private final float facingYaw;
    /** 头部（眼睛所在的一格）是否在水里。 */
    private final boolean headInWater;
    /** 氧气条还剩几泡，0 到 {@link DrowningDanger#MAX_BUBBLES}。 */
    private final int airBubbles;
    /** 是否已经因为缺氧开始掉血。 */
    private final boolean drowning;
    /** 是否卡在会窒息的实心方块里。 */
    private final boolean stuckInSolidBlock;
    /** 被埋时最先要刨开的那一格；没有被埋住时为 null。先头部、后躯干。 */
    private final BlockPos buriedCell;
    /** 正下方直到世界底都是空的（悬在虚空上）。只有下落时才算，其他时候一律按不是虚空处理。 */
    private final boolean overVoid;

    SurvivalSituation(double fallDistance, double health, double feetY, float facingYaw,
                              boolean headInWater, int airBubbles, boolean drowning,
                              boolean stuckInSolidBlock, BlockPos buriedCell, boolean overVoid) {
        this.fallDistance = fallDistance;
        this.health = health;
        this.feetY = feetY;
        this.facingYaw = facingYaw;
        this.headInWater = headInWater;
        this.airBubbles = airBubbles;
        this.drowning = drowning;
        this.stuckInSolidBlock = stuckInSolidBlock;
        this.buriedCell = buriedCell;
        this.overVoid = overVoid;
    }

    @Override public double fallDistance() { return fallDistance; }
    @Override public double health() { return health; }
    @Override public boolean overVoid() { return overVoid; }
    @Override public boolean headInWater() { return headInWater; }
    @Override public int airBubbles() { return airBubbles; }
    @Override public boolean drowning() { return drowning; }
    @Override public boolean stuckInSolidBlock() { return stuckInSolidBlock; }

    /** 脚的所在高度；换气任务判断上浮进展用。 */
    public double feetY() { return feetY; }
    /** 角色当前水平朝向（度）；换气抬头时保持它不变。 */
    public float facingYaw() { return facingYaw; }
    /** 被埋时最先要刨开的那一格；没有被埋住时为 null。 */
    public BlockPos buriedCell() { return buriedCell; }

    /** 原版氧气条 300 刻空气换 10 泡，每 30 刻一格泡。F 游戏事实，来源：原版氧气规则。 */
    static int bubblesOf(int airTicks) {
        return Math.floorDiv(airTicks, 30);
    }

    /**
     * 从真实游戏状态读一份处境。只在客户端刻内、拿着当刻的角色上下文调用。
     * 只读世界，不写任何东西；虚空判断只在下落时逐格往下扫，平时不花这个开销。
     */
    public static SurvivalSituation read(PlayerContext context) {
        var player = context.localPlayer();
        Level level = context.level();
        BlockPos eyeCell = BlockPos.containing(player.getEyePosition());
        BlockPos footCell = player.blockPosition();
        boolean headWet = level.getFluidState(eyeCell).is(FluidTags.WATER);
        boolean headBuried = level.getBlockState(eyeCell).isSuffocating(level, eyeCell);
        boolean bodyBuried = level.getBlockState(footCell).isSuffocating(level, footCell);
        int airTicks = player.getAirSupply();
        double fall = player.fallDistance;
        return new SurvivalSituation(
                fall,
                player.getHealth(),
                player.getY(),
                player.getYRot(),
                headWet,
                bubblesOf(airTicks),
                airTicks <= 0,
                headBuried || bodyBuried,
                headBuried ? eyeCell : bodyBuried ? footCell : null,
                fall > 0 && isVoidBelow(level, footCell));
    }

    /** 从脚下一格一直扫到世界底，中间没有任何可碰撞的方块才算悬在虚空上。 */
    private static boolean isVoidBelow(Level level, BlockPos footCell) {
        for (int y = footCell.getY() - 1; y >= level.getMinBuildHeight(); y--) {
            BlockPos pos = new BlockPos(footCell.getX(), y, footCell.getZ());
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 每刻从角色上下文取一份生存处境；测试里换成 canned 的处境替身。 */
    @FunctionalInterface
    public interface SituationReader {
        SurvivalSituation read(TickContext context);
    }

    /** 生产用读取器：从当刻的角色上下文读真实游戏状态。 */
    public static final class FromPlayer implements SituationReader {
        /** 判断急迫时才读游戏状态；没有角色上下文（例如角色还没进世界）就没有处境，按什么都不急处理。 */
        @Override
        public SurvivalSituation read(TickContext context) {
            PlayerContext player = context.player();
            return player == null ? null : SurvivalSituation.read(player);
        }
    }
}
