// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.task;

import org.maiwithu.maicraft.kernel.outcome.Attempt;
import org.maiwithu.maicraft.kernel.outcome.Blocker;
import org.maiwithu.maicraft.kernel.outcome.Effect;
import org.maiwithu.maicraft.kernel.outcome.Facts;
import org.maiwithu.maicraft.kernel.outcome.Outcome;
import org.maiwithu.maicraft.kernel.progress.ProgressMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 阶段式执行器：v2 所有执行器的基类（docs/design/08 第 3 节）。
 *
 * <p>执行器的进度用一个阶段枚举表达，例如睡觉的"选床 → 拿床 → 放床 → 走到床边 → 躺下"。
 * 子类只写两件事：进入某阶段时准备什么子步骤（{@link #enter}），以及每刻这个阶段怎么走（{@link #tick(Enum, TickContext)}）。
 *
 * <p>下面这些横切的事情由基类统一处理，子类不再各写一遍：
 * <ul>
 *   <li>换阶段时关闭旧子步骤、准备新子步骤，并写日志；</li>
 *   <li>被抢占时暂停子步骤，结束时收尾子步骤；</li>
 *   <li>停滞判定：子步骤报告的真实进展才算进展，<b>换阶段本身不算</b>，
 *       否则在两个阶段之间来回跳的执行器永远不会被判为原地打转；</li>
 *   <li>累积执行过程中确认的效果、未确认的操作和尝试过的办法，结束时组装成统一回执；</li>
 *   <li>被替换、被取消或失去身体时，照样如实交代已经发生了什么。</li>
 * </ul>
 *
 * <p>执行器里的异常不在这里捕获：由驱动它的子任务监护统一转成 INTERNAL 卡点。
 */
public abstract class PhasedExecutor<P extends Enum<P>> implements Task {
    private static final Logger LOG = LoggerFactory.getLogger(PhasedExecutor.class);

    private final String label;
    private final ProgressMeter progress;
    private final List<Effect> achieved = new ArrayList<>();
    private final List<Effect> uncertain = new ArrayList<>();
    private final List<Attempt> attempts = new ArrayList<>();
    private P phase;
    private Step step;
    private boolean entered;
    private Outcome result;

    /**
     * @param label      这件事的名字，用于面板、日志和回执，例如"睡觉"
     * @param firstPhase 第一个阶段
     * @param progress   本执行器的进度表；停滞窗口和总上限由能力按自己的节奏决定
     */
    protected PhasedExecutor(String label, P firstPhase, ProgressMeter progress) {
        this.label = Objects.requireNonNull(label, "label");
        this.phase = Objects.requireNonNull(firstPhase, "firstPhase");
        this.progress = Objects.requireNonNull(progress, "progress");
    }

    /** 进入某个阶段时调用一次：返回这个阶段要用的子步骤；纯决策的阶段返回 null。 */
    protected abstract Step enter(P phase);

    /** 每刻推进当前阶段：留在原阶段、换阶段、完成或失败。 */
    protected abstract Next<P> tick(P phase, TickContext context);

    /** 本能力特有的回执事实；默认没有。 */
    protected Facts facts() {
        return Facts.NONE;
    }

    /** 没完成就结束时，还剩哪些部分没做；默认不列。 */
    protected List<String> remaining() {
        return List.of();
    }

    /** 阶段的中文名，用于面板与日志；默认用枚举名。 */
    protected String describePhase(P value) {
        return value.name();
    }

    /** 换阶段后的扩展点，例如向 Attention 发进度事件；默认什么都不做。 */
    protected void onTransition(P from, P to, String why) {}

    /** 当前阶段。 */
    protected final P phase() {
        return phase;
    }

    /** 当前阶段的子步骤；纯决策的阶段为 null。 */
    protected final Step step() {
        return step;
    }

    /** 推进当前子步骤一刻；子步骤报告的真实进展会自动记到进度表上。 */
    protected final StepStatus run(TickContext context) {
        if (step == null) {
            throw new IllegalStateException(label + " 的阶段 " + phase + " 没有子步骤，不能调用 run()");
        }
        StepStatus status = step.tick(context);
        if (status instanceof StepStatus.Running running && running.progressed()
                || status instanceof StepStatus.Done) {
            progress.advanced(step.describe());
        }
        return status;
    }

    /**
     * 推进当前子步骤一刻，并按结果决定走向：还在做就留在本阶段；失败就把卡点原样上抛；做完了才执行给定的转移。
     * 例：{@code case APPROACH -> runThen(context, () -> Next.go(Phase.LIE_DOWN, "到床边了"));}
     */
    protected final Next<P> runThen(TickContext context, Supplier<Next<P>> whenDone) {
        return switch (run(context)) {
            case StepStatus.Running running -> Next.stay();
            case StepStatus.Done done -> whenDone.get();
            case StepStatus.Failed failed -> Next.fail(failed.blocker());
        };
    }

    /** 报告一次不经过子步骤的真实进展，例如决策阶段确认选中了一张可用的床。 */
    protected final void progressed(String signal) {
        progress.advanced(signal);
    }

    /** 记下一条已确认发生的效果，结束时进入回执的 achieved。 */
    protected final void achieved(Effect effect) {
        achieved.add(Objects.requireNonNull(effect, "effect"));
    }

    /** 记下一条已提交但无法确认结果的操作，结束时进入回执的 uncertain；这类操作不能盲目重试。 */
    protected final void uncertain(Effect effect) {
        uncertain.add(Objects.requireNonNull(effect, "effect"));
    }

    /** 记下一次尝试过的办法及结果，结束时进入回执的 attempts。 */
    protected final void attempt(String strategy, String outcome) {
        attempts.add(new Attempt(strategy, outcome));
    }

    @Override
    public final void start(TickContext context) {
        enterIfNeeded();
    }

    @Override
    public final TaskStatus tick(TickContext context) {
        if (result != null) return TaskStatus.finished(result);
        enterIfNeeded();
        progress.tick();
        TaskStatus status = apply(tick(phase, context));
        if (status instanceof TaskStatus.Running) {
            // 先让本刻的子步骤有机会报告进展，再判断是否原地打转。
            ProgressMeter.Verdict verdict = progress.verdict();
            if (verdict instanceof ProgressMeter.Verdict.Stalled stalled) {
                return finish(failedOutcome(Blocker.of(Blocker.Kind.STALLED,
                        "自「" + stalled.lastSignal() + "」之后推进了 " + stalled.idleTicks() + " 刻都没有新进展")));
            }
            if (verdict instanceof ProgressMeter.Verdict.OverCap overCap) {
                return finish(failedOutcome(Blocker.of(Blocker.Kind.STALLED,
                        "已推进 " + overCap.activeTicks() + " 刻，超过这件事的总时长上限")));
            }
        }
        return status;
    }

    @Override
    public final void pause() {
        if (step != null) step.pause();
    }

    @Override
    public final Outcome close(CloseReason reason) {
        closeStep();
        if (result != null) return result;
        // 没走到结局就结束：照样交代已经发生的效果和没做完的部分，不能当作什么都没发生。
        String why = switch (reason) {
            case FINISHED -> "结束";
            case REPLACED -> "被新的任务替换";
            case CANCELLED -> "被取消";
            case BODY_GONE -> "失去身体（死亡、断线或换世界）";
        };
        result = Outcome.builder(Outcome.Status.CANCELLED, label + "：" + why)
                .achieved(achieved).uncertain(uncertain).attempts(attempts)
                .remaining(remaining()).facts(facts()).build();
        return result;
    }

    @Override
    public Interruptibility interruptibility(TickContext context) {
        // 没有进行中的子步骤，说明正处在阶段之间，舒适需求可以趁这个空档插进来。
        return step == null ? Interruptibility.FREE : step.interruptibility();
    }

    @Override
    public final String describe() {
        String text = label + "：" + describePhase(phase);
        return step == null ? text : text + "，" + step.describe();
    }

    private void enterIfNeeded() {
        if (entered) return;
        entered = true;
        step = enter(phase);
    }

    private TaskStatus apply(Next<P> next) {
        return switch (next) {
            case Next.Stay<P> stay -> TaskStatus.RUNNING;
            case Next.Go<P> go -> {
                P from = phase;
                closeStep();
                phase = go.phase();
                entered = false;
                enterIfNeeded();
                LOG.debug("{}：{} → {}（{}）", label, from, phase, go.why());
                onTransition(from, phase, go.why());
                yield TaskStatus.RUNNING;
            }
            case Next.Done<P> done -> finish(merge(done.outcome()));
            case Next.Fail<P> fail -> finish(failedOutcome(fail.blocker()));
        };
    }

    // 子类在 Next.done 里只需写结论与事实；执行过程中用 achieved() 等记下的内容在这里补进回执。
    private Outcome merge(Outcome outcome) {
        Outcome.Builder builder = outcome.toBuilder()
                .achieved(achieved).uncertain(uncertain).attempts(attempts);
        if (outcome.status() != Outcome.Status.DONE && outcome.remaining().isEmpty()) {
            builder.remaining(remaining());
        }
        if (outcome.facts() == Facts.NONE) builder.facts(facts());
        return builder.build();
    }

    private Outcome failedOutcome(Blocker blocker) {
        return Outcome.builder(Outcome.Status.FAILED, label + "没有完成：" + blocker.fact())
                .blocker(blocker).achieved(achieved).uncertain(uncertain).attempts(attempts)
                .remaining(remaining()).facts(facts()).build();
    }

    private TaskStatus finish(Outcome outcome) {
        closeStep();
        result = outcome;
        return TaskStatus.finished(outcome);
    }

    private void closeStep() {
        if (step != null) {
            step.close();
            step = null;
        }
    }
}
