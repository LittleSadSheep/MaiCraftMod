// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.game.player.EverydayFood;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 饿了身上没吃的：像玩家一样去弄点现成的口粮。按面包、熟肉、熟鱼、蔬菜、水果几类挨个问拿东西的引擎哪一类最近
 * （网络里、箱子里有就拿，没有再做、再打猎），挑最近的那一类拿几件；有预算，超时或哪儿都没有如实说。
 * 没弄满也不要紧：吃饭任务每刻看身上，弄到第一件这会儿能吃的（打到的生肉、烤好的第一块熟肉）就把这里叫停、先吃。
 */
final class FetchesFood implements Action {

    /** 口粮按这几类找（通用物品标签）；一样近时排前面的先。 */
    static final List<String> KINDS = List.of("c:foods/bread", "c:foods/cooked_meat", "c:foods/cooked_fish",
            "c:foods/vegetable", "c:foods/fruit");
    /** 一次问几件：箱子、网络里一把拿够吃一阵，不囤；要打猎、烧炼的弄到第一件就会被叫停先吃。 */
    static final int COUNT = 8;

    private final ItemNeeds needs;
    private final long budgetTicks;
    private long deadline = -1;
    private Action current;
    private String chosen;
    /** 已经去弄过、没弄成的几类：换下一类，不在同一类上反复。 */
    private final Set<String> tried = new HashSet<>();
    private Problem lastFailure;

    FetchesFood(ItemNeeds needs, long budgetTicks) {
        this.needs = Objects.requireNonNull(needs, "needs");
        this.budgetTicks = budgetTicks;
    }

    @Override public ActionStatus tick(TickContext context) {
        if (deadline < 0) deadline = context.gameTick() + budgetTicks;
        if (context.gameTick() > deadline) {
            if (current != null) current.close();
            return ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                    "弄吃的过了 " + budgetTicks / 20 + " 秒还没弄到" + (chosen == null ? "" : "（在弄" + chosen + "）"),
                    "给角色一些普通食物"));
        }
        if (current == null) {
            Optional<ItemRequest> nearest = nearestKind();
            if (nearest.isEmpty()) {
                if (lastFailure != null) return ActionStatus.failed(lastFailure);
                return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM,
                        "饿了，身上没有口粮，附近、箱子里、网络里也都没找到现成的面包、熟肉、熟鱼、蔬菜、水果或别的能吃的",
                        "给角色一些普通食物，或种地、养殖"));
            }
            tried.add(nearest.get().wanted().specifier());
            chosen = nearest.get().wanted().describe();
            current = needs.actionFor(nearest.get(), Permissions.DEFAULT);
        }
        ActionStatus status = current.tick(context);
        // 这一类问价时说有、去弄却没弄成（报价太乐观）：换下一类，不就此饿着。
        if (status instanceof ActionStatus.Failed failed) {
            current.close();
            current = null;
            lastFailure = failed.problem();
            return ActionStatus.progressed();
        }
        return status;
    }

    // 挨类问一句要走多远（只问价不拿）：问得出的里面挑最近的；面包、熟肉这几类都弄不到，
    // 再问"能吃的口粮"一大类（模组食物、生肉都算），不因为叫不出名字就饿着。
    private Optional<ItemRequest> nearestKind() {
        Optional<ItemRequest> named = nearestOf(KINDS);
        return named.isPresent() ? named : nearestOf(List.of(EverydayFood.TAG));
    }

    private Optional<ItemRequest> nearestOf(List<String> kinds) {
        ItemRequest best = null;
        double bestDistance = Double.MAX_VALUE;
        for (String kind : kinds) {
            if (tried.contains(WantedItem.ofTag(kind).specifier())) continue;
            ItemRequest request = new ItemRequest(WantedItem.ofTag(kind), COUNT, "饿了，弄点吃的");
            Optional<Double> distance = needs.nearestDistanceBlocks(request, Permissions.DEFAULT);
            if (distance.isPresent() && distance.get() < bestDistance) {
                best = request;
                bestDistance = distance.get();
            }
        }
        return Optional.ofNullable(best);
    }

    @Override public void pause() {
        if (current != null) current.pause();
    }

    @Override public void close() {
        if (current != null) current.close();
    }

    @Override public Interruptibility interruptibility() {
        return current == null ? Interruptibility.BETWEEN_ACTIONS : current.interruptibility();
    }

    @Override public String describe() {
        return chosen == null ? "找能弄到的口粮" : "去弄" + chosen;
    }
}
