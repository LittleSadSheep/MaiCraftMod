package org.maiwithu.maicraft.core.task.chain;

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

/**
 * 收到生物造成的伤害，或观察到近处明确的攻击目标时，暂时接管当前工作自卫。
 * 危险短暂消失后保留一小段观察时间，避免刚拉开一点距离就把工作还回去，再马上被同一只怪打断。
 * 它没有另一套攻击动作，实际打斗、撤退和拾取都交给 AttackCompanionTask。
 */
public final class MobDefenseChain implements Task, Reflex {

    /** 本能名册里的 id。别处按住这条本能时用它,见 {@code LocalPlayer.pauseReflex}。 */
    public static final String ID = "mob_defense";

    /** 看多远。超出这个半径的不算"身边"。 */
    private static final double SCAN_RADIUS = 12.0;

    /**
     * 寻常近战怪逼到这么近就算危险。
     *
     * <p>爬行者与末影水晶那两条线是从原版推出来的(引信倒退距离、爆炸威力两倍),这一条不是
     * ——它是"它下一步就能打到我"的经验值。要更硬该去读每种怪自己的攻击距离。
     */
    private static final double MELEE_DANGER = 4.0;

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
    private boolean attentionActive;
    private int initialDangerCount;
    private float attentionStartHealth;

    public MobDefenseChain() {
    }

