// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.phys.AABB;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.SupportedMod;
import org.maiwithu.maicraft.compat.VerifiedVersions;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.FurnaceFuels;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/** ME 终端来源：联动入口交出终端界面的布局证明与途径 ae2 的来源；挑终端的结论如实写成报价。 */
class Ae2TerminalSourceTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private static final Ae2Terminals.Terminal TERMINAL = new Ae2Terminals.Terminal(
            new BlockPos(3, 64, 0), Direction.NORTH, new AABB(3.125, 64.125, 0, 3.875, 64.875, 0.125), "ae2:terminal");

    private static ItemRequest torches(int count) {
        return new ItemRequest(WantedItem.ofItem("minecraft:torch"), count, "照明");
    }

    /** 不在世界里的玩家行为：角色为空，靠近与交互都碰不到；只够建出来源、回答"不在世界里"。 */
    private static PlayerServices offlineServices() {
        Protection nobodyOwns = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self");
        return new PlayerServices(() -> null, (target, permissions) -> null, new Interactions(null),
                itemId -> Optional.empty(), (request, permissions) -> null, nobodyOwns,
                itemId -> Set.of(), MenuLayouts.VANILLA, new BlockScanService());
    }

    /** 读写端的替身：附近没有终端，界面什么都读不到；这里只看联动入口交了什么。 */
    private static Ae2Compat compat() {
        return new Ae2Compat(new Ae2Terminals() {
            @Override public List<Ae2Terminals.Terminal> near(ClientLevel level, BlockPos center, int radius) {
                return List.of();
            }

            @Override public boolean stillThere(ClientLevel level, BlockPos block, Direction side) {
                return false;
            }
        }, new Ae2TerminalMenu() {
            @Override public boolean belongsTo(AbstractContainerMenu menu, ClientLevel level, BlockPos block,
                    Direction side) {
                return false;
            }

            @Override public Optional<Boolean> linked(AbstractContainerMenu menu) {
                return Optional.empty();
            }

            @Override public Optional<List<StockEntry>> stock(AbstractContainerMenu menu) {
                return Optional.empty();
            }

            @Override public void takeStack(AbstractContainerMenu menu, long serial) {}

            @Override public void takeOne(AbstractContainerMenu menu, long serial) {}

            @Override public void putBack(AbstractContainerMenu menu) {}
        });
    }

    /** 一个界面的槽位描述替身：前 otherSlots 格是视图元件、合成格这类不是背包的格子，后面 36 格是角色背包。 */
    private static MenuSlots terminalSlots(String menuType, int otherSlots) {
        return new MenuSlots() {
            @Override public String menuTypeId() { return menuType; }
            @Override public int slotCount() { return otherSlots + 36; }
            @Override public boolean playerBacked(int slot) { return slot >= otherSlots; }
        };
    }

    @Test
    void 联动入口交出终端界面的布局证明与途径为ae2的来源() {
        SupportedMod<CompatModule> supported = new SupportedMod<>(Ae2Compat.MOD_ID, "应用能源2", new VerifiedVersions("19.2.17", "19.3"),
                Ae2TerminalSourceTest::compat);
        CompatRegistry registry = CompatRegistry.load(List.of(supported), loaderWith("19.2.17"));

        // 合成终端除了 36 格背包还有视图元件格与合成格：都不算容器那一侧，网络里的货不在格子里。
        MenuLayout.Supported layout = assertInstanceOf(MenuLayout.Supported.class,
                registry.menuLayouts().classify(terminalSlots("ae2:craftingterm", 15)));
        assertEquals(36, layout.playerSlots().size());
        assertTrue(layout.containerSlots().isEmpty());
        assertInstanceOf(MenuLayout.Unsupported.class,
                registry.menuLayouts().classify(terminalSlots("ae2:pattern_access_terminal", 0)));

        List<ItemSource> sources = registry.itemSources(offlineServices());
        assertEquals(1, sources.size());
        assertEquals("ae2", sources.getFirst().via().name());
        assertEquals("ME 终端", sources.getFirst().describe());
    }

    @Test
    void 角色不在世界里就给不了() {
        Ae2TerminalSource source = new Ae2TerminalSource(compat(), offlineServices(),
                (terminal, dimension, request, permissions) -> Optional.empty(), new SeenNetworkStock(), () -> NOW);
        SourceQuote quote = source.quote(torches(8),
                new SourceContext(WorldPosition.here(0, 64, 0), Permissions.DEFAULT));
        assertInstanceOf(SourceQuote.Unavailable.class, quote);
    }

    @Test
    void 记得有货_报明确数量且不超过这次要的() {
        TerminalChooser.Candidate candidate = new TerminalChooser.Candidate(TERMINAL, 12, true,
                Optional.of(new SeenNetworkStock.Sighting(true, Map.of("minecraft:torch", 200L), NOW.minusSeconds(90))));
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                Ae2TerminalSource.quoteFrom(new TerminalChooser.Known(candidate, 200), torches(8), "ME 终端", NOW));
        assertEquals(8, offer.obtainableCount());
        assertEquals(12, offer.cost().distanceBlocks());
        assertTrue(offer.risk().contains("1 分钟前"), offer.risk());
        assertEquals("3,64,0,north", offer.hint());
    }

    @Test
    void 没看过或上次不行的终端报不知道有多少_都用不上就给不了() {
        TerminalChooser.Candidate candidate = new TerminalChooser.Candidate(TERMINAL, 5, true, Optional.empty());
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                Ae2TerminalSource.quoteFrom(new TerminalChooser.Unknown(candidate, Optional.empty()), torches(8),
                        "ME 终端", NOW));
        assertTrue(offer.countUnknown());
        SourceQuote.Offer again = assertInstanceOf(SourceQuote.Offer.class, Ae2TerminalSource.quoteFrom(
                new TerminalChooser.Unknown(candidate, Optional.of("上次看没连上网络（没电或没频道）")), torches(8),
                "ME 终端", NOW));
        assertTrue(again.risk().startsWith("上次看没连上网络"), again.risk());

        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class,
                Ae2TerminalSource.quoteFrom(new TerminalChooser.None("附近 64 格内没有 ME 终端"), torches(8),
                        "ME 终端", NOW));
        assertEquals("附近 64 格内没有 ME 终端", unavailable.reason());
    }

    /** 只装了 AE2、版本给定的加载器环境替身。 */
    private static LoaderEnvironment loaderWith(String version) {
        return new LoaderEnvironment() {
            @Override public String loaderName() { return "test"; }
            @Override public boolean isModLoaded(String modId) { return modId.equals(Ae2Compat.MOD_ID); }
            @Override public Optional<String> modVersion(String modId) {
                return isModLoaded(modId) ? Optional.of(version) : Optional.empty();
            }
            @Override public Path gameDirectory() { return Path.of("."); }
            @Override public Path configDirectory() { return Path.of("."); }
            @Override public boolean isDevelopment() { return true; }
            @Override public FurnaceFuels furnaceFuels() { return stack -> 0; }
        };
    }
}
