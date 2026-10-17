// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.travel.ReadsSeenTargets;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 看机器的任务：分刻读格、认领分组、认不出的格与没加载的格都如实进结果。 */
class MachineInspectTaskTest {

    private static final BlockPos PRESS = new BlockPos(2, 64, 3);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 认领的相邻格分成一台并读出状态() {
        Rig rig = new Rig();
        rig.type.state = () -> new MachineState(MachineState.Activity.RUNNING, 120.0, null, List.of(),
                Map.of(), "");
        rig.area.offer(new ReadsMachineArea.Round(List.of(
                new ReadsMachineArea.Cell(PRESS, Blocks.FURNACE.defaultBlockState(), true),
                new ReadsMachineArea.Cell(PRESS.above(), Blocks.FURNACE.defaultBlockState(), true)),
                0, true, false));
        MachineDetails details = run(rig, positionOf(PRESS));
        assertEquals(1, details.machines().size());
        // 两格都归同一种机器：运行状态每种机器读一格，只有一条。
        assertEquals(1, details.machines().get(0).running().size());
        assertEquals(MachineState.Activity.RUNNING,
                details.machines().get(0).running().get(0).state().activity());
        assertEquals(2, details.machines().get(0).types().get(0).blocks().size(), "按角色归并的组成，两格都在");
        assertTrue(details.machines().get(0).networks().isEmpty(), "没有挂网时不给网络条目");
        assertTrue(details.scanComplete());
    }

    @Test
    void 没认领的模组机器按方块归并进清单() {
        // 离线建不出模组方块：方块 ID 的分拣是纯函数，直接给"create:mechanical_press"这种 ID 测；
        // 任务这边用真实注册表验证"原版方块实体不进清单"，模组方块走同一条路进清单。
        assertTrue(MachineInspectTask.looksLikeUnclaimedMachine(true, "create:mechanical_press"));
        MachineDetails details = runUnclaimedViaVanillaBlock();
        assertEquals(0, details.machines().size());
    }

    private MachineDetails runUnclaimedViaVanillaBlock() {
        Rig rig = new Rig();
        rig.area.offer(new ReadsMachineArea.Round(List.of(
                new ReadsMachineArea.Cell(PRESS, Blocks.CHEST.defaultBlockState(), true)),
                0, true, false));
        return run(rig, positionOf(PRESS));
    }

    @Test
    void 原版方块实体不算看着像机器() {
        Rig rig = new Rig();
        rig.area.offer(new ReadsMachineArea.Round(List.of(
                new ReadsMachineArea.Cell(PRESS, Blocks.CHEST.defaultBlockState(), true)),
                0, true, false));
        MachineDetails details = run(rig, positionOf(PRESS));
        assertEquals(0, details.machines().size());
        assertEquals(0, details.unrecognized().size());
        // 纯函数分两头都堵住：原版方块与没有方块实体的格都不算。
        assertTrue(!MachineInspectTask.looksLikeUnclaimedMachine(true, "minecraft:chest"));
        assertTrue(!MachineInspectTask.looksLikeUnclaimedMachine(false, "create:mechanical_press"));
    }

    @Test
    void 没加载的格只数个数_不冒充没有() {
        Rig rig = new Rig();
        rig.area.offer(new ReadsMachineArea.Round(List.of(), 7, true, false));
        MachineDetails details = run(rig, positionOf(PRESS));
        assertEquals(0, details.machines().size());
        assertEquals(7, details.unloadedCells());
        assertTrue(details.scanComplete());
    }

    @Test
    void 没扫完的轮继续等_扫完才给结论() {
        Rig rig = new Rig();
        rig.area.offer(new ReadsMachineArea.Round(List.of(), 0, false, false));
        rig.area.offer(new ReadsMachineArea.Round(List.of(
                new ReadsMachineArea.Cell(PRESS, Blocks.FURNACE.defaultBlockState(), true)), 0, true, false));
        MachineDetails details = run(rig, positionOf(PRESS));
        assertEquals(1, details.machines().size());
    }

