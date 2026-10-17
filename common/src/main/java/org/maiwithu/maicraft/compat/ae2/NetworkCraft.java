// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.TaskRecords;

/**
 * 现货不够时在网络里下单补缺口。终端里已经看不到想要的现货、还缺的时候由取货动作来问：
 * 网络里那一条标着有样板，就像玩家一样下单——件数按还缺的、封顶在背包这次装得下的——开工后在终端前等成品进网络再取；
 * AE2 记着角色自己下的这样东西的作业还没结束，就不再下单，接着等它。一次取货只下一单；不补缺料、不加样板、不排队等合成 CPU。
 *
 * <p>只做决定、点下单那几页、看等成品的期限；取货、关界面、重开终端由取货动作做。
 * 下单的结论（开工了、缺什么、为什么没开工）记一条 attempts；下单本身不是背包的变化，不记 change。
 */
final class NetworkCraft {

    /** 取货动作接下来怎么办。 */
    sealed interface Next {
    }

    /** 在下单的几页上，下一刻接着走 {@link #ordering}。 */
    record Ordering() implements Next {
    }

    /**
     * 开着终端在跟前等成品，下一刻再看。jobRunning 为真是 AE2 还记着这一单在做：网络在干活，
     * 取货动作照常报进展，不让外层把这段有期限的等待当成原地打转。
     */
    record Waiting(boolean jobRunning) implements Next {
    }

    /** 开工了：AE2 退回来的终端关掉重新打开，等成品进网络。 */
    record Reopen() implements Next {
    }

    /**
     * 收场。evenIfTaken 为真时，这次就算已经取到一些也按失败交回：下单没成、等不到成品，引擎要划掉这条路换别的，
     * 不该再回来白跑一趟；背包装不下这类引擎腾出地方还能接着取的为假。
     */
    record Stop(Problem problem, boolean evenIfTaken) implements Next {
        Stop {
            Objects.requireNonNull(problem, "problem");
        }
    }

    /** 没样板、也没在等：照旧"网络里没有了"。 */
    record Nothing() implements Next {
    }

    private final Ae2Compat compat;
    private final CraftOrdering.PageSender sender;
    private final String where;
    private final TaskRecords records;
    /** 正在走下单那几页；没在下单为空。 */
    private CraftOrdering ordering;
    /** 在等成品；没在等为空。 */
    private CraftWait wait;
    /** 等的是哪一条（下单的那一条，或之前下单还在做的那一条）。 */
    private Ae2TerminalMenu.StockEntry target;
    /** 这次取货下过单：一次只下一单。 */
    private boolean ordered;
    private int orderedAmount;
    private int takenAtOrder;

