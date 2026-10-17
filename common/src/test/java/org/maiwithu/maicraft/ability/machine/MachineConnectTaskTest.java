// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.SharedConstants;
import java.util.EnumSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.LineMaterials;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.Attempt;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/**
 * 接网络的任务与核对判断：已接着不再动、转速为零照实说、不同网如实说差一段、没给来源扫一片找；
 * 铺线路那一段跟着接网络暂停、先到安全处再停、收尾。
 */
class MachineConnectTaskTest {

    private static final BlockPos TARGET = new BlockPos(6, 64, 6);
    private static final BlockPos SOURCE = new BlockPos(2, 64, 2);

    @TempDir
    Path tempDir;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static MachineConnectInput input(String kind, Target target, Target source) {
        return new MachineConnectInput(kind, target,
                source == null ? Optional.empty() : Optional.of(source), 16, null, Permissions.DEFAULT);
    }

    @Test
    void 两端同一张网且机器在转_完成不再动() {
        Rig rig = new Rig(tempDir);
        rig.reader.put(TARGET, "net1");
        rig.reader.put(SOURCE, "net1");
        rig.machineRuns();
        TaskResult result = rig.run(input("kinetic", positionOf(TARGET), positionOf(SOURCE)));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        MachineConnectDetails details = (MachineConnectDetails) result.details();
        assertTrue(details.joined());
        assertTrue(details.running());
        assertTrue(result.summary().contains("已经接着"), result.summary());
    }

    @Test
    void 同一张网但转速为零_照实说接上了没在转() {
        Rig rig = new Rig(tempDir);
        rig.reader.put(TARGET, "net1");
        rig.reader.put(SOURCE, "net1");
        rig.type.state = () -> new MachineState(MachineState.Activity.IDLE, 0.0, null, List.of(), Map.of(), "");
        TaskResult result = rig.run(input("kinetic", positionOf(TARGET), positionOf(SOURCE)));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        MachineConnectDetails details = (MachineConnectDetails) result.details();
        assertTrue(details.joined());
        assertFalse(details.running(), result.summary());
        assertTrue(result.summary().contains("转速为 0"), result.summary());
    }

