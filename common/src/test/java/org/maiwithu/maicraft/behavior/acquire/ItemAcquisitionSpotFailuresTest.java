// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.FailedOnlyAtSpots;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 来源一次动手只坏在现场几处（一棵树够不着）：引擎不把整个来源划掉，换别处接着拿；
 * 连着三次都这样一件没拿到才当它这次走不通；不说只坏在几处的失败照旧当场划掉。
 */
class ItemAcquisitionSpotFailuresTest {

    private static final String LOG = "minecraft:spruce_log";

    private final FakeBackpack backpack = new FakeBackpack(36);

    /** 替身采掘：前 failures 次动手都只坏在几处，之后每次砍下一根。 */
    private final class Trees implements ItemSource {
        private final boolean onlySpots;
        private int failuresLeft;
        int begun;

        Trees(int failures, boolean onlySpots) {
            this.failuresLeft = failures;
            this.onlySpots = onlySpots;
        }

        @Override public String describe() {
            return "采掘";
        }

        @Override public AcquireVia via() {
            return AcquireVia.MINE;
        }

        @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
            return new SourceQuote.Offer("采掘", request.count(), new AcquisitionCost(5, 1), null);
        }

        @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
            begun++;
            boolean fail = failuresLeft-- > 0;
            return Optional.of(new Chop(fail, onlySpots, begun));
        }
    }

    /** 一次动手：要么砍下一根，要么走不到那棵树跟前。 */
    private final class Chop implements Action, FailedOnlyAtSpots {
        private final boolean fail;
        private final boolean onlySpots;
        private final int tree;

        Chop(boolean fail, boolean onlySpots, int tree) {
            this.fail = fail;
            this.onlySpots = onlySpots;
            this.tree = tree;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (fail) {
                return ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "走不到第 " + tree + " 棵树跟前"));
            }
            backpack.add(LOG, 1);
            return ActionStatus.done();
        }

        @Override public boolean onlyThoseSpotsFailed() {
            return onlySpots;
        }

        @Override public String describe() {
            return "砍第 " + tree + " 棵树";
        }
    }

    private ActionStatus run(ItemSource source, int count) {
        ItemAcquisition engine = new ItemAcquisition(List.of(source), backpack, new FakeOffhand(), new FakeTags(),
                new FixedSpot(0, 64, 0), Optional.empty(), ItemAcquisition.DEFAULT_MAX_DEPTH);
        Action action = engine.need(new ItemRequest(WantedItem.ofItem(LOG), count, "木头"), Permissions.DEFAULT);
        for (int tick = 1; tick <= 200; tick++) {
            ActionStatus status = action.tick(new StubTick(tick));
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("两百刻还没定局");
    }

    @Test
    void 一棵树够不着_换别的树接着砍() {
        Trees trees = new Trees(2, true);
        assertInstanceOf(ActionStatus.Done.class, run(trees, 2));
        assertEquals(4, trees.begun, "两棵够不着的跳过，又砍了两棵");
    }

    @Test
    void 连着三处都够不着_才当采掘这次走不通() {
        Trees trees = new Trees(10, true);
        ActionStatus status = run(trees, 1);
        String message = assertInstanceOf(ActionStatus.Failed.class, status).problem().message();
        assertEquals(ItemAcquisition.MAX_SPOT_FAILURES, trees.begun);
        assertTrue(message.contains("走不到第 1 棵树跟前") && message.contains("走不到第 3 棵树跟前"), message);
    }

    @Test
    void 不是只坏在几处的失败_当场划掉整个来源() {
        Trees trees = new Trees(1, false);
        assertInstanceOf(ActionStatus.Failed.class, run(trees, 1));
        assertEquals(1, trees.begun);
    }
}
