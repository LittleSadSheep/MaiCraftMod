// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireRoute;

/**
 * 测试替身：一个按剧本回答的物品来源。问价按排好的答案依次回答；
 * 动手按设定的方式收场——往背包里放货、以问题失败、装模作样做完却一件不给、或干脆接不上。
 */
final class ScriptedSource implements ItemSource {

    /** 动手时怎么收场。 */
    enum Ending {
        /** 往背包里放进报价认的数，然后做完。 */
        DELIVER,
        /** 以问题失败。 */
        FAIL,
        /** 做完了，但一件都没进背包（白跑一趟）。 */
        DELIVER_NOTHING,
        /** 接不上，返回 empty。 */
        UNAVAILABLE
    }

    final String name;
    /** 这条来源自报的途径；核对 via 只走指定路时用它。 */
    private final AcquireRoute route;
    private final FakeBackpack backpack;
    private final Deque<SourceQuote> quotes = new ArrayDeque<>();
    private SourceQuote lastAnswer;
    private Ending ending = Ending.DELIVER;
    private int asked;
    private int begun;

    ScriptedSource(String name, FakeBackpack backpack) {
        this(name, AcquireRoutes.CARRIED, backpack);
    }

    ScriptedSource(String name, AcquireRoute route, FakeBackpack backpack) {
        this.name = name;
        this.route = route;
        this.backpack = backpack;
    }

    ScriptedSource answer(SourceQuote quote) {
        quotes.add(quote);
        return this;
    }

    ScriptedSource onBegin(Ending ending) {
        this.ending = ending;
        return this;
    }

    int askedTimes() {
        return asked;
    }

    /** 动手了几次：核对"只用了一条路"时看它，问价与动手是两回事。 */
    int begunTimes() {
        return begun;
    }

    @Override public String describe() {
        return name;
    }

    @Override public AcquireRoute route() {
        return route;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        asked++;
        // 答案用完就重复最后一条：世界没变时来源的回答不会自己变。
        if (!quotes.isEmpty()) lastAnswer = quotes.poll();
        return lastAnswer;
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        begun++;
        return switch (ending) {
            case DELIVER -> Optional.of(new Delivering(name, backpack,
                    request.wanted().specifier(), Math.max(1, offer.obtainableCount())));
            case FAIL -> Optional.of(new Failing(name));
            case DELIVER_NOTHING -> Optional.of(new InstantlyDone(name));
            case UNAVAILABLE -> Optional.empty();
        };
    }

    /** 做完时往背包里放进几件想要的东西，让引擎的重新清点看得到。 */
    private static final class Delivering implements Action {
        private final String source;
        private final FakeBackpack backpack;
        private final String itemId;
        private final int count;

        Delivering(String source, FakeBackpack backpack, String itemId, int count) {
            this.source = source;
            this.backpack = backpack;
            this.itemId = itemId;
            this.count = count;
        }

        @Override public ActionStatus tick(TickContext context) {
            backpack.add(itemId, count);
            return ActionStatus.done();
        }

        @Override public String describe() {
            return source + "把货放进背包";
        }
    }

    private record Failing(String source) implements Action {
        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, source + "没了"));
        }
        @Override public String describe() {
            return source + "以问题失败";
        }
    }

    private record InstantlyDone(String source) implements Action {
        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.done();
        }
        @Override public String describe() {
            return source + "做完了（其实什么都没拿到）";
        }
    }
}