    /**
     * @param sender  在此刻那一页上发模组的包
     * @param where   给结果看的终端说法，例如"ME 终端 (3, 64, 0) 北面"
     * @param records 发起任务的记账口，下单的结论记成 attempts；没有记账口为 null
     */
    NetworkCraft(Ae2Compat compat, CraftOrdering.PageSender sender, String where, TaskRecords records) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.where = Objects.requireNonNull(where, "where");
        this.records = records;
    }

    /** 下过单或在等成品：取货动作的总期限要放宽。 */
    boolean involved() {
        return ordered || wait != null;
    }

    /** 正站在终端前等成品。被打断时界面关上、作业留在网络里接着做；回来看到作业还在做就接着等，不重复下单。 */
    boolean waiting() {
        return wait != null;
    }

    /** 在等的是什么，给调试面板与日志看，例如"等网络做好 44 个 minecraft:iron_ingot（到了 30 个）"。 */
    String waitingText() {
        if (wait == null) return "";
        String what = itemId(target.sample());
        return (ordered ? "等网络做好 " + orderedAmount + " 个 " + what : "等网络做好之前下单的 " + what)
                + "（到了 " + wait.arrived() + " 个）";
    }

    /**
     * 终端里已经看不到想要的现货了，这一刻怎么办。
     *
     * @param terminal 此刻开着的终端界面
     * @param stock    终端里的网络存货
     * @param wanted   一种东西算不算想要的
     * @param taken    这次取货已经取到几件
     * @param remaining 还缺几件
     * @param room     背包这次还能装下几件这种东西
     */
    Next noStock(AbstractContainerMenu terminal, List<Ae2TerminalMenu.StockEntry> stock, Predicate<ItemStack> wanted,
            int taken, int remaining, ToIntFunction<ItemStack> room) {
        // 已经拿够：不下单，也不再等。
        if (remaining <= 0) return new Nothing();
        if (wait != null) return waitTick(taken);
        if (ordered) return new Nothing();
        Ae2TerminalMenu.StockEntry craftable = null;
        for (Ae2TerminalMenu.StockEntry entry : stock) {
            if (!entry.craftable() || !wanted.test(entry.sample())) continue;
            // AE2 记着角色自己之前下的这样东西还没做完（被打断、上次等到期限）：不再下一单，接着等它。
            if (compat.craftPending(entry.sample())) {
                target = entry;
                wait = new CraftWait(taken, true);
                return new Waiting(true);
            }
            if (craftable == null) craftable = entry;
        }
        if (craftable == null) return new Nothing();
        // 下几件：还缺的，封顶在背包这次装得下的——做出来拿不走的不下；一件都装不下就不下单。
        int amount = Math.min(remaining, room.applyAsInt(craftable.sample()));
        if (amount <= 0) {
            return new Stop(Problem.of(Problem.Kind.INVENTORY_FULL, "背包装不下了，没在 " + where + " 的网络下单合成"
                    + itemId(craftable.sample())), false);
        }
        ordered = true;
        orderedAmount = amount;
        takenAtOrder = taken;
        target = craftable;
        ordering = new CraftOrdering(compat, sender, craftable, amount);
        return ordering(terminal);
    }

    /** 在下单的几页上走一刻：开工了就记一笔、去重开终端等成品；没开工照抄原因收场。 */
    Next ordering(AbstractContainerMenu current) {
        if (ordering == null) return new Nothing();
        return switch (ordering.tick(current)) {
            case CraftOrdering.Running running -> new Ordering();
            case CraftOrdering.Started started -> {
                ordering = null;
                attempt("开工了");
                wait = new CraftWait(takenAtOrder, compat.craftPending(target.sample()));
                yield new Reopen();
            }
            case CraftOrdering.Stopped stopped -> {
                ordering = null;
                // 缺料的清单可能很长，完整的只写在问题里（引擎会把它摊进最外层），这一条只记结论，不抄第二遍。
                attempt(stopped.kind() == Problem.Kind.NEED_ITEM ? "缺料，没开工" : stopped.reason());
                yield new Stop(Problem.of(stopped.kind(), orderText() + "，" + stopped.reason(),
                        suggestion(stopped.kind())), true);
            }
        };
    }

    // 等成品的一刻：取到了重新计时；两分钟没新的、满五分钟、AE2 报这单结束且网络里没有更多，都收场并说清作业此刻在不在跑。
    private Next waitTick(int taken) {
        boolean pending = compat.craftPending(target.sample());
        CraftWait.Verdict verdict = wait.tick(taken, pending);
        if (verdict == CraftWait.Verdict.WAIT) return new Waiting(pending);
        String head = ordered
                ? orderText() + "，到了 " + wait.arrived() + " 个"
                : where + " 的网络里还在做之前下单的 " + itemId(target.sample()) + "，这次等到 " + wait.arrived() + " 个";
        String message = switch (verdict) {
            case ENDED -> head + "；AE2 报这单已经结束，网络里没有更多的了（可能被取消，或成品被别的机器、玩家拿走）";
            case IDLE -> head + "，两分钟没再出新的" + jobState(pending);
            default -> head + "，等满五分钟没等齐" + jobState(pending);
        };
        String suggestion = pending
                ? "过一会儿再 obtain 会直接取，不会重复下单；或用 machine_inspect 看网络的合成 CPU"
                : null;
        return new Stop(Problem.of(Problem.Kind.STUCK, message, suggestion), true);
    }

    // 收场时作业的情况：AE2 还记着就是还在跑，做好会进网络；不记了就是做完或被取消了。
    private static String jobState(boolean pending) {
        return pending ? "；作业还在网络里跑，做好会进网络" : "；AE2 已经不记着这单了（做完或被取消）";
    }

    // 下单这件事的说法：在哪、几件、什么。
    private String orderText() {
        return "已在 " + where + " 的网络下单合成 " + orderedAmount + " 个 " + itemId(target.sample());
    }

    // 下单的结论记一条 attempts：试的是什么（在哪下单几件什么），结果如何（开工了、缺什么、为什么没开工）。
    private void attempt(String whatHappened) {
        if (records == null) return;
        records.attempt("在 " + where + " 的网络下单合成 " + orderedAmount + " 个 " + itemId(target.sample()),
                whatHappened);
    }

    // 下单没成时给 LLM 的出路：缺料补进网络或换路，CPU 用不上等它空出来或换路。
    private static String suggestion(Problem.Kind kind) {
        return switch (kind) {
            case NEED_ITEM -> "缺的料补进网络，或换别的途径";
            case REFUSED_BY_GAME -> "等合成 CPU 空出来再要，或换别的途径";
            default -> null;
        };
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