    @Test
    void 两端不同网_如实说铺不了线路() {
        Rig rig = new Rig(tempDir);
        rig.reader.put(TARGET, "net2");
        rig.reader.put(SOURCE, "net1");
        rig.machineRuns();
        TaskResult result = rig.run(input("kinetic", positionOf(TARGET), positionOf(SOURCE)));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.UNSUPPORTED, result.problem().kind());
        assertTrue(result.problem().message().contains("还不是同一张"), result.problem().message());
        MachineConnectDetails details = (MachineConnectDetails) result.details();
        assertFalse(details.joined());
    }

    @Test
    void 没给来源_扫一片挑挂着这种网的格子() {
        Rig rig = new Rig(tempDir);
        rig.reader.put(TARGET, "net2");
        rig.reader.put(SOURCE, "net1");
        rig.machineRuns();
        rig.area.offer(new ReadsMachineArea.Round(
                List.of(new ReadsMachineArea.Cell(SOURCE, Blocks.FURNACE.defaultBlockState(), false)), 0, true, false));
        TaskResult result = rig.run(input("kinetic", positionOf(TARGET), null));
        assertEquals(TaskResult.Status.FAILED, result.status());
        MachineConnectDetails details = (MachineConnectDetails) result.details();
        assertNotNull(details.sourceCell(), "来源扫到了并写进细节");
        assertEquals(new WorldPosition(SOURCE.getX(), SOURCE.getY(),
                SOURCE.getZ(), "minecraft:overworld"), details.sourceCell());
    }

    @Test
    void 这个种类没有读取器_按参数不对说() {
        Rig rig = new Rig(tempDir);
        rig.machineRuns();
        TaskResult result = rig.run(input("fluid", positionOf(TARGET), null));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.INVALID_PARAMETER, result.problem().kind());
    }

    @Test
    void 铺线路跟着接网络暂停_先停稳与收尾() {
        // 两台机器在一条线上、中间空着：规划出直线后铺线路按动作推进，接网络的暂停、先停稳、收尾都转到那段施工上。
        Rig rig = new Rig(tempDir);
        BlockPos source = new BlockPos(2, 64, 6);
        rig.world.blocks.put(source, Blocks.FURNACE.defaultBlockState());
        for (int x = 0; x <= 8; x++) {
            BlockPos cell = new BlockPos(x, 64, 6);
            if (!cell.equals(source) && !cell.equals(TARGET)) rig.world.blocks.put(cell, Blocks.AIR.defaultBlockState());
        }
        rig.reader.put(TARGET, "net2");
        rig.reader.put(source, "net1");
        rig.reader.lines = new ShaftLines();
        rig.type.ports = List.of(
                new ExchangePoint(BlockPos.ZERO, Direction.EAST, ExchangePoint.Medium.ROTATION, ExchangePoint.Flow.BOTH, ""),
                new ExchangePoint(BlockPos.ZERO, Direction.WEST, ExchangePoint.Medium.ROTATION, ExchangePoint.Flow.BOTH, ""));
        HeldBuild laying = new HeldBuild();
        rig.lineBuilding = line -> laying;
        MachineConnectTask task = rig.task(input("kinetic", positionOf(TARGET), positionOf(source)));
        task.start(rig.tick());
        for (int i = 0; i < 50 && laying.ticks == 0; i++) {
            assertFalse(task.tick(rig.tick()) instanceof TickResult.Finished, "接网络没走到铺线路就收场了");
        }
        assertTrue(laying.ticks > 0, "铺线路那段在做");

        assertEquals(Interruptibility.UNSAFE_TO_STOP, task.interruptibility(rig.tick()),
                "铺线路手上那一下停不得，接网络就停不得");
        assertTrue(task.stopSafely(), "叫接网络先到安全处再停，转给铺线路去收尾");
        assertEquals(1, laying.stops, "铺线路收到先停稳");
        assertFalse(task.stoppedSafely(), "那段还没停稳，接网络也没停稳");
        task.pause();
        assertEquals(1, laying.pauses, "接网络暂停，铺线路跟着暂停");
        TaskResult closed = task.close(CloseReason.CANCELLED);
        assertEquals(1, laying.closes, "接网络收尾，铺线路按取消收尾");
        assertTrue(closed.attempts().stream().anyMatch(attempt -> attempt.tried().equals("铺线路")),
                "铺线路收尾交代的事实并进接网络的结果：" + closed.attempts());
    }

    /** 测试线材：原木沿轴向接两面，拐弯用石头；不会把旁边的方块一起接上，也没有远距离跨越。 */
    private static final class ShaftLines implements LineMaterials {
        @Override public ExchangePoint.Medium medium() {
            return ExchangePoint.Medium.ROTATION;
        }

        @Override public BlockState straight(Direction.Axis axis) {
            return Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, axis);
        }

        @Override public Optional<BlockState> turn(Direction.Axis in, Direction.Axis out) {
            return Optional.of(Blocks.STONE.defaultBlockState());
        }

        @Override public boolean joins(BlockState neighbor) {
            return false;
        }

        @Override public Set<Direction> connectedFaces(BlockState piece) {
            if (!piece.hasProperty(BlockStateProperties.AXIS)) return EnumSet.allOf(Direction.class);
            Direction.Axis axis = piece.getValue(BlockStateProperties.AXIS);
            return Set.of(Direction.get(Direction.AxisDirection.POSITIVE, axis),
                    Direction.get(Direction.AxisDirection.NEGATIVE, axis));
        }

        @Override public Optional<Span> span() {
            return Optional.empty();
        }
    }

    /** 一直在铺、停不得的那段施工：记下被推进、叫停稳、暂停、收尾了几次，收尾时交代一条试过的办法。 */
    private static final class HeldBuild implements Task {
        int ticks;
        int stops;
        int pauses;
        int closes;

        @Override public TickResult tick(TickContext context) {
            ticks++;
            return TickResult.RUNNING;
        }

        @Override public void pause() {
            pauses++;
        }

        // 悬在半空铺轴：先到安全处再停要先落地，这几刻里一直没停稳。
        @Override public boolean stopSafely() {
            stops++;
            return true;
        }

        @Override public boolean stoppedSafely() {
            return false;
        }

        @Override public Interruptibility interruptibility(TickContext context) {
            return Interruptibility.UNSAFE_TO_STOP;
        }

        @Override public TaskResult close(CloseReason reason) {
            closes++;
            return TaskResult.builder(TaskResult.Status.CANCELLED, "铺到一半停下")
                    .attempts(List.of(new Attempt("铺线路", "铺了两格轴就被叫停"))).build();
        }

        @Override public String describe() {
            return "停不得的铺线路";
        }
    }

    private static Target positionOf(BlockPos cell) {
        return new Target.Position(cell.getX(), cell.getY(), cell.getZ(), null);
    }

    /** 一套离线替身：目标一格熔炉（有机器类型认领）、一张应力网、扫描按脚本给。 */
    private static final class Rig {
        final MachineTestDoubles.FakeMachineType type =
                new MachineTestDoubles.FakeMachineType("test:press", "测试压机", MachineRole.PROCESSING,
                        state -> state.is(Blocks.FURNACE));
        final MachineTestDoubles.FakeWorld world = new MachineTestDoubles.FakeWorld();
        final MachineTestDoubles.FakeNetworkReader reader =
                new MachineTestDoubles.FakeNetworkReader("kinetic", "应力网络：轴、齿轮连起来的一组");
        final MachineTestDoubles.FakeArea area = new MachineTestDoubles.FakeArea();
        final MachineServices services;
        final Path tempDir;
        /** 铺线路的施工任务怎么建；没给的场景不该走到施工。 */
        MachineConnectTask.BuildsLine lineBuilding = line -> {
            throw new AssertionError("这里的场景不该走到施工");
        };

        Rig(Path tempDir) {
            this.tempDir = tempDir;
            world.dimension = "minecraft:overworld";
            world.playerAt = TARGET.west();
            world.blocks.put(TARGET, Blocks.FURNACE.defaultBlockState());
            services = new MachineServices(() -> null, world, List.of(type), List.of(reader),
                    area, new RecipeLookup(List.of(), new GameRecipeTable(Optional::empty)),
                    new MachineTestDoubles.FakeNeeds(), new MachineTestDoubles.FakeClose(),
                    new MachineTestDoubles.FakeClicks(), new MachineTestDoubles.FakeSeen(),
                    new MachineTestDoubles.FakePlaces());
        }

        void machineRuns() {
            type.state = () -> new MachineState(MachineState.Activity.RUNNING, 120.0, null, List.of(), Map.of(), "");
        }

        MachineConnectTask task(MachineConnectInput input) {
            DocumentStore documents = new DocumentStore(tempDir.resolve("state.sqlite"));
            return new MachineConnectTask(input, services, new MachineArchives(documents, "world1"),
                    new WorldMemory(documents, "world1"), lineBuilding);
        }

        TaskResult run(MachineConnectInput input) {
            MachineConnectTask task = task(input);
            task.start(tick());
            for (long i = 0; i < 2000; i++) {
                if (task.tick(tick()) instanceof TickResult.Finished finished) {
                    return finished.result();
                }
            }
            throw new AssertionError("两千刻都没做完");
        }

        private TickContext tick() {
            long now = ++ticks;
            return new TickContext() {
                @Override public long gameTick() {
                    return now;
                }

                @Override public PlayerContext player() {
                    return null;
                }
            };
        }

        private long ticks;
    }
}