    /**
     * 危险来了就醒。<b>打完不设冷却</b>——没有危险时 {@link #dangersNear} 本来就是空的,
     * 链子自然不会醒,冷却在这里没有作用,只有副作用:那几秒里新出现的危险她一动不动。
     * 实测四次重伤都发生在这个窗口里。
     *
     * <p>冷却原本管的是"退无可退"(老注释:hands the body back to the LLM),但那件事的正解
     * 不是等几秒再试一次,而是{@code cornered} 那一维——退不掉就打。
     */
    @Override
    // 健康尚可且已有明确战斗任务时先让它处理；自己一旦创建了自卫任务，就持续获得执行机会直到该任务结束。
    public boolean canRun(LocalPlayer companion) {
        long now = companion.level().getGameTime();
        // 有人正在替这条本能干活(模型派的 attack),就别抢 —— 除非她已经扛不住,
        // 那一档只有本能看得见。按住的是本能不是目标,所以会分裂的怪不会让它失效。
        if (fight == null && explicitCombatTaskActive() && !Menace.outmatched(companion)) {
            return false;
        }
        if (fight != null) {
            return true;   // 打着呢,打完再说
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
    // 有近处危险就刷新最后危险时刻；还没开打则创建自卫战斗，已经开打就继续同一个 fight。
    public TaskState tick(LocalPlayer companion) {
        if (!dangersNear(companion).isEmpty()) {
            dangerLastSeenTick = companion.level().getGameTime();
        }
        if (fight == null) {
            if (dangersNear(companion).isEmpty()) {
                return TaskState.RUNNING;   // 宽限期里的空转,别开新的一场
            }
            begin(companion);
            return TaskState.RUNNING;
        }
        TaskState state = fight.tick(companion);
        if (state != TaskState.RUNNING) {
            end(companion, state);
        }
        return TaskState.RUNNING;
    }

    /**
     * 开打。<b>无差别</b>:身边的危险不是模型点名的,而且会分裂的怪一裂开,点名就作废了。
     *
     * <p>不设截止时间——它的终点是"没人再追我",由 {@code attack} 自己判;
     * 给一个闹钟只会在打到一半时把她扔在原地。
     */
    // 记下开始时的危险数量和红心生命，报告自动接管原因，然后创建使用普通战斗逻辑的自卫任务。
    private void begin(LocalPlayer companion) {
        long now = companion.level().getGameTime();
        initialDangerCount = dangersNear(companion).size();
        attentionStartHealth = companion.getHealth();
        attentionActive = true;
        GameplayAttentionMonitor.reflexStarted(
                id(), "hostile damage or an immediate nearby threat was observed", "emergency self-defense",
                "weapons, ammunition, food, shields, or durability may be consumed",
                "combat can cause damage or death");
        AttackTaskRecord record = new AttackTaskRecord(
                "reflex-" + now, now + NO_DEADLINE, List.of(), true);
        fight = new AttackCompanionTask(companion, record);
        fight.start(companion);
        org.maiwithu.maicraft.core.Constants.LOG.info("[maicraft-defense] 自动接管 —— 身边 {} 个危险",
                dangersNear(companion).size());
    }

    /** 长到等同于没有截止时间;终点由"没人再追我"说了算。 */
    private static final long NO_DEADLINE = 20L * 60L * 60L * 24L;

    // 取出战斗结果并让它执行清理，再清掉自卫状态、松开输入和报告剩余威胁。
    private void end(LocalPlayer companion, TaskState state) {
        String line = fight.result(state).message();
        fight = null;
        finishAttention(companion, state == TaskState.SUCCESS
                ? "immediate danger handled" : "self-defense ended without confirmed success");
        dangerLastSeenTick = NEVER;
        InputDriver.halt(companion);
        org.maiwithu.maicraft.client.runtime.ClientRuntime.requireContext(companion).body().releaseAll();
        org.maiwithu.maicraft.core.Constants.LOG.info("[maicraft-defense] 收场 {} —— {}", state, line);
        // <b>不急</b>:她的后台任务照跑,黄了自有 task_finished 报。这条只是让主人翻聊天流时
        // 看得懂她刚才为什么打了一架、或者挪了二十格。攒着搭下一轮的车就够。
        org.maiwithu.maicraft.core.Constants.LOG.info(
                "[maicraft-defense] hit danger and handled it on instinct — {}", line);
    }

    // 只报告开始和结束时看到的威胁数与红心差，不把它冒充精确的攻击伤害或资源消耗账单。
    private void finishAttention(LocalPlayer companion, String outcome) {
        if (!attentionActive) return;
        int remaining = dangersNear(companion).size();
        float healthLost = Math.max(0.0F, attentionStartHealth - companion.getHealth());
        GameplayAttentionMonitor.reflexFinished(
                id(), outcome + "; threats " + initialDangerCount + " -> " + remaining, 0,
                "resource consumption is possible; exact combat accounting is not claimed",
                healthLost > 0.0F ? "health lost during reflex: " + healthLost : "no health loss observed");
        attentionActive = false;
        initialDangerCount = 0;
    }

    @Override
    // 被更紧急的行为打断时保留 fight，之后继续；其他停止原因也只停止这份任务，没有在这里把 fight 置空。
    public void stop(LocalPlayer companion, StopReason why) {
        if (fight != null) {
            // 被更急的链抢走(摔落、换气):只松开身体,这场仗的状态一个不动,回来接着打。
            fight.stop(companion, why);
        }
        if (why != StopReason.PREEMPTED) {
            finishAttention(companion, "body or reflex unavailable; combat result unconfirmed");
        }
        InputDriver.halt(companion);
        org.maiwithu.maicraft.client.runtime.ClientRuntime.requireContext(companion).body().releaseAll();
    }

    @Override
    public String name() {
        return ID;
    }

    // ---- Reflex roster paperwork (constitution §6) ----

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "受到生物伤害或发现近处明确威胁时自动自卫，战斗或撤退后恢复工作";
    }

    // ---- 什么算危险 ----

    /**
     * 身边<b>已经近到没有提前量</b>的威胁。
     *
     * <p>只算正在针对她的——防守不是挑衅,一只路过的僵尸猪灵不该被"防御"链招惹。还没逼近的
     * 那些也不进来:模型看得见它们,该由它决定要不要动手。
     *
     * <p>模型自己派的 {@code attack} 已经认领的目标同样不算:那场仗有人管了。但她扛不住时
     * 一律接管——那一档只有本能看得见。
     */
    // 真实伤害不受近战距离限制：骷髅在远处射中玩家也应触发。
    // 若其他模组提供了实际 AI 目标，仍保留原来的近处危险预判；不靠它识别已发生的伤害。
    private List<Mob> dangersNear(LocalPlayer companion) {
        List<Mob> near = new ArrayList<>(CombatThreats.attackers(companion));
        for (Mob m : Menace.hostilesAround(companion, SCAN_RADIUS)) {
            if (near.contains(m) || m.getTarget() != companion) {
                continue;
            }

            // "够危险了没有"与站位、退避问的是<b>同一个函数</b>:它自己的危险半径。
            // 用一条固定的线时每种怪都判错——爬行者要七格,僵尸两格就够。
            if (Menace.tooClose(m, companion)) {
                near.add(m);
            }
        }
        return near;
    }
}
