// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 内需入口：引擎建好前问什么都问不出（远近、最省代价、看一眼都为空），建好后每一问都原样转给引擎——
 * 合成来源"缺料一样多按原料远近排""分不出远近先看一眼 AE"靠它在游戏里生效。
 */
class DeferredInnerNeedsTest {

    private static final ItemRequest PLANKS = new ItemRequest(WantedItem.ofTag("minecraft:planks"), 4, "做箱子");

    private static final Action LOOK = new Action() {
        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.done();
        }
        @Override public String describe() {
            return "看一眼 AE";
        }
    };

    /** 引擎替身：每一问都给一个认得出的答案。 */
    private static final ItemNeeds ENGINE = new ItemNeeds() {
        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            return LOOK;
        }
        @Override public boolean knownUnobtainable(WantedItem wanted) {
            return true;
        }
        @Override public Optional<Double> nearestDistanceBlocks(ItemRequest request, Permissions permissions) {
            return Optional.of(7.0);
        }
        @Override public Optional<Action> lookAround(ItemRequest request, Permissions permissions) {
            return Optional.of(LOOK);
        }
        @Override public Optional<Double> cheapestCost(ItemRequest request, Permissions permissions,
                boolean includeMaking) {
            return Optional.of(includeMaking ? 50.0 : 118.0);
        }
    };

    @Test
    void 引擎建好前问不出() {
        DeferredInnerNeeds needs = new DeferredInnerNeeds();
        assertTrue(needs.nearestDistanceBlocks(PLANKS, Permissions.DEFAULT).isEmpty());
        assertTrue(needs.lookAround(PLANKS, Permissions.DEFAULT).isEmpty());
        assertTrue(needs.cheapestCost(PLANKS, Permissions.DEFAULT, true).isEmpty());
        assertFalse(needs.knownUnobtainable(PLANKS.wanted()));
    }

    @Test
    void 引擎建好后每一问都转给它() {
        DeferredInnerNeeds needs = new DeferredInnerNeeds();
        needs.attach(ENGINE);
        assertEquals(Optional.of(7.0), needs.nearestDistanceBlocks(PLANKS, Permissions.DEFAULT));
        assertSame(LOOK, needs.lookAround(PLANKS, Permissions.DEFAULT).orElseThrow());
        assertEquals(Optional.of(118.0), needs.cheapestCost(PLANKS, Permissions.DEFAULT, false));
        assertTrue(needs.knownUnobtainable(PLANKS.wanted()));
        assertSame(LOOK, needs.actionFor(PLANKS, Permissions.DEFAULT));
    }
}
