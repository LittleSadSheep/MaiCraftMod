package org.maiwithu.maicraft.core.task.chain;

import org.maiwithu.maicraft.task.reflex.Reflex;
import org.maiwithu.maicraft.entity.InputDriver;

import org.maiwithu.maicraft.core.WorkProfile;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritoneRuntime;
import org.maiwithu.maicraft.core.pathing.util.SwimAirBudget;
import org.maiwithu.maicraft.core.pathing.util.BreathingRoute;
import org.maiwithu.maicraft.core.pathing.baritone.EmbeddedBaritonePolicy;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.core.task.survival.SurvivalDecisions;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.client.actor.BodyControlPort;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.Constants;

/**
 * 自主上浮补气生存链，为玩家角色补上原版 Mob 自带的浮水本能。自动化 LocalPlayer 输入不会持续按住跳跃键：寻路只会在执行移动时让角色划水上浮，
 * 因此若任务在游泳中结束、玩家发出 Stop 或角色只是闲置漂浮，深水中的身体会下沉、耗尽空气并溺水。
 * 本链持续检查浸水状态和抵达水面所需空气；若寻路尚未主动管理游泳，就在上浮余量耗尽前接管角色，
 * 并在头部露出水面后继续持有控制，直到权威空气值恢复满格。恢复迟滞可防止寻路在刚能呼吸的第一刻立刻重新潜水。
 *
 * <p>水面上方开阔时直接上浮即可。若头顶被封闭（例如冰封海洋或洪水洞穴，角色可能一直顶着浮冰却无法上升），
 * 则按游泳代价搜索真实身体能通过的水下路线，依次经过转弯和开口后再上浮。
 * 搜索失败会如实记录受困，不把穿墙直线或含水半砖当作可用出口。
 */
public final class BreathChain implements Task, Reflex {

    /** 直线上探测到多高后才判定头顶封闭；连续水深超过此值代表开阔海洋，应继续上浮。 */
    private static final int CEILING_PROBE = 16;
    /** 本次受困期间观察到的最低空气值，用于唯一一条受困记录。 */
    private int worstAir = Integer.MAX_VALUE;
    private boolean episodeActive;
    // 低顶水域保留整条游泳路线，沿转弯逐步接近空气；路线失效时从当前身体重新搜索。
    private BreathingRoute.Search search;
    private BreathingRoute.Route escape;
    private int waypoint;
    private Vec3 lastProgress;
    private int stalledTicks;
    private int retryTicks;
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
                    : headUnderWater && !EmbeddedBaritoneRuntime.managesSwimAir(companion)
                            && SurvivalDecisions.breathTriggered(headUnderWater, companion.getAirSupply(),
                                    requiredAirForSurface(companion));
        }
        if (!triggered && episodeActive) {
            noteEpisode(companion);
        }
        return triggered;
    }

    // 顶部封闭时提前预留绕行时间，避免只按竖直水深估算、等到剩余氧气已经不够转弯才开始找出口。
    private int requiredAirForSurface(LocalPlayer player) {
        var route = BreathingRoute.ascent(BreathingRoute.observed(player, EmbeddedBaritonePolicy.snapshot().forbiddenBodyCells()), player.position());
        return route == null ? Math.max(player.getMaxAirSupply() - 20,
                SwimAirBudget.requiredAirForAscent(CEILING_PROBE, airBudget.airPerTick())) : route.requiredAir(airBudget.airPerTick());
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
        // 眼睛出水后守住水面等空气补满；水下则先证明路线，绕行阶段不再无条件顶着天花板按上浮。
        if (!companion.isEyeInFluid(FluidTags.WATER)) {
            if (companion.isInWater()) InputDriver.jump(companion);
            return TaskState.RUNNING;
        }
        var view = BreathingRoute.observed(companion, EmbeddedBaritonePolicy.snapshot().forbiddenBodyCells());
        Vec3 here = companion.position();
        if (retryTicks-- > 0) return TaskState.RUNNING;
        if (lastProgress == null || lastProgress.distanceToSqr(here) > .04) { lastProgress = here; stalledTicks = 0; }
        else stalledTicks++;
        if (escape == null && search == null) {
            escape = BreathingRoute.ascent(view, here);
            if (escape == null) search = new BreathingRoute.Search(here);
            waypoint = 1;
        }
        if (search != null) {
            search.advance(view, 64);
            if (!search.done()) return TaskState.RUNNING;
            escape = search.result(); search = null; waypoint = 1;
            if (escape == null) { retryTicks = 20; noteTrapped(companion); return TaskState.RUNNING; }
            stalledTicks = 0; lastProgress = here;
            Constants.LOG.info("[maicraft-breath] escape route nodes={} required_air={} remaining_air={}",
                    escape.points().size(), escape.requiredAir(airBudget.airPerTick()), companion.getAirSupply());
        }
        while (waypoint < escape.points().size() && here.distanceToSqr(escape.points().get(waypoint)) < .09) waypoint++;
        if (waypoint >= escape.points().size() || stalledTicks > 20) { escape = null; stalledTicks = 0; return TaskState.RUNNING; }
        Vec3 next = escape.points().get(waypoint);
        if (!view.clear(here, next)) { escape = null; return TaskState.RUNNING; }
        double horizontal = Math.hypot(next.x - here.x, next.z - here.z);
        // 在低顶通道按实际路径保持或降低高度；走到畅通水柱后才按跳跃上浮，避免上推把身体卡死在拐角。
        if (horizontal > .18) {
            InputDriver.lookAt(companion, new Vec3(next.x, companion.getEyeY(), next.z));
        }
        var context = ClientRuntime.requireContext(companion);
        // 镜头平滑转向期间也按当前实际朝向投影按键，避免刚绕过拐角就沿旧视角游回墙上。
        double desiredYaw = Math.toDegrees(Math.atan2(next.z - here.z, next.x - here.x)) - 90;
        context.body().applySteering(yaw -> new BodyControlPort.Movement(
                horizontal > .18 ? (float) Math.cos(Math.toRadians(desiredYaw - yaw)) : 0,
                horizontal > .18 ? (float) -Math.sin(Math.toRadians(desiredYaw - yaw)) : 0,
                next.y > here.y + .12, next.y < here.y - .12, false), companion.getYRot(), context.tickRevision());
        return TaskState.RUNNING;
    }

    /** 一旦诊断出受困就立即记录，不等到溺水后才上报。 */
    private void noteTrapped(LocalPlayer companion) {
        if (trappedNoted) return;
        trappedNoted = true;
        GameplayAttentionMonitor.reflexEscalated(
                id(), "the direct ascent is sealed and no nearby opening was proved",
                "no collision-safe route to breathable air was found within the bounded search");
        Constants.LOG.info(
                "[maicraft-breath] drowning under a sealed ceiling with {}s of air; no opening within {} blocks",
                Math.max(0, companion.getAirSupply() / 20), 16);
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
        escape = null; search = null; lastProgress = null; stalledTicks = 0; retryTicks = 0;
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
            escape = null; search = null; lastProgress = null; stalledTicks = 0; retryTicks = 0;
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
