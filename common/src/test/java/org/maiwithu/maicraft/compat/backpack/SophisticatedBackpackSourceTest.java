// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.backpack;

import net.minecraft.world.inventory.AbstractContainerMenu;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.GearChanges;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.task.Action;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 随身背包的来源：身上有包才报价；上次开包读到的数五分钟内算数，报明确数量；
 * 没读过报"包里可能有，不知道有多少"；读数里没有的如实说，不冒充开包去看。
 */
class SophisticatedBackpackSourceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final SourceContext CONTEXT = new SourceContext(
            WorldPosition.here(0, 64, 0), Permissions.DEFAULT);
    private static final ItemRequest TORCHES = new ItemRequest(WantedItem.ofItem("minecraft:torch"), 10, "按需要拿取");

    /** 界面读写端的替身：测试不真开背包界面。 */
    private static final BackpackMenus MENUS = new BackpackMenus() {
        @Override public boolean isBackpackMenu(AbstractContainerMenu menu) { return false; }
        @Override public Optional<List<Integer>> mainStorageSlotIds(AbstractContainerMenu menu) { return Optional.empty(); }
        @Override public List<Integer> playerSlotIds(AbstractContainerMenu menu) { return List.of(); }
        @Override public void openCarried() { }
    };

    private static BackpackView carrying(String... itemIds) {
        List<BackpackStack> stacks = List.of(itemIds).stream()
                .map(itemId -> new BackpackStack(itemId, 1, 64, false, false, false, false)).toList();
        return new BackpackView() {
            @Override public List<BackpackStack> stacks() { return stacks; }
            @Override public int usedSlots() { return stacks.size(); }
            @Override public int totalSlots() { return 36; }
        };
    }

    private SophisticatedBackpackSource source(BackpackView backpack, SeenBackpackStock seen) {
        BackpackCompat compat = new BackpackCompat(itemId -> itemId.startsWith("sophisticatedbackpacks:"),
                MENUS, () -> NOW);
        PlayerServices services = new PlayerServices(() -> null, (target, permissions) -> null,
                new Interactions(null),
                itemId -> Optional.empty(), new NoGear(), inner -> inner, (request, permissions) -> null,
                new Protection((dimension, x, y, z) -> Optional.empty(), List::of, name -> Optional.empty(),
                        GuessesPlayerMade.NOTHING, "self"),
                itemId -> Set.of(), MenuLayouts.VANILLA, new BlockScanService(), ServerReads.NONE, backpack);
        return new SophisticatedBackpackSource(compat, services, MENUS, seen, () -> NOW);
    }

    /** 换不了装备的替身：这里的测试不穿卸、不放副手。 */
    private static final class NoGear implements GearChanges {
        @Override public Optional<Action> wear(String itemId, GearSlotName slot) {
            return Optional.empty();
        }

        @Override public Optional<Action> takeOff(GearSlotName slot) {
            return Optional.empty();
        }
    }

    @Test
    void 身上没有背包时如实说没有() {
        SourceQuote quote = source(carrying("minecraft:torch"), new SeenBackpackStock())
                .quote(TORCHES, CONTEXT);
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class, quote);
        assertTrue(unavailable.reason().contains("身上没有随身背包"), unavailable.reason());
    }

    @Test
    void 没开过包时报_包里可能有_排在数量明确的后面() {
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                source(carrying("sophisticatedbackpacks:iron_backpack"), new SeenBackpackStock())
                        .quote(TORCHES, CONTEXT));
        assertEquals(SourceQuote.Offer.UNKNOWN_COUNT, offer.obtainableCount());
        assertTrue(offer.risk().contains("不知道有多少"), offer.risk());
    }

    @Test
    void 开包读数五分钟内算数_报明确数量() {
        SeenBackpackStock seen = new SeenBackpackStock();
        seen.saw(Map.of("minecraft:torch", 20, "minecraft:bread", 3), NOW.minusSeconds(60));
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                source(carrying("sophisticatedbackpacks:iron_backpack"), seen).quote(TORCHES, CONTEXT));
        // 要 10 个、包里记着 20 个：报要拿的量，代价只算开包取货的动作。
        assertEquals(10, offer.obtainableCount());
        assertEquals(0, offer.cost().distanceBlocks());
        assertTrue(offer.risk().contains("以现场为准"), offer.risk());
    }

    @Test
    void 读数过了五分钟当没看过() {
        SeenBackpackStock seen = new SeenBackpackStock();
        seen.saw(Map.of("minecraft:torch", 20), NOW.minus(Duration.ofMinutes(6)));
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                source(carrying("sophisticatedbackpacks:iron_backpack"), seen).quote(TORCHES, CONTEXT));
        assertEquals(SourceQuote.Offer.UNKNOWN_COUNT, offer.obtainableCount());
    }

    @Test
    void 读数里没有的东西如实说_不去开包赌运气() {
        SeenBackpackStock seen = new SeenBackpackStock();
        seen.saw(Map.of("minecraft:bread", 3), NOW.minusSeconds(30));
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class,
                source(carrying("sophisticatedbackpacks:iron_backpack"), seen).quote(TORCHES, CONTEXT));
        assertTrue(unavailable.reason().contains("里面没有"), unavailable.reason());
    }

    @Test
    void 身上没背包时动手交回空_由引擎换路() {
        ItemSource source = source(carrying("minecraft:torch"), new SeenBackpackStock());
        assertTrue(source.begin(TORCHES,
                new SourceQuote.Offer("随身背包", 5, new AcquisitionCost(0, 4), ""),
                CONTEXT).isEmpty());
    }
}
