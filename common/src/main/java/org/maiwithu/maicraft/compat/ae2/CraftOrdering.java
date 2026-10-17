// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.world.inventory.AbstractContainerMenu;

import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 在 ME 终端里下单合成一条：像玩家一样点那一条"合成"→ 填数量页填件数、定下并选"算完能做就开工"→ 确认页看结论。
 * 料齐时 AE2 当场开工并退回终端；缺料、开工被拒时停在确认页，照抄 AE2 写的收场。
 *
 * <p>每一页都等它画出来、本刻能动手才点（经 {@link PageSender}），点完看界面换没换页，同一页不点第二下。
 * 这里只认此刻的界面是哪一页；关界面、重开终端由取货动作做。
 */
final class CraftOrdering {

    /** 点了一下等换页的期限（刻）；一直点不出去也按这个期限收场。 */
    static final int PAGE_TICKS = 60;
    /** 确认页上网络算料的期限（刻）：大网络、长配方链算得慢，给 30 秒；类别：玩家常识。 */
    static final int CALCULATE_TICKS = 20 * 30;
    /** 最多在这几页上耗多久（刻）：取货动作按它放宽总期限。 */
    static final int MOST_TICKS = PAGE_TICKS * 3 + CALCULATE_TICKS;

    /** 在此刻那一页上发模组的包。 */
    interface PageSender {
        /**
         * 本刻有交互机会、上一下结清、界面画过才发，返回 true；发不了返回 false，下一刻再试。
         *
         * @param what 这一下在做什么，写进日志
         * @param send 真正发包的那一下
         */
        boolean send(String what, Runnable send);
    }

    /** 这一刻走到哪了。 */
    sealed interface Step {
    }

    /** 还在这几页上，下一刻接着看。 */
    record Running() implements Step {
    }

    /** 网络开工了：界面退回了终端，或 AE2 已经记着这一单。 */
    record Started() implements Step {
    }

    /** 没开工。reason 是给 LLM 看的一句原因，能照抄 AE2 的就照抄。 */
    record Stopped(Problem.Kind kind, String reason) implements Step {
        Stopped {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** 此刻该在哪一页上。 */
    private enum Page { TERMINAL, AMOUNT, CONFIRM }

    private final Ae2Compat compat;
    private final PageSender sender;
    private final Ae2TerminalMenu.StockEntry entry;
    private final int amount;
    private Page page = Page.TERMINAL;
    /** 这一页上的那一下发出去了没有。 */
    private boolean sent;
    /** 在这一页上过了几刻（换页从零数）。 */
    private int ticks;
    /** 确认页算完以后又过了几刻（等 AE2 自己开工）。 */
    private int calculatedTicks;

    /**
     * @param entry  终端里要下单的那一条（标着有样板）
     * @param amount 下几件
     */
    CraftOrdering(Ae2Compat compat, PageSender sender, Ae2TerminalMenu.StockEntry entry, int amount) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.entry = Objects.requireNonNull(entry, "entry");
        if (amount <= 0) throw new IllegalArgumentException("下单件数要大于零：" + amount);
        this.amount = amount;
    }

    /** 看一眼此刻的界面，走一步。 */
    Step tick(AbstractContainerMenu current) {
        ticks++;
        return switch (page) {
            case TERMINAL -> onTerminal(current);
            case AMOUNT -> onAmount(current);
            case CONFIRM -> onConfirm(current);
        };
    }

    // 终端上点那一条"合成"：服务端打开填数量页。本刻点不出去就下一刻再点，到期限还没打开如实收场。
    private Step onTerminal(AbstractContainerMenu current) {
        if (compat.craftAmountMenu(current)) return turnTo(Page.AMOUNT, current);
        if (!sent) {
            if (compat.networkLinked(current).isEmpty()) {
                return new Stopped(Problem.Kind.STUCK, "要下单时终端界面已经不在了");
            }
            if (sender.send("在 ME 终端下单合成", () -> compat.requestCraft(current, entry.serial()))) {
                sent = true;
                ticks = 0;
            }
        }
        if (ticks > PAGE_TICKS) {
            return new Stopped(Problem.Kind.STUCK, sent ? "点了合成，没打开填数量的界面" : "终端界面一直点不了合成");
        }
        return new Running();
    }

