// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.worldmemory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/** 记忆记录的合并与变旧判断：来源取更强、确认过的内容覆盖没确认过的、记录时刻取新的。 */
class MemoryRecordTest {

    private static final WorldPosition POS = new WorldPosition(10, 64, -5, "minecraft:overworld");
    private static final Instant EARLIER = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-01-02T00:00:00Z");

    @Test
    void 没开过的容器不算知道里面有什么() {
        var seen = new MemoryRecord(MemoryKind.CONTAINER, POS, "minecraft:chest",
                null, MemoryOrigin.SEEN, EARLIER);
        assertFalse(seen.openedBefore());
    }

    @Test
    void 确认是空的也算开过_和没开过不是一回事() {
        var openedEmpty = new MemoryRecord(MemoryKind.CONTAINER, POS, "minecraft:chest",
                List.of(), MemoryOrigin.USED, EARLIER);
        assertTrue(openedEmpty.openedBefore());
        assertEquals(List.of(), openedEmpty.contents());
    }

    @Test
    void 合并时亲手用过不会退回亲眼看到() {
        var used = new MemoryRecord(MemoryKind.CONTAINER, POS, "minecraft:chest",
                List.of("minecraft:coal"), MemoryOrigin.USED, EARLIER);
        var seenAgain = new MemoryRecord(MemoryKind.CONTAINER, POS, "minecraft:chest",
                null, MemoryOrigin.SEEN, LATER);
        var merged = used.mergedWith(seenAgain);
        assertSame(MemoryOrigin.USED, merged.origin());
        // 路过又看了一眼不算打开过，当初开箱看到的清单还在。
        assertEquals(List.of("minecraft:coal"), merged.contents());
    }

    @Test
    void 合并时这次确认过的内容覆盖旧清单() {
        var usedBefore = new MemoryRecord(MemoryKind.CONTAINER, POS, "minecraft:chest",
                List.of("minecraft:coal"), MemoryOrigin.USED, EARLIER);
        var openedAgain = new MemoryRecord(MemoryKind.CONTAINER, POS, "minecraft:chest",
                List.of(), MemoryOrigin.USED, LATER);
        var merged = usedBefore.mergedWith(openedAgain);
        // 拿空了还记着有煤，会害角色白跑一趟；这次开箱看到的算数。
        assertEquals(List.of(), merged.contents());
        assertEquals(LATER, merged.recordedAt());
    }

    @Test
    void 来源更强的一次观察合并进亲眼看到的记忆() {
        var seen = new MemoryRecord(MemoryKind.CONTAINER, POS, "minecraft:barrel",
                null, MemoryOrigin.SEEN, EARLIER);
        var opened = new MemoryRecord(MemoryKind.CONTAINER, POS, "minecraft:barrel",
                List.of("minecraft:wheat"), MemoryOrigin.USED, LATER);
        var merged = seen.mergedWith(opened);
        assertSame(MemoryOrigin.USED, merged.origin());
        assertEquals(List.of("minecraft:wheat"), merged.contents());
    }

    @Test
    void 超过时限的记忆算旧_时限内不算() {
        var record = new MemoryRecord(MemoryKind.SITE, POS, null,
                List.of("minecraft:iron_ore"), MemoryOrigin.SEEN, EARLIER);
        assertTrue(record.olderThan(LATER, Duration.ofHours(12)));
        assertFalse(record.olderThan(EARLIER.plus(Duration.ofHours(12)), Duration.ofHours(12)));
    }

    @Test
    void 不同位置或不同种类的记忆不能合并() {
        var container = new MemoryRecord(MemoryKind.CONTAINER, POS, "minecraft:chest",
                null, MemoryOrigin.SEEN, EARLIER);
        var elsewhere = new MemoryRecord(MemoryKind.CONTAINER,
                new WorldPosition(11, 64, -5, "minecraft:overworld"), "minecraft:chest",
                null, MemoryOrigin.SEEN, LATER);
        var workstation = new MemoryRecord(MemoryKind.WORKSTATION, POS, "minecraft:crafting_table",
                null, MemoryOrigin.USED, LATER);
        assertThrows(IllegalArgumentException.class, () -> container.mergedWith(elsewhere));
        assertThrows(IllegalArgumentException.class, () -> container.mergedWith(workstation));
    }
}
