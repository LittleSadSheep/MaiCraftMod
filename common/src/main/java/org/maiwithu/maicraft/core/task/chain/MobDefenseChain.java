package org.maiwithu.maicraft.core.task.chain;

import com.google.gson.JsonObject;
import org.maiwithu.maicraft.core.combat.Menace;
import org.maiwithu.maicraft.core.combat.CombatThreats;
import org.maiwithu.maicraft.core.task.combat.AttackCompanionTask;
import org.maiwithu.maicraft.core.task.combat.AttackTaskRecord;
import org.maiwithu.maicraft.core.task.survival.SurvivalDecisions;
import org.maiwithu.maicraft.client.runtime.GameplayAttentionMonitor;
import org.maiwithu.maicraft.entity.InputDriver;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.reflex.Reflex;

import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.Constants;

/**
 * 收到生物造成的伤害，或观察到近处苦力怕及明确的攻击目标时，暂时接管当前工作自卫。
 * 危险短暂消失后保留一小段观察时间，避免刚拉开一点距离就把工作还回去，再马上被同一只怪打断。
 * 它没有另一套攻击动作，实际打斗、撤退和拾取都交给 AttackCompanionTask。
 * 从第一场开打到交还身体记为一段插曲（{@link DefenseExcursion}）：开始与结束通知、被打断任务的回执
 * 都报告接管地点、离工位多远和场次，模型不会在角色被带走后还以为她站在原地。
 * 打完后被带离工位太远就先步行回去再交还身体；回不去时暂停被打断的任务，由模型决定接下来怎么做。
 */
public final class MobDefenseChain implements Task, Reflex {

    /** 本能名册里的 id。别处按住这条本能时用它,见 {@code LocalPlayer.pauseReflex}。 */
    public static final String ID = "mob_defense";

    /** 看多远。超出这个半径的不算"身边"。 */
    private static final double SCAN_RADIUS = 14.0; // 充能苦力怕的十二格伤害范围也必须完整进入警戒。

    /**
     * 危险离开后还盯这么久才算真的没事。
     *
     * <p>没有它,一只跟她跑得几乎一样快的怪会在边界上一进一出,每进出一次就重开一场仗。
     */
    private static final long CALM_GRACE_TICKS = 40;

    /** {@link #dangerLastSeenTick} 的"从没见过"哨兵。不参与减法,免得负溢出。 */
    private static final long NEVER = Long.MIN_VALUE;

    /** 自动开的这场仗。null = 这一刻没在打。 */
    private AttackCompanionTask fight;
    /** 最后一刻还看得见危险的游戏时间。 */
    private long dangerLastSeenTick = NEVER;
    /** 这段自卫插曲：第一场开打时建立，把身体交还工作时收尾；null = 没在自卫。 */
    private DefenseExcursion excursion;
    /** 打完后走回工位用的路线；同一时刻只有一段插曲在用它。 */
    private final ReturnRoute route;

    /** 回工位不赶时间，与夜间休息返回工位一样只走不跑，省下饥饿值。 */
    private static final double RETURN_SPEED = 0.8;

    // 正式运行：回工位沿只走不改地形的普通步行路线。
    public MobDefenseChain() {
        this(new WalkingRoute());
    }

    // 回放测试借此换掉真实寻路，按脚本给出走着、到达或失败。
    public MobDefenseChain(ReturnRoute route) {
        this.route = route;
    }

    /**
     * 危险来了就醒。<b>打完不设冷却</b>——没有危险时 {@link #dangersNear} 本来就是空的,
     * 链子自然不会醒,冷却在这里没有作用,只有副作用:那几秒里新出现的危险她一动不动。
     * 实测四次重伤都发生在这个窗口里。
     *
     * <p>冷却原本管的是"退无可退"（旧注释：把角色控制权交还给 LLM），但那件事的正解
     * 不是等几秒再试一次,而是{@code cornered} 那一维——退不掉就打。
     */
    @Override
    // 健康尚可且已有明确战斗任务时先让它处理；自己一旦创建了自卫任务，就持续获得执行机会直到该任务结束。
    public boolean canRun(LocalPlayer companion) {
        long now = companion.level().getGameTime();
        if (fight != null) {
            return true;   // 打着呢,打完再说
        }
        // 插曲还没收尾时继续拿身体一刻，由 tick 交结束通知和任务账，不让工作在无人报告的情况下悄悄续上。
        if (excursion != null) {
            return true;
        }
        // 有人正在替这条本能干活(模型派的 attack),就别抢 —— 除非她已经扛不住,
        // 那一档只有本能看得见。按住的是本能不是目标,所以会分裂的怪不会让它失效。
        if (explicitCombatTaskActive() && !Menace.outmatched(companion)) {
            return false;
        }
        if (SurvivalDecisions.mobDefenseTriggered(!dangersNear(companion).isEmpty())) {
            return true;
        }
        // 宽限期内不撒手:怪刚出半径不代表没事了,这一刻放手下一刻就得重来。
        return dangerLastSeenTick != NEVER && now - dangerLastSeenTick < CALM_GRACE_TICKS;
    }