    // 填数量页：填件数，定下并选"算完能做就开工"（玩家按住 Shift 点下一步），服务端换成确认页去算料。
    private Step onAmount(AbstractContainerMenu current) {
        if (compat.craftPlanPage(current).isPresent()) return turnTo(Page.CONFIRM, current);
        if (!compat.craftAmountMenu(current)) return left(current);
        if (!sent && sender.send("填合成数量 " + amount + "，算完能做就开工",
                () -> compat.confirmCraft(current, amount))) {
            sent = true;
            ticks = 0;
        }
        if (ticks > PAGE_TICKS) {
            return new Stopped(Problem.Kind.STUCK, sent ? "定了合成数量，网络一直没反应" : "填数量的界面一直点不了");
        }
        return new Running();
    }

    // 确认页：被拒、缺料照抄收场；还在算就等，算不完如实说；算完不缺也没被拒，AE2 应当马上开工退回终端。
    private Step onConfirm(AbstractContainerMenu current) {
        Optional<Ae2TerminalMenu.CraftPlanPage> shown = compat.craftPlanPage(current);
        if (shown.isEmpty()) {
            // 从确认页退回了填数量页：AE2 不会自己这么做，多半是有人点了返回，这一单没开工。
            if (compat.craftAmountMenu(current)) {
                return new Stopped(Problem.Kind.STUCK, "确认页退回了填数量的界面，没开工");
            }
            return left(current);
        }
        Ae2TerminalMenu.CraftPlanPage plan = shown.get();
        if (plan.refusal().isPresent()) {
            return new Stopped(Problem.Kind.REFUSED_BY_GAME, "AE2 没开工：" + refusalText(plan.refusal().get()));
        }
        if (!plan.missing().isEmpty()) {
            return new Stopped(Problem.Kind.NEED_ITEM, "网络算了料，缺：" + String.join("、", plan.missing()));
        }
        if (!plan.calculated()) {
            return ticks > CALCULATE_TICKS
                    ? new Stopped(Problem.Kind.STUCK, "网络算料算了 " + CALCULATE_TICKS / 20 + " 秒还没算完")
                    : new Running();
        }
        return ++calculatedTicks > PAGE_TICKS
                ? new Stopped(Problem.Kind.STUCK, "网络算完了料也够，却一直没开工")
                : new Running();
    }

    // 换到下一页：从零数这一页的时间，并当刻就看一眼这一页（换页那一刻不白等）。
    private Step turnTo(Page next, AbstractContainerMenu current) {
        page = next;
        sent = false;
        ticks = 0;
        return tick(current);
    }

    // 离开了下单的几页：退回终端就是开工了（AE2 只在开工成功后退回终端）；界面被关上或换成别的，
    // 看 AE2 记没记着这一单——记着就是开工了，没记着说不清，如实收场。
    private Step left(AbstractContainerMenu current) {
        if (compat.networkLinked(current).isPresent() || compat.craftPending(entry.sample())) {
            return new Started();
        }
        return new Stopped(Problem.Kind.STUCK, "下单的界面被关上了，没能确认开没开工");
    }

    /** AE2 拒绝开工的原因写成一句话：照它的错误码与详情，合成 CPU 用不上时写清几台忙、几台太小、几台断电。 */
    static String refusalText(Ae2TerminalMenu.CraftRefusal refusal) {
        return switch (refusal.code()) {
            case "NO_CPU_FOUND" -> "网络里没有合成 CPU";
            case "NO_SUITABLE_CPU_FOUND" -> "网络里的合成 CPU 都用不上（" + unsuitable(refusal) + "）";
            case "CPU_BUSY" -> "选的合成 CPU 正在忙";
            case "CPU_OFFLINE" -> "选的合成 CPU 断电或缺频道";
            case "CPU_TOO_SMALL" -> "选的合成 CPU 存储太小，装不下这一单";
            case "MISSING_INGREDIENT" -> "开工时缺料" + (refusal.missing().isEmpty() ? "" : "：" + refusal.missing())
                    + "（算完以后被取走了）";
            case "INCOMPLETE_PLAN" -> "计划缺料";
            default -> "AE2 拒绝开工（" + refusal.code() + "）";
        };
    }

    // 合成 CPU 都用不上的几种情况，有几台写几台。
    private static String unsuitable(Ae2TerminalMenu.CraftRefusal refusal) {
        List<String> parts = new ArrayList<>();
        if (refusal.busy() > 0) parts.add(refusal.busy() + " 台在忙");
        if (refusal.tooSmall() > 0) parts.add(refusal.tooSmall() + " 台存储太小");
        if (refusal.offline() > 0) parts.add(refusal.offline() + " 台断电或缺频道");
        return parts.isEmpty() ? "没有合适的" : String.join("、", parts);
    }
}
