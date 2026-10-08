// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.inventory.KnownContainer;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 容器的来源：开过、记了内容的箱子报明确的数；只看见过的报"不知道有多少"；
 * 动手时按报价认的那只箱子交开箱取物的接缝，记忆过时就交回空。
 */
class ContainerSourceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final WorldPosition CHEST_AT = new WorldPosition(10, 64, 5, null);
    private static final SourceContext CONTEXT = new SourceContext(
            WorldPosition.here(0, 64, 0), Permissions.DEFAULT);

    @TempDir Path temp;

    private final FakeTags tags = new FakeTags();

    private WorldMemory memory() {
        return new WorldMemory(new DocumentStore(temp.resolve("state.sqlite")), "world-1");
    }

    private ItemRequest request(int count) {
        return new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), count, "工具准备");
    }

    @Test
    void 开过的箱子按记下的内容报价() {
        WorldMemory memory = memory();
        memory.rememberContainerOpened(CHEST_AT, "minecraft:chest",
                List.of("minecraft:iron_ingot", "minecraft:iron_ingot", "minecraft:bread"), NOW);
        ContainerSource source = new ContainerSource(memory, tags, (container, request) -> Optional.empty());

        SourceQuote quote = source.quote(request(2), CONTEXT);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, quote);
        assertEquals(2, offer.obtainableCount());
        assertEquals(11.180, offer.cost().distanceBlocks(), 0.01);
        assertTrue(offer.risk().contains("现场为准"));
    }

    @Test
    void 只看见过的箱子报不知道有多少_排在后面由选择器决定() {
        WorldMemory memory = memory();
        memory.rememberContainerSeen(CHEST_AT, "minecraft:chest", NOW);
        ContainerSource source = new ContainerSource(memory, tags, (container, request) -> Optional.empty());

        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                source.quote(request(1), CONTEXT));
        assertEquals(SourceQuote.Offer.UNKNOWN_COUNT, offer.obtainableCount());
        assertTrue(offer.risk().contains("没开过"));
    }

    @Test
    void 记忆里没有装着它的箱子_如实回答给不了() {
        WorldMemory memory = memory();
        memory.rememberContainerOpened(CHEST_AT, "minecraft:chest", List.of("minecraft:bread"), NOW);
        ContainerSource source = new ContainerSource(memory, tags, (container, request) -> Optional.empty());

        SourceQuote quote = source.quote(request(1), CONTEXT);
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class, quote);
        assertTrue(unavailable.reason().contains("没有装着"));
    }

    @Test
    void 动手时按报价认的箱子交开箱接缝() {
        WorldMemory memory = memory();
        memory.rememberContainerOpened(CHEST_AT, "minecraft:chest", List.of("minecraft:iron_ingot"), NOW);
        AtomicReference<KnownContainer> taken = new AtomicReference<>();
        ContainerSource source = new ContainerSource(memory, tags, (container, request) -> {
            taken.set(container);
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.done();
                }
                @Override public String describe() {
                    return "开箱取物";
                }
            });
        });
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, source.quote(request(1), CONTEXT));
        Action action = source.begin(request(1), offer, CONTEXT).orElseThrow();
        assertEquals(ActionStatus.done(), action.tick(new StubTick(1)));
        assertEquals(10, taken.get().x());
        assertEquals(64, taken.get().y());
        assertEquals(5, taken.get().z());
    }

    @Test
    void 报价认的箱子从记忆里没了_交回空由引擎换路() {
        WorldMemory memory = memory();
        ContainerSource source = new ContainerSource(memory, tags, (container, request) -> Optional.empty());
        SourceQuote.Offer stale = new SourceQuote.Offer("记得的箱子", 3,
                new AcquisitionCost(5, 4), null, "99,64,99");
        assertTrue(source.begin(request(1), stale, CONTEXT).isEmpty());
    }
}
