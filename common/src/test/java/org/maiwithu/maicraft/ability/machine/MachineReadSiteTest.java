// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/** 读现场成机器蓝图：挑 target 那一台、框包围盒连中间的原版方块一起抄、建档记世界记忆、名字被占不覆盖、指档案不另建。 */
class MachineReadSiteTest {

    private static final BlockPos PRESS = new BlockPos(2, 64, 3);
    private static final String DIM = "minecraft:overworld";

    @TempDir
    Path tempDir;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 读_target_那一台_包围盒里的原版方块一起抄_建档并记进世界记忆() {
        Rig rig = new Rig(tempDir);
        // L 形三格机器，包围盒里还夹着一格石头：石头不是机器也要抄进去。
        rig.machine(PRESS, PRESS.east(), PRESS.east().above());
        rig.blocks.put(PRESS.above(), Blocks.STONE.defaultBlockState());
        TaskResult result = rig.readSite(positionOf(PRESS), null);
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        MachineDetails.SiteRead site = ((MachineDetails) result.details()).site();
        String name = "机器@" + PRESS.getX() + "," + PRESS.getY() + "," + PRESS.getZ();
        assertEquals(name, site.archive());
        assertTrue(site.archived());
        assertEquals(PRESS, site.anchor(), "锚点是包围盒最小角");
        assertEquals(List.of(2, 2, 1), site.size());
        assertEquals(4, site.cells(), "三格机器加一格石头");
        assertTrue(site.complete());
        MachineArchive archive = rig.archives.find(name).orElseThrow();
        assertEquals(4, archive.blueprint().cells().size());
        assertTrue(rig.memory.allRecords().stream().anyMatch(record -> record.kind() == MemoryKind.MACHINE));
        // 正文能原样喂回 machine_build。
        assertEquals(4, MachineBlueprints.parse(site.blueprint()).cells().size());
        assertTrue(result.summary().contains("读成了机器蓝图"), result.summary());
    }

    @Test
    void target_不在机器上就挑最近的那一台() {
        Rig rig = new Rig(tempDir);
        rig.machine(PRESS);
        rig.machine(PRESS.east(5));
        TaskResult result = rig.readSite(positionOf(PRESS.east(4)), "远的那台");
        MachineDetails.SiteRead site = ((MachineDetails) result.details()).site();
        assertEquals(PRESS.east(5), site.anchor());
        assertEquals("远的那台", site.archive());
    }

    @Test
    void 名字已经有档案在用_不覆盖_蓝图照给() {
        Rig rig = new Rig(tempDir);
        MachineBlueprint old = new MachineBlueprint(List.of(
                PlannedCell.block(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), Set.of())),
                List.of(), List.of(), List.of(), List.of());
        rig.archives.save(MachineArchive.fresh("压板机", DIM, new BlockPos(100, 64, 100), old, null));
        rig.machine(PRESS);
        TaskResult result = rig.readSite(positionOf(PRESS), "压板机");
        MachineDetails.SiteRead site = ((MachineDetails) result.details()).site();
        assertFalse(site.archived());
        assertTrue(site.notes().stream().anyMatch(note -> note.contains("已经有一台机器在用")), site.notes().toString());
        assertEquals(new BlockPos(100, 64, 100), rig.archives.find("压板机").orElseThrow().anchor(), "旧档案没被覆盖");
        assertEquals(1, site.cells());
    }

    @Test
    void 指档案读_不另建档_锚点用档案的() {
        Rig rig = new Rig(tempDir);
        MachineBlueprint archived = new MachineBlueprint(List.of(
                PlannedCell.block(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState(), Set.of()),
                PlannedCell.block(new BlockPos(0, 1, 0), Blocks.STONE.defaultBlockState(), Set.of())),
                List.of(), List.of(), List.of(), List.of());
        MachineArchive archive = MachineArchive.fresh("压板机", DIM, PRESS, archived, null);
        rig.archives.save(archive);
        rig.machine(PRESS);
        rig.blocks.put(PRESS.above(), Blocks.DIRT.defaultBlockState());
        MachineInspectTask task = new MachineInspectTask(new MachineInspectInput(new Target.Landmark("压板机"), 8,
                archive, true, null), rig.services, rig.archives, rig.memory);
        MachineDetails details = (MachineDetails) rig.finish(task).details();
        assertEquals("压板机", details.site().archive());
        assertFalse(details.site().archived());
        assertEquals(PRESS, details.site().anchor());
        assertEquals(2, details.site().cells(), "档案格一起框进来：上面那格现在是泥土，照现场抄");
        assertEquals(1, details.check().differences().size(), "整机比对照给");
        assertTrue(rig.memory.allRecords().stream().noneMatch(record -> record.kind() == MemoryKind.MACHINE));
    }

    @Test
    void 一台认得的机器都没有_按没找到收场() {
        Rig rig = new Rig(tempDir);
        TaskResult result = rig.readSite(positionOf(PRESS), null);
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NOT_FOUND, result.problem().kind());
    }

    private static Target positionOf(BlockPos cell) {
        return new Target.Position(cell.getX(), cell.getY(), cell.getZ(), null);
    }

    /** 离线替身：认领熔炉的机器类型、按摆好的格给的范围读端与现场视图、真的档案库与世界记忆。 */
    private static final class Rig {
        final MachineTestDoubles.FakeMachineType type = new MachineTestDoubles.FakeMachineType("machinetest:press",
                "测试压机", MachineRole.PROCESSING, state -> state.is(Blocks.FURNACE));
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final MachineArchives archives;
        final WorldMemory memory;
        final MachineServices services;
        private long ticks;

        Rig(Path tempDir) {
            DocumentStore documents = new DocumentStore(tempDir.resolve("state.sqlite"));
            archives = new MachineArchives(documents, "world1");
            memory = new WorldMemory(documents, "world1");
            ReadsMachineArea area = (dimension, center, radius) -> new ReadsMachineArea.Round(blocks.entrySet().stream()
                    .filter(entry -> !entry.getValue().isAir())
                    .map(entry -> new ReadsMachineArea.Cell(entry.getKey(), entry.getValue(), true)).toList(),
                    0, true, false);
            services = new MachineServices(() -> null, new MachineWorldView() {
                @Override public Optional<Spot> playerSpot() {
                    return Optional.of(new Spot(BlockPos.ZERO, DIM));
                }

                @Override public Optional<BlockState> stateAt(BlockPos at) {
                    return Optional.of(blocks.getOrDefault(at, Blocks.AIR.defaultBlockState()));
                }
            }, List.of(type), List.of(), area, new RecipeLookup(List.of(), new GameRecipeTable(Optional::empty)),
                    new MachineTestDoubles.FakeNeeds(), new MachineTestDoubles.FakeClose(),
                    new MachineTestDoubles.FakeClicks(), new MachineTestDoubles.FakeSeen(),
                    new MachineTestDoubles.FakePlaces());
        }

        void machine(BlockPos... cells) {
            for (BlockPos cell : cells) blocks.put(cell, Blocks.FURNACE.defaultBlockState());
        }

        TaskResult readSite(Target target, String name) {
            return finish(new MachineInspectTask(new MachineInspectInput(target, 8, null, true, name), services,
                    archives, memory));
        }

        TaskResult finish(MachineInspectTask task) {
            task.start(tick());
            for (int i = 0; i < 200; i++) {
                if (task.tick(tick()) instanceof TickResult.Finished finished) return finished.result();
            }
            throw new AssertionError("两百刻都没读完");
        }

        TickContext tick() {
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
    }
}