    // 既识别直接的 attack 任务，也识别总目标当前步骤的 maicraft:combat，避免自卫重复抢同一场战斗。
    private static boolean explicitCombatTaskActive() {
        TaskRecord active = CompanionTickDispatcher.current();
        if (active == null) return false;
        if (AttackTaskRecord.TOOL_NAME.equals(active.getToolName())) return true;
        if (active instanceof IntentTaskRecord intent && !intent.paused()) {
            int step = intent.stepIndex();
            return step >= 0 && step < intent.steps().size()
                    && "maicraft:combat".equals(intent.steps().get(step).ability());
        }
        return false;
    }

    @Override
    // 有近处危险就刷新最后危险时刻并量一次离工位多远；已开打就继续同一个 fight，
    // 没开打而危险仍在就开下一场，危险与宽限都过去后收尾这段插曲。
    public TaskState tick(LocalPlayer companion) {
        boolean danger = !dangersNear(companion).isEmpty();
        if (danger) {
            dangerLastSeenTick = companion.level().getGameTime();
        }
        if (excursion != null) {
            excursion.observe(companion);
        }
        if (fight != null) {
            TaskState state = fight.tick(companion);
            if (state != TaskState.RUNNING) {
                end(companion, state);
            }
            return TaskState.RUNNING;
        }
        // 模型已经亲自派了战斗、且她还扛得住时不再另开一场，只把这段插曲如实收尾。
        if (danger && !(explicitCombatTaskActive() && !Menace.outmatched(companion))) {
            begin(companion);
            return TaskState.RUNNING;
        }
        if (!danger && dangerLastSeenTick != NEVER
                && companion.level().getGameTime() - dangerLastSeenTick < CALM_GRACE_TICKS) {
            // 宽限期里的空转,别开新的一场；回位路线也先停下，宽限过后从当时位置重新规划。
            if (excursion != null) excursion.suspendWalk();
            return TaskState.RUNNING;
        }
        calm(companion);
        return TaskState.RUNNING;
    }

    /**
     * 开打。<b>无差别</b>:身边的危险不是模型点名的,而且会分裂的怪一裂开,点名就作废了。
     *
     * <p>不设截止时间——它的终点是"没人再追我",由 {@code attack} 自己判;
     * 给一个闹钟只会在打到一半时把她扔在原地。
     */
    // 第一场开打时建立插曲并报告接管地点和被打断的任务；同一插曲里的后续场次只累计，然后创建使用普通战斗逻辑的自卫任务。
    private void begin(LocalPlayer companion) {
        long now = companion.level().getGameTime();
        if (excursion == null) {
            excursion = new DefenseExcursion(companion, dangersNear(companion).size(), route);
            GameplayAttentionMonitor.reflexStarted(
                    id(), "hostile damage or an immediate nearby threat was observed", "emergency self-defense",
                    "weapons, ammunition, food, shields, or durability may be consumed",
                    "combat can cause damage or death", excursion.startedFacts());
        }
        excursion.fightStarted();
        AttackTaskRecord record = new AttackTaskRecord(
                "reflex-" + now, now + NO_DEADLINE, List.of(), true);
        fight = new AttackCompanionTask(companion, record);
        fight.start(companion);
        Constants.LOG.info("[maicraft-defense] 自动接管 —— 身边 {} 个危险",
                dangersNear(companion).size());
    }

    /** 长到等同于没有截止时间;终点由"没人再追我"说了算。 */
    private static final long NO_DEADLINE = 20L * 60L * 60L * 24L;

    // 取出战斗结果并让它执行清理，松开输入后记下战果；附近已无危险就开始回位或收尾，仍有危险则下一刻接着开下一场。
    private void end(LocalPlayer companion, TaskState state) {
        String line = fight.result(state).message();
        fight = null;
        lastFightConfirmed = state == TaskState.SUCCESS;
        dangerLastSeenTick = NEVER;
        InputDriver.halt(companion);
        ClientRuntime.requireContext(companion).body().releaseAll();
        Constants.LOG.info("[maicraft-defense] 收场 {} —— {}", state, line);
        // <b>不急</b>:她的后台任务照跑,黄了自有 task_finished 报。这条只是让主人翻聊天流时
        // 看得懂她刚才为什么打了一架、或者挪了二十格。攒着搭下一轮的车就够。
        Constants.LOG.info(
                "[maicraft-defense] hit danger and handled it on instinct — {}", line);
        if (dangersNear(companion).isEmpty()) calm(companion);
    }

    // 危险都过去后：离工位太远就继续往回走、身体仍由自卫占着；不需要回、已经回到或回不去时收尾插曲。
    private void calm(LocalPlayer companion) {
        if (excursion != null && excursion.walkBack(companion)) return;
        settle(companion);
    }

    /** 最后一场是否确认脱险；插曲结束通知以它说明战果。 */
    private boolean lastFightConfirmed;

    // 危险与回位都结束后收尾：发结束通知并给被打断的任务记账，再把身体交还工作。
    private void settle(LocalPlayer companion) {
        close(companion, lastFightConfirmed
                ? "immediate danger handled" : "self-defense ended without confirmed success");
    }

