// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.travel.ReadsSeenTargets;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 改机器设置的任务：不认识的键一次报全、循环切换直到读回相等、转满一圈如实做不到。 */
class MachineConfigureTaskTest {

    private static final BlockPos CELL = new BlockPos(4, 64, 2);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 一项点到读回相等_记变化后完成() {
        Rig rig = new Rig();
        rig.type.settingValues.put("side.north", "input");
        // 配置器点一次沿 枚举顺序 切到下一个：input → output。
        rig.type.onChange.put("side.north", () -> rig.type.settingValues.put("side.north", "output"));
        TaskResult result = rig.run(CELL, Map.of("side.north", "output"));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        MachineConfigureDetails details = (MachineConfigureDetails) result.details();
        assertEquals("input", details.applied().get(0).before());
        assertEquals("output", details.applied().get(0).after());
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.BLOCK_CHANGED
                && change.note().contains("side.north：input → output")));
    }

    @Test
    void 本来就是这个值_不动手直接完成() {
        Rig rig = new Rig();
        rig.type.settingValues.put("side.north", "output");
        TaskResult result = rig.run(CELL, Map.of("side.north", "output"));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(0, rig.type.onChange.size(), "没有要改的就不装动作");
        assertEquals("output", ((MachineConfigureDetails) result.details()).applied().get(0).after());
    }

    @Test
    void 转满一圈还到不了_按做不到收场() {
        Rig rig = new Rig();
        rig.type.settingValues.put("mode", "甲");
        // 这一档点一次换一个，三个值转圈：甲 → 乙 → 甲 → ……永远到不了"丙"。
        rig.type.onChange.put("mode", () -> rig.type.settingValues.put("mode",
                "乙".equals(rig.type.settingValues.get("mode")) ? "甲" : "乙"));
        TaskResult result = rig.run(CELL, Map.of("mode", "丙"));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, result.problem().kind());
        assertTrue(result.problem().message().contains("打转"), result.problem().message());
    }

    @Test
    void 不认识的键一次报全并附合法键() {
        Rig rig = new Rig();
        rig.type.settingValues.put("side.north", "input");
        rig.type.settingValues.put("side.south", "none");
        TaskResult result = rig.run(CELL, Map.of("side.east", "output", "side.up", "output"));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.INVALID_PARAMETER, result.problem().kind());
        assertTrue(result.problem().message().contains("side.east")
                && result.problem().message().contains("side.up"), result.problem().message());
        assertTrue(result.problem().message().contains("side.north")
                && result.problem().message().contains("side.south"), result.problem().message());
    }

    @Test
    void 认不出的机器按不支持结束并写明方块() {
        Rig rig = new Rig();
        rig.world.blocks.put(CELL, Blocks.DISPENSER.defaultBlockState());
        TaskResult result = rig.run(CELL, Map.of("side.north", "output"));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.UNSUPPORTED, result.problem().kind());
        assertTrue(result.problem().message().contains("dispenser"), result.problem().message());
    }

    @Test
    void 后一项做不到_前面改好的照实算数() {
        Rig rig = new Rig();
        rig.type.settingValues.put("a", "旧");
        rig.type.settingValues.put("b", "旧");
        rig.type.onChange.put("a", () -> rig.type.settingValues.put("a", "新"));
        // b 这一项机器不给改法：点不了。
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("a", "新");
        settings.put("b", "新");
        TaskResult result = rig.run(CELL, settings);
        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        assertEquals(1, ((MachineConfigureDetails) result.details()).applied().size());
        assertTrue(result.remaining().contains("b"));
    }

    @Test
    void 目标那格没加载_按到不了说() {
        Rig rig = new Rig();
        rig.world.blocks.remove(CELL);
        TaskResult result = rig.run(CELL, Map.of("side.north", "output"));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.UNREACHABLE, result.problem().kind());
    }

    /** 一套离线替身：认领发射器的机器类型（现场摆成熔炉才认得），设置读回与改法在类型里。 */
    private static final class Rig {
        final MachineTestDoubles.FakeMachineType type =
                new MachineTestDoubles.FakeMachineType("test:configurable", "测试配置机",
                        MachineRole.PROCESSING, state -> state.is(Blocks.FURNACE));
        final MachineTestDoubles.FakeWorld world = new MachineTestDoubles.FakeWorld();
        final MachineServices services;

        Rig() {
            world.blocks.put(CELL, Blocks.FURNACE.defaultBlockState());
            RecipeLookup recipes = new RecipeLookup(List.of(), new GameRecipeTable(Optional::empty));
            services = new MachineServices(() -> null, world, List.of(type), List.of(),
                    new MachineTestDoubles.FakeArea(), recipes, new MachineTestDoubles.FakeNeeds(),
                    new MachineTestDoubles.FakeClose(), new MachineTestDoubles.FakeClicks(),
                    new MachineTestDoubles.FakeSeen(), new MachineTestDoubles.FakePlaces());
        }

        TaskResult run(BlockPos cell, Map<String, String> settings) {
            MachineConfigureInput input = new MachineConfigureInput(positionOf(cell), settings,
                    Permissions.DEFAULT);
            MachineConfigureTask task = new MachineConfigureTask(input, services);
            task.start(tick());
            for (int i = 0; i < 300; i++) {
                if (task.tick(tick()) instanceof TickResult.Finished finished) {
                    return finished.result();
                }
            }
            throw new AssertionError("三百刻都没改完");
        }

        private static Target positionOf(BlockPos cell) {
            return new Target.Position(cell.getX(), cell.getY(), cell.getZ(), null);
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
