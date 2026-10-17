// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;
import org.maiwithu.maicraft.game.player.BackpackStack;

/** 测试替身：副手上拿着什么由测试摆出来，默认空手。 */
final class FakeOffhand implements OffhandContents {
    private BackpackStack held;

    void hold(BackpackStack stack) {
        held = stack;
    }

    @Override public Optional<BackpackStack> heldInOffhand() {
        return Optional.ofNullable(held);
    }
}
