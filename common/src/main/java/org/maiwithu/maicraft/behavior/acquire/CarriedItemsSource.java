// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireRoute;

/**
 * 身上的来源：主背包与副手里已经有了的。真人要东西先翻身上——已经带着的零代价，
 * 问价时最先报出来；执行没什么可做的，东西本来就在身上，引擎重新清点时自然会算到。
 */
public final class CarriedItemsSource implements ItemSource {

    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final ReadsItemTags tags;

    public CarriedItemsSource(BackpackView backpack, OffhandContents offhand, ReadsItemTags tags) {
        this.backpack = backpack;
        this.offhand = offhand;
        this.tags = tags;
    }

    @Override public String describe() {
        return "身上的背包";
    }

    @Override public AcquireRoute route() {
        return AcquireRoutes.CARRIED;
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        int carried = CarriedItems.matching(backpack, offhand, request, tags);
        if (carried <= 0) {
            return new SourceQuote.Unavailable(describe(), "身上没有" + request.wanted().describe());
        }
        return new SourceQuote.Offer(describe(), Math.min(carried, request.count()),
                AcquisitionCost.free(), null);
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        return Optional.of(new AlreadyCarried());
    }

    /** 东西已经在身上的"动作"：推进一步就算做完，没有现场要做的事。 */
    private record AlreadyCarried() implements Action {
        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.done();
        }
        @Override public String describe() {
            return "东西已经在身上，不用再拿";
        }
    }
}
