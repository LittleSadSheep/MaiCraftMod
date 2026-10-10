// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.phys.AABB;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.compat.ModApiMismatch;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.SupportedMod;
import org.maiwithu.maicraft.compat.VerifiedVersions;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.game.world.FurnaceFuels;

/** AE2 的联动入口：装了且版本在范围内才登记；碰读写端的每一下都经联动入口，对不上就整个停用。 */
class Ae2CompatTest {

    private static final Ae2Terminals.Terminal WALL_TERMINAL = new Ae2Terminals.Terminal(
            new BlockPos(10, 64, 10), Direction.NORTH, new AABB(10.125, 64.125, 10, 10.875, 64.875, 10.125),
            "ae2:terminal");

    /** 找终端的替身：总是报同一台贴墙终端。 */
    private static final Ae2Terminals ONE_TERMINAL = new Ae2Terminals() {
        @Override public List<Ae2Terminals.Terminal> near(ClientLevel level, BlockPos center, int radius) {
            return List.of(WALL_TERMINAL);
        }

        @Override public boolean stillThere(ClientLevel level, BlockPos block, Direction side) {
            return block.equals(WALL_TERMINAL.block()) && side == WALL_TERMINAL.side();
        }
    };

    /** 终端界面的替身：记下发出去的取货操作；网络存货给空表。 */
    private static final class RecordingMenu implements Ae2TerminalMenu {
        final List<String> sent = new ArrayList<>();

        @Override public boolean belongsTo(AbstractContainerMenu menu, ClientLevel level, BlockPos block, Direction side) {
            return true;
        }

        @Override public Optional<Boolean> linked(AbstractContainerMenu menu) {
            return Optional.of(true);
        }

        @Override public Optional<List<StockEntry>> stock(AbstractContainerMenu menu) {
            return Optional.of(List.of());
        }

        @Override public void takeStack(AbstractContainerMenu menu, long serial) {
            sent.add("整组 " + serial);
        }

        @Override public void takeOne(AbstractContainerMenu menu, long serial) {
            sent.add("一件 " + serial);
        }

        @Override public void putBack(AbstractContainerMenu menu) {
            sent.add("放回");
        }
    }

    @Test
    void 经联动入口找终端与发取货操作() {
        RecordingMenu menu = new RecordingMenu();
        Ae2Compat compat = new Ae2Compat(ONE_TERMINAL, menu);
        assertEquals(List.of(WALL_TERMINAL), compat.terminalsNear(null, BlockPos.ZERO, 16));
        assertTrue(compat.terminalStillThere(null, WALL_TERMINAL.block(), Direction.NORTH));
        assertFalse(compat.terminalStillThere(null, WALL_TERMINAL.block(), Direction.SOUTH));
        compat.takeStack(null, 7);
        compat.takeOne(null, 7);
        compat.putBack(null);
        assertEquals(List.of("整组 7", "一件 7", "放回"), menu.sent);
        assertTrue(compat.active());
    }

    @Test
    void 读写端的类对不上时停用并抛模组接口对不上() {
        Ae2Terminals broken = new Ae2Terminals() {
            @Override public List<Ae2Terminals.Terminal> near(ClientLevel level, BlockPos center, int radius) {
                throw new NoSuchMethodError("IPartHost.getPart");
            }

            @Override public boolean stillThere(ClientLevel level, BlockPos block, Direction side) {
                return false;
            }
        };
        Ae2Compat compat = new Ae2Compat(broken, new RecordingMenu());
        assertThrows(ModApiMismatch.class, () -> compat.terminalsNear(null, BlockPos.ZERO, 16));
        assertFalse(compat.active());
        assertTrue(compat.disabledReason().orElseThrow().contains("找附近的 ME 终端"));
        // 停用之后别的读写一律不再碰模组，直接说明为什么用不了。
        assertThrows(ModApiMismatch.class, () -> compat.takeOne(null, 1));
    }

    @Test
    void 装了且版本在范围内才登记() {
        SupportedMod<CompatModule> supported = new SupportedMod<>(Ae2Compat.MOD_ID, "应用能源2", new VerifiedVersions("19.2.17", "19.3"),
                () -> new Ae2Compat(ONE_TERMINAL, new RecordingMenu()));

        CompatRegistry installed = CompatRegistry.load(List.of(supported), loaderWith("19.2.17"));
        assertEquals(List.of(Ae2Compat.MOD_ID), installed.modules().stream().map(module -> module.modId()).toList());
        assertEquals("应用能源2（ae2）：已登记，版本 19.2.17", installed.decisions().getFirst());

        CompatRegistry newer = CompatRegistry.load(List.of(supported), loaderWith("19.3.0"));
        assertTrue(newer.modules().isEmpty());
        assertTrue(newer.decisions().getFirst().contains("不登记"), newer.decisions().getFirst());

        CompatRegistry older = CompatRegistry.load(List.of(supported), loaderWith("19.2.16"));
        assertTrue(older.modules().isEmpty());
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