    @Test
    void 看到一半换了世界_这一眼作废() {
        Rig rig = new Rig();
        rig.area.offer(new ReadsMachineArea.Round(List.of(), 0, false, true));
        TaskResult result = runRaw(rig, positionOf(PRESS));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.UNREACHABLE, result.problem().kind());
    }

    @Test
    void 指档案名时和档案整机比对_没对上的格列出来_机器带上档案名() {
        Rig rig = new Rig();
        BlockPos anchor = new BlockPos(10, 64, 10);
        rig.blocks.put(anchor, Blocks.FURNACE.defaultBlockState());
        rig.blocks.put(anchor.east(), Blocks.DIRT.defaultBlockState());
        rig.area.offer(new ReadsMachineArea.Round(List.of(
                new ReadsMachineArea.Cell(anchor, Blocks.FURNACE.defaultBlockState(), true)), 0, true, false));
        MachineBlueprint blueprint = new MachineBlueprint(List.of(
                PlannedCell.block(BlockPos.ZERO, Blocks.FURNACE.defaultBlockState(), Set.of()),
                PlannedCell.block(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState(), Set.of())),
                List.of(), List.of(), List.of(), List.of());
        MachineArchive archive = MachineArchive.fresh("压板机", "minecraft:overworld", anchor, blueprint, null);
        MachineInspectTask task = new MachineInspectTask(new MachineInspectInput(new Target.Landmark("压板机"), 8,
                archive), rig.services);
        task.start(rig.tick());
        TaskResult result = null;
        for (int i = 0; i < 200 && result == null; i++) {
            if (task.tick(rig.tick()) instanceof TickResult.Finished finished) result = finished.result();
        }
        MachineDetails details = (MachineDetails) result.details();
        assertEquals("压板机", details.archive());
        assertEquals("压板机", details.machines().getFirst().name());
        assertTrue(details.check().complete());
        assertEquals(1, details.check().differences().size());
        assertEquals("wrong_block", details.check().differences().getFirst().state());
        assertEquals("minecraft:dirt", details.check().differences().getFirst().found());
        assertTrue(result.summary().contains("1 格没对上"), result.summary());
    }

    private static Target positionOf(BlockPos cell) {
        return new Target.Position(cell.getX(), cell.getY(), cell.getZ(), null);
    }

    private static MachineDetails run(Rig rig, Target target) {
        return (MachineDetails) runRaw(rig, target).details();
    }

    private static TaskResult runRaw(Rig rig, Target target) {
        MachineInspectTask task = new MachineInspectTask(new MachineInspectInput(target, 8, null), rig.services);
        task.start(rig.tick());
        for (int i = 0; i < 200; i++) {
            if (task.tick(rig.tick()) instanceof TickResult.Finished finished) {
                return finished.result();
            }
        }
        throw new AssertionError("两百刻都没看完");
    }

    /** 一套离线替身：认领熔炉的机器类型、按脚本给格的范围读端、都在本维度。 */
    private static final class Rig {
        final MachineTestDoubles.FakeMachineType type =
                new MachineTestDoubles.FakeMachineType("machinetest:press", "测试压机",
                        MachineRole.PROCESSING, state -> state.is(Blocks.FURNACE));
        final MachineTestDoubles.FakeArea area = new MachineTestDoubles.FakeArea();
        /** 现场：记下的格是什么方块，其余是空气。 */
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final MachineServices services;

        Rig() {
            RecipeLookup recipes = new RecipeLookup(List.of(), new GameRecipeTable(Optional::empty));
            services = new MachineServices(() -> null, new MachineWorldView() {
                @Override public Optional<Spot> playerSpot() {
                    return Optional.of(new Spot(BlockPos.ZERO, "minecraft:overworld"));
                }

                @Override public Optional<BlockState> stateAt(BlockPos at) {
                    return Optional.of(blocks.getOrDefault(at, Blocks.AIR.defaultBlockState()));
                }
            }, List.of(type), List.of(), area, recipes, new MachineTestDoubles.FakeNeeds(),
                    new MachineTestDoubles.FakeClose(), new MachineTestDoubles.FakeClicks(),
                    new MachineTestDoubles.FakeSeen(), new MachineTestDoubles.FakePlaces());
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

        private long ticks;
    }
}
