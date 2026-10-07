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
import org.maiwithu.maicraft.core.combat.CombatThreats;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import java.util.List;

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
    // 换气独占的是逃生路线；近身攻击者仍交给既有近战执行器，不另开追击或水下弓战。
    private AttackCompanionTask defense;

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
        // 先反击，再恢复本刻逃生输入；近战瞄准或撤销旧攻击可能 halt，不能让它清掉上浮和横游。
        if (defense == null && !CombatThreats.attackers(companion).isEmpty()) {
            defense = new AttackCompanionTask(companion,
                    new AttackTaskRecord("breath-defense-" + companion.level().getGameTime(), Long.MAX_VALUE, List.of(), true));
            defense.start(companion);
        }
        if (defense != null) defense.tickEmergencyMelee();
        return tickRecovery(companion);
    }

    private TaskState tickRecovery(LocalPlayer companion) {
        if (!episodeActive) {
            attentionStartHealth = companion.getHealth();
            swimTicks = 0;
            GameplayAttentionMonitor.reflexStarted(
                    id(), "air supply is low while submerged", "emergency movement",
                    "nearby self-defense may consume weapon durability", "drowning damage or death if air is not reached");
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
        double desiredYaw = Math.toDegrees(Math.atan2(next.z - here.z, next.x - here.x)) - 90;
        // 逃生视角让位于本刻近战瞄准；下方按实际朝向重投影按键，回头打溺尸仍沿原通道游向空气。
        if (horizontal > .18) InputDriver.lookForNavigation(companion, (float) desiredYaw, 0);
        var context = ClientRuntime.requireContext(companion);
        // 镜头平滑转向期间也按当前实际朝向投影按键，避免刚绕过拐角就沿旧视角游回墙上。
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
                "nearby self-defense may consume weapon durability",
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
        // 换气结束或身体交接时一并清理其近战子动作；不宣称击败敌人，后续自卫重新观察实际威胁。
        if (defense != null) {
            // 死亡或换身体后可能已没有可用原生入口；此时只释放旧输入，不向旧身体查询或提交动作。
            if (ClientRuntime.actor().activeContext().filter(c -> c.player() == companion && c.isCurrent()).isPresent())
                defense.result(TaskState.CANCELLED);
            else defense.stop(companion, why);
            defense = null;
        }
        // 没有需要跨 tick 释放的身体状态；本次事件记录会在下次休眠检查时关闭，或由新一次入水事件替代。
        if (episodeActive && why != StopReason.PREEMPTED) {
            float healthLost = Math.max(0.0F, attentionStartHealth - companion.getHealth());
            GameplayAttentionMonitor.reflexFinished(
                    id(), "body or reflex unavailable; air recovery unconfirmed", swimTicks,
                    "nearby self-defense may consume weapon durability",
                    healthLost > 0.0F ? "health lost during reflex: " + healthLost : "no health loss observed");
            episodeActive = false;
            worstAir = Integer.MAX_VALUE;
            swimTicks = 0;
            escape = null; search = null; lastProgress = null; stalledTicks = 0; retryTicks = 0;
            trappedNoted = false;
        }
    }

    /**
     * 换气让位：任务正在连续驾驶身体把水排出去（水中攀沿等需要按跳攒抬升的段）时，
     * 每秒接管一次会把驾驶切碎成永远凑不齐的碎片，角色反而更晚离水。让位只在驾驶
     * 段持有身体且空气仍在兜底触发线之上时生效（见驾驶侧 drivesBodyContinuously
     * 的空气守卫）；头顶封闭等真密封处境不受影响——那时驾驶段自己会放弃并把身体交回。
     */
    @Override
    public boolean yieldsToContinuousDriving() {
        return true;
    }

    @Override
    public String name() {
        return "breath";
    }

    @Override
    public String describeCurrentAction() {
        return "氧气不足，正在上浮换气";
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
