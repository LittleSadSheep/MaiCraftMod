// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.recipe.GameRecipeTable;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.recipe.ShownStack;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用机器做东西的任务：选工序、备料、投料、开机只拨一次、出口只算新增、收产物按 collect。 */
class MachineRunTaskTest {

    private static final BlockPos CELL = new BlockPos(6, 64, 6);
    private static final String INGOT = "test:ingot";
    private static final String PLATE = "test:plate";

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 一条压板配方：一台压机、一个铁锭进、一块铁板出。 */
    private static ShownRecipe pressingRecipe() {
        return new ShownRecipe("test:pressing/plate", "test:pressing", "压制",
                List.of(ShownStack.item("test:press", "测试压机", 1)),
                List.of(ShownIngredient.of(ShownStack.item(INGOT, "铁锭", 1))),
                List.of(), List.of(ShownStack.item(PLATE, "铁板", 1)), null);
    }

    @Test
    void 出够数量拿回背包_按完成收场() {
        Rig rig = new Rig();
        rig.machineRunsAtFullSpeed();
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), PLATE, 2, true, 5,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        MachineRunDetails details = (MachineRunDetails) result.details();
        assertEquals(2, details.produced());
        assertEquals(2, details.collected());
        assertEquals("test:pressing", details.recipe().category());
        assertEquals(2, details.batches());
        assertEquals(1, details.fed().size());
        assertEquals(2, details.fed().get(0).count());
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.ITEM_GAINED
                && PLATE.equals(change.what()) && change.count() == 2));
        assertTrue(result.changes().stream().anyMatch(change -> change.kind() == Change.Kind.ITEM_CONSUMED
                && INGOT.equals(change.what())));
        assertEquals(1, rig.needs.requests.size(), "一种原料一次备料请求，按批次放大");
        assertEquals(2, rig.needs.requests.get(0).count());
    }

    @Test
    void 机器停着_走近右键拨一次再等() {
        Rig rig = new Rig();
        rig.type.state = () -> new MachineState(MachineState.Activity.OFFLINE, null, null, List.of(),
                Map.of(), "");
        // 只开机：没给 item，拨完开关就算完成。
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), null, 1, true, 5,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(1, rig.close.calls);
        assertEquals(List.of(CELL), rig.clicks.clicked);
        assertTrue(result.summary().contains("开机了"), result.summary());
    }

    @Test
    void 机器本来在转_不拨开关() {
        Rig rig = new Rig();
        rig.machineRunsAtFullSpeed();
        rig.type.state = () -> new MachineState(MachineState.Activity.RUNNING, null, null, List.of(),
                Map.of(), "");
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), null, 1, true, 5,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        assertEquals(0, rig.clicks.clicked.size(), "在转就不拨");
    }

    @Test
    void 到等待上限还没出够_按部分完成附读数() {
        Rig rig = new Rig();
        rig.machineRunsAtFullSpeed();
        // 只出得出一半：投两批的料，出口只长出一件。
        rig.type.feedActions.clear();
        rig.type.feedActions.add(new MachineTestDoubles.Scripted(1,
                () -> rig.type.output.put(PLATE, rig.type.output.getOrDefault(PLATE, 0) + 1)));
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), PLATE, 2, true, 1,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.PARTIAL, result.status(), result.summary());
        assertEquals(1, ((MachineRunDetails) result.details()).produced());
        assertTrue(result.problem().message().contains("没等到全部产物"), result.problem().message());
    }

    @Test
    void collect为假_出了货也留在出口() {
        Rig rig = new Rig();
        rig.machineRunsAtFullSpeed();
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), PLATE, 1, false, 5,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.DONE, result.status(), result.summary());
        // 机器投一次料出满一批（替身一次出两件）：出了就算数，不收就不该有拿到东西的变化。
        assertEquals(2, ((MachineRunDetails) result.details()).produced());
        assertEquals(0, ((MachineRunDetails) result.details()).collected());
        assertTrue(result.changes().stream().noneMatch(change -> change.kind() == Change.Kind.ITEM_GAINED),
                "不收就不该有拿到东西的变化");
    }

    @Test
    void 查不到这台机器能做的配方_按做不了说() {
        Rig rig = new Rig();
        rig.machineRunsAtFullSpeed();
        // 查看器只认另一台机器：压板配方挂在工作站 test:other 上。
        rig.viewer.putMaking(PLATE, List.of(new ShownRecipe("other:plate", "other", "其它",
                List.of(ShownStack.item("test:other", "别的机器", 1)),
                List.of(ShownIngredient.of(ShownStack.item(INGOT, "铁锭", 1))),
                List.of(), List.of(ShownStack.item(PLATE, "铁板", 1)), null)));
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), PLATE, 1, true, 5,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, result.problem().kind());
    }

    @Test
    void 认不出的机器_按不支持结束() {
        Rig rig = new Rig();
        rig.machineRunsAtFullSpeed();
        rig.world.blocks.put(CELL, Blocks.DISPENSER.defaultBlockState());
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), PLATE, 1, true, 5,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.UNSUPPORTED, result.problem().kind());
    }

    @Test
    void 备料拿不齐_按缺东西收场() {
        Rig rig = new Rig();
        rig.machineRunsAtFullSpeed();
        rig.needs.fail = true;
        TaskResult result = rig.run(new MachineRunInput(positionOf(CELL), PLATE, 1, true, 5,
                Permissions.DEFAULT));
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind());
    }

    private static Target positionOf(BlockPos cell) {
        return new Target.Position(cell.getX(), cell.getY(), cell.getZ(), null);
    }

    /** 一套离线替身：投料一完成出口就出满，机器一直读作在转。 */
    private static final class Rig {
        final MachineTestDoubles.FakeMachineType type =
                new MachineTestDoubles.FakeMachineType("test:press", "测试压机",
                        MachineRole.PROCESSING, state -> state.is(Blocks.FURNACE));
        final MachineTestDoubles.FakeWorld world = new MachineTestDoubles.FakeWorld();
        final MachineTestDoubles.FakeNeeds needs = new MachineTestDoubles.FakeNeeds();
        final MachineTestDoubles.FakeClose close = new MachineTestDoubles.FakeClose();
        final MachineTestDoubles.FakeClicks clicks = new MachineTestDoubles.FakeClicks();
        final MachineTestDoubles.FakeViewer viewer = new MachineTestDoubles.FakeViewer();
        final MachineServices services;

        Rig() {
            world.blocks.put(CELL, Blocks.FURNACE.defaultBlockState());
            type.state = () -> new MachineState(MachineState.Activity.RUNNING, null, null, List.of(),
                    Map.of(), "");
            services = new MachineServices(() -> null, world, List.of(type), List.of(),
                    new MachineTestDoubles.FakeArea(),
                    new RecipeLookup(List.of(viewer), new GameRecipeTable(Optional::empty)),
                    needs, close, clicks, new MachineTestDoubles.FakeSeen(),
                    new MachineTestDoubles.FakePlaces());
        }

        // 投料一完成，出口立刻长出要做的两件；取货一步收场。
        void machineRunsAtFullSpeed() {
            viewer.putMaking(PLATE, List.of(pressingRecipe()));
            viewer.putAtWorkstation("test:press", List.of(pressingRecipe()));
            type.feedActions.add(new MachineTestDoubles.Scripted(1,
                    () -> type.output.put(PLATE, type.output.getOrDefault(PLATE, 0) + 2)));
            type.takeActions.add(new MachineTestDoubles.Scripted(1, () -> { }));
        }

        TaskResult run(MachineRunInput input) {
            MachineRunTask task = new MachineRunTask(input, services);
            task.start(tick());
            for (int i = 0; i < 600; i++) {
                if (task.tick(tick()) instanceof TickResult.Finished finished) {
                    return finished.result();
                }
            }
            throw new AssertionError("六百刻都没做完");
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
