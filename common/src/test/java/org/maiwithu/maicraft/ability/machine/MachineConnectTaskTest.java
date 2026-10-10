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
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/** 接网络的任务与核对判断：已接着不再动、转速为零照实说、不同网如实说差一段、没给来源扫一片找。 */
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

        TaskResult run(MachineConnectInput input) {
            MachineConnectTask task = new MachineConnectTask(input, services,
                    new MachineArchives(new DocumentStore(tempDir.resolve("state.sqlite")), "world1"));
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
