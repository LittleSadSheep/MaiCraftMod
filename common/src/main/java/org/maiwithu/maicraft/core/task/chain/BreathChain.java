package org.maiwithu.maicraft.core.task.chain;

import org.maiwithu.maicraft.task.reflex.Reflex;
import org.maiwithu.maicraft.entity.InputDriver;

import org.maiwithu.maicraft.core.WorkProfile;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;
import org.maiwithu.maicraft.core.pathing.util.SwimAirBudget;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.core.task.survival.SurvivalDecisions;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayDeque;
import java.util.HashSet;
import org.maiwithu.maicraft.core.Constants;

/**
 * 自主上浮补气生存链，为玩家角色补上原版 Mob 自带的浮水本能。自动化 LocalPlayer 输入不会持续按住跳跃键：寻路只会在执行移动时让角色划水上浮，
 * 因此若任务在游泳中结束、玩家发出 Stop 或角色只是闲置漂浮，深水中的身体会下沉、耗尽空气并溺水。
 * 本链持续检查浸水状态和抵达水面所需空气；若寻路尚未主动管理游泳，就在上浮余量耗尽前接管角色，
 * 并在头部露出水面后继续持有控制，直到权威空气值恢复满格。恢复迟滞可防止寻路在刚能呼吸的第一刻立刻重新潜水。
 *
 * <p>水面上方开阔时直接上浮即可。若头顶被封闭（例如冰封海洋或洪水洞穴，角色可能一直顶着浮冰却无法上升），
 * 则通过 BFS 遍历连通水域，寻找上方有可呼吸空间的最近柱列，并一边持续上浮一边游向开口。
 * 只有预算范围内找不到开口时，才退回尽力直线上浮，并立即记录受困情况，让认知层趁角色还有空气时采取行动。
 */
public final class BreathChain implements Task, Reflex {

    /** 直线上探测到多高后才判定头顶封闭；连续水深超过此值代表开阔海洋，应继续上浮。 */
    private static final int CEILING_PROBE = 16;
    /** 搜索可呼吸开口时，BFS 遍历连通水格的预算。 */
    private static final int AIR_SEARCH_BUDGET = 400;
    /** 开口搜索的水平范围上限，按轴计算与起始柱列的方块距离。 */
    private static final int AIR_SEARCH_RADIUS = 16;
    /** 重新验证或选择游向开口的间隔游戏刻数。 */
    private static final int RETARGET_TICKS = 20;

    /** 本次受困期间观察到的最低空气值，用于唯一一条受困记录。 */
    private int worstAir = Integer.MAX_VALUE;
    private boolean episodeActive;
    /** 上方有可呼吸空间的水格；直线上方封闭时作为游泳目标，空值表示直接上浮。 */
    private BlockPos airColumn;
    private int retargetCooldown;
    /** 每次受困最多写一条记录；搜索无果时立即写入，确保认知层仍有剩余空气可采取行动。 */
    private boolean trappedNoted;
    private final SwimAirBudget airBudget = new SwimAirBudget();
    private float attentionStartHealth;
    private int swimTicks;

    public BreathChain() {
    }

    @Override
    public boolean canRun(LocalPlayer companion) {
        boolean triggered;
        boolean headUnderWater = companion.isEyeInFluid(FluidTags.WATER);
        airBudget.observe(companion.level().getGameTime(), companion.getAirSupply(), headUnderWater);
        if (WorkProfile.of(companion).fearless()
                || companion.hasEffect(MobEffects.WATER_BREATHING)
                || companion.hasEffect(MobEffects.CONDUIT_POWER)) {
            triggered = false;
        } else {
            triggered = episodeActive
                    ? SurvivalDecisions.breathRecoveryRequired(
                            companion.isInWater(), headUnderWater, companion.onGround(),
                            companion.getAirSupply(), companion.getMaxAirSupply())
                    : !EmbeddedBaritoneRuntime.managesSwimAir(companion)
                            && SurvivalDecisions.breathTriggered(headUnderWater, companion.getAirSupply(),
                                    requiredAirForSurface(companion));
        }
        if (!triggered && episodeActive) {
            noteEpisode(companion);
        }
        return triggered;
    }