    // 只报告开始和结束时看到的威胁数、红心差与位移事实，不把它冒充精确的攻击伤害或资源消耗账单。
    private void close(LocalPlayer companion, String outcome) {
        DefenseExcursion ended = excursion;
        excursion = null;
        if (ended == null) return;
        long now = companion.level().getGameTime();
        ended.abandonWalk("reflex_stopped");
        // 回不去就先暂停被打断的任务，结束通知里的任务状态因此如实写“已暂停”，不让它在新地点悄悄续上。
        ended.pauseStrandedTask(companion, now);
        String summary = outcome + "; threats " + ended.initialThreats() + " -> " + dangersNear(companion).size();
        JsonObject facts = ended.finishedFacts(companion);
        // 先记任务账再发通知：模型被通知唤醒后去查任务，已经能看到这段插曲。
        ended.recordOnTask(facts, summary, now);
        float healthLost = ended.healthLost(companion);
        GameplayAttentionMonitor.reflexFinished(
                id(), summary, 0,
                "resource consumption is possible; exact combat accounting is not claimed",
                healthLost > 0.0F ? "health lost during reflex: " + healthLost : "no health loss observed", facts);
    }

    @Override
    // 被更紧急的行为打断时保留 fight，之后继续；其他停止原因也只停止这份任务，没有在这里把 fight 置空。
    // 回位路线不跨抢占保留：先停下，拿回身体后从当时位置重新规划。
    public void stop(LocalPlayer companion, StopReason why) {
        if (fight != null) {
            // 被更急的链抢走(摔落、换气):只松开身体,这场仗的状态一个不动,回来接着打。
            fight.stop(companion, why);
        }
        if (excursion != null) excursion.suspendWalk();
        if (why != StopReason.PREEMPTED) {
            close(companion, "body or reflex unavailable; combat result unconfirmed");
        }
        InputDriver.halt(companion);
        ClientRuntime.requireContext(companion).body().releaseAll();
    }

    @Override
    public String name() {
        return ID;
    }

    // ---- 自卫链登记信息：供本能任务表稳定识别此链并向调度器提供说明 ----

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "受到生物伤害或发现近处明确威胁时自动自卫；战斗或撤退后被带离工位太远就步行回去再恢复工作，"
                + "回不去时暂停任务交给模型决定";
    }

    // ---- 什么算危险 ----

    /**
     * 身边<b>已经近到没有提前量</b>的威胁。
     *
     * <p>苦力怕在近处主动警戒；其他生物依赖真实伤害或明确攻击目标，避免招惹中立生物。
     *
     * <p>模型自己派的 {@code attack} 已经认领的目标同样不算:那场仗有人管了。但她扛不住时
     * 一律接管——那一档只有本能看得见。
     */
    // 真实伤害不受近战距离限制：骷髅在远处射中玩家也应触发。
    // 若其他模组提供了实际 AI 目标，仍保留原来的近处危险预判；不靠它识别已发生的伤害。
    private List<Mob> dangersNear(LocalPlayer companion) {
        List<Mob> near = new ArrayList<>(CombatThreats.attackers(companion));
        for (Mob m : Menace.hostilesAround(companion, SCAN_RADIUS)) {
            if (near.contains(m)) continue;
            // 苦力怕必须在爆炸前主动警戒；其他敌人的预判仍要求明确攻击目标，实际伤害另由上方记录。
            if (Menace.creeperThreat(m, companion)
                    || m.getTarget() == companion && Menace.tooClose(m, companion)) {
                near.add(m);
            }
        }
        return near;
    }

    // ---- 回工位路线 ----

    /** 自卫打完后朝工位走的路线；每刻走一步，失败后由调用方停下并在下一刻重新规划。 */
    public interface ReturnRoute {
        PlayerNav.Status step(LocalPlayer player, NavGoal workSite, BooleanSupplier arrived);

        /** 最近一次失败的类别与原因，供结束通知如实转述。 */
        String failure();

        void stop();
    }

    /** 正式回位路线：首步按当前位置建一条只走不改地形、不乘交通工具的步行导航；停下或失败后丢弃，下一步重新规划。 */
    private static final class WalkingRoute implements ReturnRoute {
        private PlayerNav nav;
        private String failure;

        @Override
        public PlayerNav.Status step(LocalPlayer player, NavGoal workSite, BooleanSupplier arrived) {
            if (nav == null) nav = PlayerNav.toGoal(player, () -> workSite, RETURN_SPEED, arrived).walkingOnly();
            PlayerNav.Status status = nav.tick();
            // 导航释放后失败原因就读不到了，先记下再停。
            if (status == PlayerNav.Status.FAILED) failure = nav.failType() + ": " + nav.failReason();
            if (status != PlayerNav.Status.RUNNING) stop();
            return status;
        }

        @Override
        public String failure() {
            return failure;
        }

        @Override
        public void stop() {
            if (nav != null) nav.stop();
            nav = null;
        }
    }
}
