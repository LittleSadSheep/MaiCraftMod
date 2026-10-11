// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.NetworkSummary;

/** 动力读数翻成运行状态、应力网汇总、加工处在哪：纯函数。 */
class CreateReadingsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static CreateKinetics.Reading reading(double speed, boolean overStressed, boolean fastEnough) {
        return new CreateKinetics.Reading(speed, overStressed ? 32 : speed, Optional.of(7L), overStressed,
                overStressed ? 300 : 120, 256, 9, fastEnough);
    }

    @Test
    void 接在网上在转且够快就是在干活() {
        MachineState state = CreateReadings.kinetic(Optional.of(reading(32, false, true)), Optional.empty(), List.of());
        assertEquals(MachineState.Activity.RUNNING, state.activity());
        assertEquals(32.0, state.speed());
        assertEquals(256.0, state.readings().get("stress_capacity"));
    }

    @Test
    void 过载时整张网停转并写出应力与容量() {
        MachineState state = CreateReadings.kinetic(Optional.of(reading(0, true, false)), Optional.empty(), List.of());
        assertEquals(MachineState.Activity.OVERLOADED, state.activity());
        assertTrue(state.note().contains("300"));
        assertEquals(32.0, state.readings().get("speed_without_overload"));
    }

    @Test
    void 在转但转速不够门槛时算停着并说明差多少() {
        MachineState state = CreateReadings.kinetic(Optional.of(reading(16, false, false)), Optional.of(30.0),
                List.of());
        assertEquals(MachineState.Activity.IDLE, state.activity());
        assertTrue(state.note().contains("30"));
    }

    @Test
    void 没接网或读不到时不猜() {
        CreateKinetics.Reading loose = new CreateKinetics.Reading(0, 0, Optional.empty(), false, 0, 0, 0, true);
        assertEquals(MachineState.Activity.IDLE,
                CreateReadings.kinetic(Optional.of(loose), Optional.empty(), List.of()).activity());
        assertEquals(MachineState.Activity.UNKNOWN,
                CreateReadings.kinetic(Optional.empty(), Optional.empty(), List.of()).activity());
    }

    @Test
    void 应力网汇总给容量当前应力剩余与成员数() {
        NetworkSummary summary = KineticNetworkReader.readable("kinetic:7", reading(32, false, true));
        assertTrue(summary.readable());
        assertEquals(136.0, summary.readings().get("stress_left"));
        assertEquals(9L, summary.readings().get("members"));
        assertEquals(false, summary.readings().get("overstressed"));
    }

    @Test
    void 压机与搅拌器在正下方第二格干活_机械手在朝向前方第二格() {
        BlockPos at = new BlockPos(0, 70, 0);
        assertEquals(Optional.of(new BlockPos(0, 68, 0)), ProcessingSpots.workCell(CreateMachineCatalog.Work.PRESS,
                Blocks.STONE.defaultBlockState(), at));
        assertEquals(Optional.of(new BlockPos(0, 68, 0)), ProcessingSpots.workCell(CreateMachineCatalog.Work.MIXER,
                Blocks.STONE.defaultBlockState(), at));
        // 机械手用方块状态的 facing：拿发射器的方块状态代它摆朝向。
        assertEquals(Optional.of(new BlockPos(2, 70, 0)), ProcessingSpots.workCell(CreateMachineCatalog.Work.DEPLOYER,
                Blocks.DISPENSER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST), at));
    }

    @Test
    void 动力源的轴往外给转动_置物台顶面能放能取() {
        BlockPos at = new BlockPos(0, 64, 0);
        List<ExchangePoint> wheel = ProcessingSpots.of(CreateMachineCatalog.Work.TURNING,
                Blocks.STONE.defaultBlockState(), at, Set.of(Direction.EAST, Direction.WEST), true);
        assertEquals(2, wheel.size());
        assertTrue(wheel.stream().allMatch(point -> point.medium() == ExchangePoint.Medium.ROTATION
                && point.flow() == ExchangePoint.Flow.OUTPUT));
        List<ExchangePoint> depot = ProcessingSpots.of(CreateMachineCatalog.Work.DEPOT,
                Blocks.STONE.defaultBlockState(), at, Set.of(), false);
        assertEquals(Direction.UP, depot.getFirst().side());
        assertEquals(ExchangePoint.Flow.BOTH, depot.getFirst().flow());
    }

    @Test
    void 机械手的两项设置_手里拿着的与模式_点击框只在前端那一截() {
        CreateStations.DeployerReading holding = new CreateStations.DeployerReading(
                new ItemStack(Items.ANDESITE, 12), "use");
        List<MachineSetting> settings = DeployerSettings.of(Optional.of(holding));
        assertEquals("minecraft:andesite", settings.get(0).value());
        assertEquals("use", settings.get(1).value());
        assertEquals("empty", DeployerSettings.heldValue(ItemStack.EMPTY));
        assertTrue(DeployerSettings.of(Optional.empty()).isEmpty(), "读不到机械手就没有设置项");
        // 朝下的机械手：前端是底面往上 0.2 格那一片。
        BlockPos at = new BlockPos(0, 70, 0);
        var front = DeployerSettings.front(at, Direction.DOWN);
        assertEquals(70.0, front.minY, 1e-9);
        assertEquals(70.2, front.maxY, 1e-9);
        var state = DeployerSettings.withReading(MachineState.unknown(""), Optional.of(holding));
        assertEquals("minecraft:andesite ×12", state.readings().get("held"));
        assertEquals("use", state.readings().get("mode"));
    }

    @Test
    void 机械合成器_格里放什么与箭头两项_正面与连入口的点击框() {
        CreateStations.CrafterReading reading = new CreateStations.CrafterReading(
                new ItemStack(Items.IRON_INGOT), "left", Direction.NORTH);
        List<MachineSetting> settings = CrafterSettings.of(Optional.of(reading));
        assertEquals(List.of("minecraft:iron_ingot", "left"), settings.stream().map(MachineSetting::value).toList());
        BlockPos at = new BlockPos(0, 64, 0);
        var front = CrafterSettings.front(at, Direction.NORTH);
        assertEquals(0.0, front.minZ, 1e-9);
        assertEquals(0.15, front.minX, 1e-9);
        // 连东边那一台：点背面（南面）靠东的那一条，x 在 13/16 到 1 之间，贴着南面。
        var edge = CrafterSettings.edge(at, Direction.SOUTH, Direction.EAST);
        assertEquals(0.8125, edge.minX, 1e-9);
        assertEquals(1.0, edge.maxX, 1e-9);
        assertEquals(1.0, edge.maxZ, 1e-9);
        assertTrue(CrafterLinkInstallation.shapeProblem(List.of(at, at.east())).isEmpty());
        assertTrue(CrafterLinkInstallation.shapeProblem(List.of(at, at.east(2))).isPresent());
    }

    @Test
    void 流体_显示的字_无线红石_装置在不在动并进读数() {
        CreateStations.LinkReading link = new CreateStations.LinkReading(
                List.of(new ItemStack(Items.REDSTONE), ItemStack.EMPTY), List.of(), true, false);
        MachineState state = CreateReadouts.with(MachineState.unknown(""),
                List.of(new CreateStations.FluidReading("minecraft:water", 1500, 8000)), Optional.of("12:00"),
                Optional.of(link), Optional.of(true));
        assertEquals(List.of("minecraft:water 1500/8000 mB"), state.readings().get("fluids"));
        assertEquals("12:00", state.readings().get("text"));
        assertEquals(List.of("minecraft:redstone", "empty"), state.readings().get("frequency"));
        assertEquals("receive", state.readings().get("link_mode"));
        assertEquals(true, state.readings().get("moving"));
        List<MachineSetting> settings = LinkSettings.of(Optional.of(link));
        assertEquals(List.of("link.frequency.1", "link.frequency.2", "link.mode"),
                settings.stream().map(MachineSetting::key).toList());
        assertEquals(Optional.of(1), LinkSettings.frequencyIndex("link.frequency.2"));
        assertTrue(LinkSettings.frequencyIndex("link.frequency.3").isEmpty());
        assertTrue(CreateReadouts.with(MachineState.unknown(""), List.of(), Optional.empty(), Optional.empty(),
                Optional.empty()).readings().isEmpty(), "读不到的不写");
    }

    @Test
    void 鼓风机沿风向往前找置物台_水在第一格_被实心方块挡住就停() {
        BlockPos fan = new BlockPos(0, 64, 0);
        var east = Blocks.DISPENSER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST);
        // 第一格是水，第二格是置物台：找到第二格。
        assertEquals(Optional.of(new BlockPos(2, 64, 0)), ProcessingSpots.fanDepot(east, fan,
                cell -> cell.equals(new BlockPos(2, 64, 0)), cell -> false));
        // 第二格是石头挡着风，第三格的置物台吹不到。
        assertTrue(ProcessingSpots.fanDepot(east, fan, cell -> cell.equals(new BlockPos(3, 64, 0)),
                cell -> cell.equals(new BlockPos(2, 64, 0))).isEmpty());
        // 六格以内都没有置物台。
        assertTrue(ProcessingSpots.fanDepot(east, fan, cell -> false, cell -> false).isEmpty());
    }

    @Test
    void 工作盆_搅拌器下的盆_朝上的锯_石磨都扔上去_锯不朝上给不出() {
        BlockPos at = new BlockPos(0, 70, 0);
        BlockPos basin = new BlockPos(0, 68, 0);
        var stone = Blocks.STONE.defaultBlockState();
        assertEquals(Optional.of(new StationHandling.TossSpot(at, StationHandling.BASIN_RIM)),
                StationHandling.spotFor(CreateMachineCatalog.Work.BASIN, stone, at, cell -> true, cell -> stone));
        // 搅拌器下方第二格是工作盆时扔进那个盆；那里没有盆就给不出。
        assertEquals(Optional.of(new StationHandling.TossSpot(basin, StationHandling.BASIN_RIM)),
                StationHandling.spotFor(CreateMachineCatalog.Work.MIXER, stone, at, basin::equals, cell -> stone));
        assertTrue(StationHandling.spotFor(CreateMachineCatalog.Work.MIXER, stone, at, cell -> false, cell -> stone)
                .isEmpty());
        // 锯的朝向在方块状态的 facing 上：拿发射器的方块状态代它摆朝向。
        var up = Blocks.DISPENSER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP);
        var east = Blocks.DISPENSER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST);
        assertEquals(Optional.of(new StationHandling.TossSpot(at, StationHandling.SAW_TOP)),
                StationHandling.spotFor(CreateMachineCatalog.Work.SAW, up, at, cell -> false, cell -> stone));
        assertTrue(StationHandling.spotFor(CreateMachineCatalog.Work.SAW, east, at, cell -> false, cell -> stone)
                .isEmpty());
        assertEquals(Optional.of(new StationHandling.TossSpot(at, StationHandling.MILLSTONE_RIM)),
                StationHandling.spotFor(CreateMachineCatalog.Work.GRINDER, stone, at, cell -> false, cell -> stone));
    }
}