    // 当前向上读水柱使用 hasChunkAt 作加载限制，但原版客户端不会在未知区块把它变成 false。
    private int requiredAirForSurface(LocalPlayer player) {
        Vec3 eyes = player.getEyePosition();
        BlockPos head = BlockPos.containing(eyes);
        for (int rise = 0; rise < CEILING_PROBE; rise++) {
            BlockPos pos = head.above(rise);
            if (!player.level().hasChunkAt(pos)) break;
            BlockState state = player.level().getBlockState(pos);
            if (state.getFluidState().is(FluidTags.WATER)) continue;
            if (breathable(player.level(), pos, state)) {
                return SwimAirBudget.requiredAirForAscent(pos.getY() + 0.2D - eyes.y, airBudget.airPerTick());
            }
            break;
        }
        // 深度未知或顶部封闭时，既要搜索开口又要上浮，因此延长处理时间。
        return SwimAirBudget.requiredAirForAscent(CEILING_PROBE, airBudget.airPerTick());
    }

    @Override
    public TaskState tick(LocalPlayer companion) {
        if (!episodeActive) {
            attentionStartHealth = companion.getHealth();
            swimTicks = 0;
            GameplayAttentionMonitor.reflexStarted(
                    id(), "air supply is low while submerged", "emergency movement",
                    "no item consumption expected", "drowning damage or death if air is not reached");
        }
        episodeActive = true;
        swimTicks++;
        worstAir = Math.min(worstAir, companion.getAirSupply());
        InputDriver.halt(companion);
        // 开阔水域中直接上浮是最简单的救援方式。只有水柱封闭时才横向搜寻：持续上浮并穿过连通水域，游向上方有空气的最近开口，例如冰洞或洞穴入口。
        if (!ceilingSealed(companion)) {
            airColumn = null;
        } else {
            if (airColumn == null || --retargetCooldown <= 0
                    || !breathableAbove(companion.level(), airColumn)) {
                airColumn = findAirColumn(companion);
                retargetCooldown = RETARGET_TICKS;
                if (airColumn == null) {
                    noteTrapped(companion);
                }
            }
            if (airColumn != null) {
                InputDriver.stepToward(companion, Vec3.atCenterOf(airColumn), false);
            }
        }
        // 角色仍接触水面时，此动作既能上浮，也能在补气阶段让眼睛保持露出水面；完全上岸后，canRun() 会立即释放控制，避免角色在陆地上跳跃。
        InputDriver.jump(companion);
        return TaskState.RUNNING;
    }

    /**
     * 判断头顶正上方的水柱是否在到达可呼吸空间前被封住。连续水深超过 {@link #CEILING_PROBE} 时按开阔水域处理，深海中应直接上浮。
     */
    private static boolean ceilingSealed(LocalPlayer companion) {
        Level level = companion.level();
        BlockPos p = BlockPos.containing(companion.getEyePosition());
        for (int i = 0; i < CEILING_PROBE; i++) {
            p = p.above();
            BlockState s = level.getBlockState(p);
            // 当前含水方块也直接跳过，因此这里会漏掉含水半砖形成的实体顶盖。
            if (s.getFluidState().is(FluidTags.WATER)) continue;
            return !breathable(level, p, s);
        }
        return false;
    }

    /** 头部可呼吸的格子：没有液体，也没有碰撞物。 */
    private static boolean breathable(Level level, BlockPos pos, BlockState state) {
        return state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty();
    }

    /** {@code waterCell} 是否仍是有效开口：该格有水且上方有可呼吸空间。 */
    private static boolean breathableAbove(Level level, BlockPos waterCell) {
        if (!level.getFluidState(waterCell).is(FluidTags.WATER)) return false;
        BlockPos above = waterCell.above();
        return breathable(level, above, level.getBlockState(above));
    }

