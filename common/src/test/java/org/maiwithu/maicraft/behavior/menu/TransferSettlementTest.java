// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Problem;

/** 搬运结算：已确认的量只增不减，失败保留最早的原因，没能确认的原样留着。 */
class TransferSettlementTest {

    @Test
    void 确认的量累计且不被后来的失败抹掉() {
        TransferSettlement outcome = new TransferSettlement();
        outcome.confirm("minecraft:cobblestone", 32);
        outcome.confirm("minecraft:cobblestone", 17);
        outcome.fail(Problem.of(Problem.Kind.TARGET_GONE, "容器被拆了"));
        outcome.confirm("minecraft:iron_ingot", 3);
        // 失败之后已确认的量照常累计，不会被清零或撤销。
        assertEquals(Map.of("minecraft:cobblestone", 49, "minecraft:iron_ingot", 3), outcome.confirmedByItem());
        assertTrue(outcome.failed());
    }

    @Test
    void 失败保留最早的原因() {
        TransferSettlement outcome = new TransferSettlement();
        outcome.fail(Problem.of(Problem.Kind.TARGET_GONE, "容器被拆了"));
        outcome.fail(Problem.of(Problem.Kind.STUCK, "界面关不上"));
        assertEquals("容器被拆了", outcome.earliestFailure().message());
    }

    @Test
    void 没能确认的搬运原样留着() {
        TransferSettlement outcome = new TransferSettlement();
        outcome.unconfirmed("往燃料槽放煤的结果没等到确认");
        outcome.unconfirmed("从产出格取铁锭的结果没等到确认");
        assertFalse(outcome.failed(), "没能确认不是失败，两种账分开记");
        assertEquals(2, outcome.unconfirmedFacts().size());
        assertTrue(outcome.confirmedByItem().isEmpty());
    }
}
