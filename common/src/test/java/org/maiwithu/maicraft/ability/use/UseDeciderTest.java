// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.kernel.result.Problem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用东西的手势判定：参数组合、会炸的组合、交互结论怎么变成任务结果。 */
class UseDeciderTest {

    @Test
    void reportsAllInvalidCombinationsAtOnce() {
        // block 与 entity 同时给、text 与 item 同时给、什么都不给，一次报全。
        List<String> both = UseDecider.invalidCombinations(true, "minecraft:bucket",
                "minecraft:chest", "minecraft:horse", List.of());
        assertEquals(1, both.size());
        List<String> textAndItem = UseDecider.invalidCombinations(true, "minecraft:stick",
                null, null, List.of("第一行"));
        assertEquals(1, textAndItem.size());
        List<String> nothing = UseDecider.invalidCombinations(false, null, null, null, List.of());
        assertEquals(1, nothing.size());
    }

    @Test
    void timedReleaseItemsAreRejectedWithDirection() {
        assertTrue(UseDecider.rejectedItem("minecraft:bow").isPresent());
        assertEquals("钓鱼用 fish", UseDecider.rejectedItem("minecraft:fishing_rod").orElseThrow().suggestion());
        assertTrue(UseDecider.rejectedItem("minecraft:bucket").isEmpty());
    }

    @Test
    void bedsAndRespawnAnchorsInWrongDimensionsAreDanger() {
        assertTrue(UseDecider.explosionDanger("minecraft:the_nether", "minecraft:red_bed").isPresent());
        assertTrue(UseDecider.explosionDanger("minecraft:the_end", "minecraft:white_bed").isPresent());
        assertTrue(UseDecider.explosionDanger("minecraft:overworld", "minecraft:respawn_anchor").isPresent());
        // 主世界的床、下界的重生锚不会炸。
        assertTrue(UseDecider.explosionDanger(null, "minecraft:red_bed").isEmpty());
        assertTrue(UseDecider.explosionDanger("minecraft:the_nether", "minecraft:respawn_anchor").isEmpty());
        assertTrue(UseDecider.explosionDanger("minecraft:overworld", "minecraft:chest").isEmpty());
    }

    @Test
    void verdictsSettleIntoResultDirections() {
        // 生效：做满次数就完成。
        assertTrue(UseDecider.settle(InteractionResult.applied("生效"), false, true)
                instanceof UseDecider.Settlement.Applied);
        // 有游戏给的提示语的没生效：按被游戏拒绝，附原话。
        UseDecider.Settlement refused = UseDecider.settle(
                InteractionResult.notApplied("箱子已上锁"), true, false);
        assertTrue(refused instanceof UseDecider.Settlement.NotApplied);
        assertEquals(Problem.Kind.REFUSED_BY_GAME,
                ((UseDecider.Settlement.NotApplied) refused).problem().kind());
        // 服务端说没被任何东西处理且目标没变：这样用没有效果。
        UseDecider.Settlement noEffect = UseDecider.settle(
                InteractionResult.notApplied("现场纹丝没动"), false, false);
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE,
                ((UseDecider.Settlement.NotApplied) noEffect).problem().kind());
        // 出乎预料（水倒进岩浆变成黑曜石）：照样算完成，changes 照实写。
        assertTrue(UseDecider.settle(InteractionResult.unexpected("出现了黑曜石"), false, true)
                instanceof UseDecider.Settlement.Unexpected);
        // 没能确认：部分完成，会用掉东西的绝不再试。
        assertTrue(UseDecider.settle(InteractionResult.unconfirmed("确认没等到"), false, true)
                instanceof UseDecider.Settlement.Unconfirmed);
        assertTrue(UseDecider.mustStopAfterUnconfirmed(true));
        assertTrue(!UseDecider.mustStopAfterUnconfirmed(false));
    }
}