    /**
     * 从头部出发，通过 BFS 遍历连通水格，寻找上方有可呼吸空间的最近位置。按实际游泳距离排序，角色会前往最近的真实开口，而不会误追墙后直线距离很近的假目标。
     * 搜索受 {@link #AIR_SEARCH_BUDGET} 和 {@link #AIR_SEARCH_RADIUS} 限制；受困期间每经过 {@link #RETARGET_TICKS} 最多读取约 400 个方块。
     */
    private static BlockPos findAirColumn(LocalPlayer companion) {
        Level level = companion.level();
        BlockPos start = BlockPos.containing(companion.getEyePosition());
        if (!level.getFluidState(start).is(FluidTags.WATER)) {
            start = companion.blockPosition();
        }
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        HashSet<Long> seen = new HashSet<>();
        queue.add(start);
        seen.add(start.asLong());
        int budget = AIR_SEARCH_BUDGET;
        while (!queue.isEmpty() && budget-- > 0) {
            BlockPos cell = queue.poll();
            if (breathableAbove(level, cell)) {
                return cell;
            }
            for (Direction d : Direction.values()) {
                BlockPos n = cell.relative(d);
                if (Math.abs(n.getX() - start.getX()) > AIR_SEARCH_RADIUS
                        || Math.abs(n.getZ() - start.getZ()) > AIR_SEARCH_RADIUS) continue;
                // 扩展邻格只认水，没有检查身体能否穿过；找到透气口不等于已经证明能游到那里。
                if (!level.getFluidState(n).is(FluidTags.WATER)) continue;
                if (seen.add(n.asLong())) {
                    queue.add(n);
                }
            }
        }
        return null;
    }

    /** 一旦诊断出受困就立即记录，不等到溺水后才上报。 */
    private void noteTrapped(LocalPlayer companion) {
        if (trappedNoted) return;
        trappedNoted = true;
        GameplayAttentionMonitor.reflexEscalated(
                id(), "the direct ascent is sealed and no nearby opening was proved",
                "air continues falling while the reflex uses best-effort upward movement");
        Constants.LOG.info(
                "[maicraft-breath] drowning under a sealed ceiling with {}s of air; no opening within {} blocks",
                Math.max(0, companion.getAirSupply() / 20), AIR_SEARCH_RADIUS);
    }

    /** 每次险些溺水只写一条记录，并标注剩余空气对应的秒数。 */
    private void noteEpisode(LocalPlayer companion) {
        int worst = worstAir;
        float healthLost = Math.max(0.0F, attentionStartHealth - companion.getHealth());
        GameplayAttentionMonitor.reflexFinished(
                id(), companion.isEyeInFluid(FluidTags.WATER)
                        ? "air recovery no longer needed" : "breathable air reached", swimTicks,
                "no item consumption observed",
                healthLost > 0.0F ? "health lost during reflex: " + healthLost : "no health loss observed");
        episodeActive = false;
        worstAir = Integer.MAX_VALUE;
        swimTicks = 0;
        airColumn = null;
        retargetCooldown = 0;
        trappedNoted = false;
        Constants.LOG.info(
                "[maicraft-breath] breath recovery completed (lowest air: {}s)",
                Math.max(0, worst / 20));
    }

    @Override
    public void stop(LocalPlayer companion, StopReason why) {
        // 没有需要跨 tick 释放的身体状态；本次事件记录会在下次休眠检查时关闭，或由新一次入水事件替代。
        if (episodeActive && why != StopReason.PREEMPTED) {
            float healthLost = Math.max(0.0F, attentionStartHealth - companion.getHealth());
            GameplayAttentionMonitor.reflexFinished(
                    id(), "body or reflex unavailable; air recovery unconfirmed", swimTicks,
                    "no item consumption observed",
                    healthLost > 0.0F ? "health lost during reflex: " + healthLost : "no health loss observed");
            episodeActive = false;
            worstAir = Integer.MAX_VALUE;
            swimTicks = 0;
            airColumn = null;
            trappedNoted = false;
        }
    }

    @Override
    public String name() {
        return "breath";
    }

    // ---- 反射链登记信息（章程 §6）----

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "在水里快憋不住气时会自己浮上来换气,头顶被冰面/岩层封住时会游向最近的透气口";
    }
}
