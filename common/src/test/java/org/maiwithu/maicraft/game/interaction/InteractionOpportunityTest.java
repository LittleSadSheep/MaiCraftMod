// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 每刻只准提交一次交互：机会被占用后同一刻不能再领，下一刻自动恢复。 */
class InteractionOpportunityTest {

    @Test
    void oneClaimPerTickAndReadonlyAvailability() {
        InteractionOpportunity opportunity = new InteractionOpportunity();
        FakePlayerContext context = new FakePlayerContext(null);
        assertTrue(opportunity.available(context));
        assertTrue(opportunity.tryClaim(context));
        // 只读判断不占用；同一刻的第二次领取必须被拒绝。
        assertFalse(opportunity.available(context));
        assertFalse(opportunity.tryClaim(context));
        // 下一刻自动恢复，不需要谁去显式释放。
        context.nextTick();
        assertTrue(opportunity.available(context));
        assertTrue(opportunity.tryClaim(context));
    }

    @Test
    void anExpiredContextOffersNoOpportunity() {
        InteractionOpportunity opportunity = new InteractionOpportunity();
        FakePlayerContext stale = new FakePlayerContext(null).expired();
        assertFalse(opportunity.available(stale));
        assertFalse(opportunity.tryClaim(stale));
        FakePlayerContext spent = new FakePlayerContext(null);
        spent.canInteract = false;
        assertFalse(opportunity.available(spent));
    }
}
