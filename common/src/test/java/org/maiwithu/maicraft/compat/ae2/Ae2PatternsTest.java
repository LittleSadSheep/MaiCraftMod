// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.behavior.menu.MenuLayout;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;

/** 样板供应器：每种样板按产出记一项、值是 in / out，没开过界面读作 unknown；界面只认放样板的格子。 */
class Ae2PatternsTest {

    @Test
    void 身上带着的与供应器里看见的样板各记一项_没开过界面读作unknown() {
        List<MachineSetting> unseen = PatternSettings.of(Optional.empty(), Set.of("minecraft:iron_block"));
        assertEquals(1, unseen.size());
        assertEquals("pattern.minecraft:iron_block", unseen.getFirst().key());
        assertEquals(PatternSettings.UNKNOWN, unseen.getFirst().value());
        List<MachineSetting> seen = PatternSettings.of(Optional.of(List.of("minecraft:gold_block")),
                Set.of("minecraft:iron_block"));
        assertEquals(List.of("pattern.minecraft:gold_block", "pattern.minecraft:iron_block"),
                seen.stream().map(MachineSetting::key).toList());
        assertEquals(List.of("in", "out"), seen.stream().map(MachineSetting::value).toList());
        assertEquals(Optional.of("minecraft:iron_block"), PatternSettings.outputOf("pattern.minecraft:iron_block"));
        assertTrue(PatternSettings.outputOf("side.north").isEmpty());
        assertTrue(PatternSettings.outputOf("pattern.").isEmpty());
    }

    @Test
    void 样板供应器的界面只认放样板的格子_一格都没有就证明不了() {
        StubPatterns patterns = new StubPatterns();
        Ae2PatternProviderLayout layout = new Ae2PatternProviderLayout(
                new Ae2Compat(NO_TERMINALS, new NoTerminalMenu(), new NoGrid(), patterns));
        MenuSlots slots = new MenuSlots() {
            @Override public String menuTypeId() { return Ae2PatternProviderLayout.PROVIDER_MENU; }
            @Override public int slotCount() { return 54; }
            @Override public boolean playerBacked(int slot) { return slot >= 18; }
        };
        List<Integer> player = IntStream.range(18, 54).boxed().toList();
        List<Integer> others = IntStream.range(0, 18).boxed().toList();
        // 前九格放样板，后九格是退回格：只认前九格。
        patterns.slots = Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8);
        MenuLayout.Layout supported = layout.classify(slots, player, others);
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8), ((MenuLayout.Supported) supported).containerSlots());
        patterns.slots = Set.of();
        assertTrue(layout.classify(slots, player, others) instanceof MenuLayout.Unsupported);
    }

    /** 附近没有终端。 */
    private static final Ae2Terminals NO_TERMINALS = new Ae2Terminals() {
        @Override public List<Ae2Terminals.Terminal> near(ClientLevel level, BlockPos center, int radius) {
            return List.of();
        }

        @Override public boolean stillThere(ClientLevel level, BlockPos block, Direction side) {
            return false;
        }
    };

    /** 终端界面：什么都读不到，什么都不发。 */
    private static final class NoTerminalMenu implements Ae2TerminalMenu {
        @Override public boolean belongsTo(AbstractContainerMenu menu, ClientLevel level, BlockPos block, Direction side) {
            return false;
        }

        @Override public Optional<Boolean> linked(AbstractContainerMenu menu) { return Optional.empty(); }
        @Override public Optional<List<StockEntry>> stock(AbstractContainerMenu menu) { return Optional.empty(); }
        @Override public void takeStack(AbstractContainerMenu menu, long serial) { }
        @Override public void takeOne(AbstractContainerMenu menu, long serial) { }
        @Override public void putBack(AbstractContainerMenu menu) { }
    }

    /** 读写端替身：按测试给的格号回答，不认样板。 */
    private static final class StubPatterns implements Ae2Patterns {
        Set<Integer> slots = Set.of();

        @Override public Set<Integer> openPatternSlots() { return slots; }
        @Override public Optional<String> patternOutput(ItemStack stack, ClientLevel level) { return Optional.empty(); }
    }
}
