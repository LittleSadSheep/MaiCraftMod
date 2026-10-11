// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.mekanism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.GearChanges;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.compat.SupportedMod;
import org.maiwithu.maicraft.compat.VerifiedVersions;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.FurnaceFuels;
import org.maiwithu.maicraft.kernel.task.Action;

/** 通用机械的联动：交出的机器类型与管网读取器、侧面配置的设置项写法、管道连成哪一片。 */
class MekanismCompatTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 读写端替身：按测试摆的管道回答，没有机器。 */
    private static final class StubReads implements MekanismReads {
        final Map<BlockPos, Transmitter> pipes = new HashMap<>();

        @Override public Optional<SideConfig> sideConfig(ClientLevel level, BlockPos at) { return Optional.empty(); }
        @Override public boolean configurable(BlockState state) { return false; }
        @Override public Optional<Transmitter> transmitter(ClientLevel level, BlockPos at) {
            return Optional.ofNullable(pipes.get(at));
        }
        @Override public Optional<String> configuratorMode(ItemStack stack) { return Optional.empty(); }
        @Override public void nextConfiguratorMode() { }
    }

    @Test
    void 装了对的版本交出目录里每种_兜底一种_四种管网() {
        SupportedMod<CompatModule> supported = new SupportedMod<>(MekanismCompat.MOD_ID, "通用机械",
                new VerifiedVersions("10.7.19", "10.8"), () -> new MekanismCompat(new StubReads()));
        CompatRegistry registry = CompatRegistry.load(List.of(supported), loader("10.7.19"));
        List<MachineType> types = registry.machineTypes(offline());
        assertEquals(MekanismMachineCatalog.kinds().size() + 1, types.size());
        List<NetworkReader> readers = registry.networkReaders(offline());
        assertEquals(List.of("energy", "fluid", "item", "chemical"),
                readers.stream().map(reader -> reader.kind().id()).toList());
        // 侧面配置与配置器的写法挂到机器的物品资料页上。
        assertTrue(registry.knowledgeSources().getFirst().entriesAbout("mekanism:metallurgic_infuser").size() == 1);
        assertTrue(registry.knowledgeSources().getFirst().read(MekanismMachineKnowledge.INDEX).text()
                .contains("side.north.item"));
    }

    @Test
    void 侧面配置写成设置项_化学品只读不列_键拆得回方向与介质() {
        Map<Direction, String> item = new EnumMap<>(Direction.class);
        for (Direction side : Direction.values()) item.put(side, "none");
        item.put(Direction.NORTH, "output");
        MekanismReads.SideConfig config = new MekanismReads.SideConfig(Direction.SOUTH,
                Map.of("item", item, "chemical", item), Map.of("item", List.of("none", "input", "output")));
        List<MachineSetting> settings = SideSettings.of(config);
        assertEquals(6, settings.size(), "只有物品这一种能改的，六面各一项");
        MachineSetting north = settings.stream().filter(setting -> setting.key().equals("side.north.item"))
                .findFirst().orElseThrow();
        assertEquals("output", north.value());
        assertEquals(List.of("none", "input", "output"), north.choices());
        assertEquals(Optional.of(new SideSettings.Key(Direction.NORTH, "item")), SideSettings.parse("side.north.item"));
        assertTrue(SideSettings.parse("side.north.chemical").isEmpty(), "化学品不改");
        assertTrue(SideSettings.parse("north.item").isEmpty());
        assertEquals("configurate_energy", SideSettings.configuratorMode("energy"));
        List<ExchangePoint> points = SideSettings.points(config, BlockPos.ZERO);
        assertTrue(points.stream().anyMatch(point -> point.side() == Direction.NORTH
                && point.medium() == ExchangePoint.Medium.ITEM && point.flow() == ExchangePoint.Flow.OUTPUT));
    }

    @Test
    void 管道沿连着的面数成一片_机器算在连着它的那一片() {
        StubReads reads = new StubReads();
        BlockPos a = new BlockPos(0, 64, 0);
        reads.pipes.put(a, new MekanismReads.Transmitter(Set.of("energy"), Set.of(Direction.EAST), Set.of()));
        reads.pipes.put(a.east(), new MekanismReads.Transmitter(Set.of("energy"), Set.of(Direction.WEST),
                Set.of(Direction.EAST)));
        PipeNetworkReader reader = new PipeNetworkReader("energy", new MekanismCompat(reads), offline());
        PipeNetworkReader.Component found = reader.componentOf(a.east(), at -> Optional.ofNullable(reads.pipes.get(at)));
        assertEquals(Set.of(a, a.east()), found.cells());
        assertEquals(1, found.machines());
        assertEquals("energy@0,64,0", found.id());
        assertTrue(reader.lineMaterials().isEmpty(), "注册表里没有 Mekanism 的线缆方块时不给线材");
    }

    private static PlayerServices offline() {
        Protection nobodyOwns = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self");
        return new PlayerServices(() -> null, (target, permissions) -> null, new Interactions(null),
                itemId -> Optional.empty(), new GearChanges() {
                    @Override public Optional<Action> wear(String itemId, GearSlotName slot) { return Optional.empty(); }
                    @Override public Optional<Action> takeOff(GearSlotName slot) { return Optional.empty(); }
                }, inner -> inner, (request, permissions) -> null, nobodyOwns, itemId -> Set.of(),
                MenuLayouts.VANILLA, new BlockScanService(), ServerReads.NONE, emptyBackpack());
    }

    /** 背包视图的替身：身上什么都没有，通用机械的测试用不到它。 */
    private static BackpackView emptyBackpack() {
        return new BackpackView() {
            @Override public List<BackpackStack> stacks() { return List.of(); }
            @Override public int usedSlots() { return 0; }
            @Override public int totalSlots() { return 36; }
        };
    }

    private static LoaderEnvironment loader(String version) {
        return new LoaderEnvironment() {
            @Override public String loaderName() { return "test"; }
            @Override public boolean isModLoaded(String modId) { return modId.equals(MekanismCompat.MOD_ID); }
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
