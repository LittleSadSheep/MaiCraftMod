// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.IntConsumer;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.survival.BurrowPlan.Ground;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/** 挖三填一：脚下挖得成封得住的坑才挖，挖三格、封坑口、等天亮、挖开坑口、爬回地面；挖不成就站定硬熬。 */
class BurrowInTaskTest {

    private static final BlockPos SURFACE = new BlockPos(10, 64, 10);

    /** 选点：脚下三格能挖、第四格踩得住、坑壁都是实心的才挖。 */
    @Test
    void solidGroundAllAroundIsBurrowable() {
        assertTrue(BurrowPlan.refusal(site(Ground.DIGGABLE, Ground.DIGGABLE)).isEmpty());
        assertTrue(BurrowPlan.refusal(site(Ground.UNBREAKABLE, Ground.DIGGABLE)).isEmpty(), "坑底是基岩也踩得住");
    }

    @Test
    void groundThatCannotHoldASealedHoleIsRefusedWithTheReason() {
        assertTrue(BurrowPlan.refusal(column(Ground.PROTECTED)).orElseThrow().contains("别人的东西"));
        assertTrue(BurrowPlan.refusal(column(Ground.OPEN)).orElseThrow().contains("空的"));
        assertTrue(BurrowPlan.refusal(column(Ground.UNBREAKABLE)).orElseThrow().contains("挖不动"));
        assertTrue(BurrowPlan.refusal(site(Ground.FLUID, Ground.DIGGABLE)).orElseThrow().contains("站不住"));
        assertTrue(BurrowPlan.refusal(site(Ground.DIGGABLE, Ground.FLUID)).orElseThrow().contains("液体"));
        assertTrue(BurrowPlan.refusal(site(Ground.DIGGABLE, Ground.OPEN)).orElseThrow().contains("缺口"));
    }

    @Test
    void digsThreeDownSealsWaitsForDayThenOpensAndClimbsOut() {
        Moves moves = new Moves();
        BurrowInTask task = new BurrowInTask(moves, (kind, message) -> moves.events.add(message));
        TaskResult result = run(task, moves, 400, tick -> {
            // 天黑熬到第 200 刻天亮。
            moves.night = tick < 200;
        });

        assertEquals(List.of(SURFACE.below(1), SURFACE.below(2), SURFACE.below(3), SURFACE.below(1)), moves.dugCells,
                "往下挖三格，天亮后挖开坑口");
        assertEquals(List.of(SURFACE.below(1)), moves.sealedCells, "封的是头顶那格（脚下第一格）");
        assertEquals(List.of(SURFACE), moves.climbedTo, "爬回挖坑前站的那一格");
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(4, result.changes().stream().filter(change -> change.kind() == Change.Kind.BLOCK_BROKEN).count());
        assertEquals(1, result.changes().stream().filter(change -> change.kind() == Change.Kind.BLOCK_PLACED).count());
    }

    @Test
    void groundThatCannotHoldAHoleMeansStandingStillUntilDay() {
        Moves moves = new Moves();
        moves.site = column(Ground.PROTECTED);
        BurrowInTask task = new BurrowInTask(moves, (kind, message) -> moves.events.add(message));
        TaskResult result = run(task, moves, 100, tick -> moves.night = tick < 50);

        assertTrue(moves.dugCells.isEmpty(), "别人的地方不挖");
        assertTrue(moves.events.stream().anyMatch(event -> event.contains("挖不成坑")), moves.events.toString());
        assertEquals(TaskResult.Status.DONE, result.status());
    }

    @Test
    void withoutABlockToSealItWaitsInTheOpenHoleAndStillClimbsOut() {
        Moves moves = new Moves();
        moves.hasSealBlock = false;
        BurrowInTask task = new BurrowInTask(moves, (kind, message) -> moves.events.add(message));
        run(task, moves, 400, tick -> moves.night = tick < 200);

        assertEquals(3, moves.dugCells.size(), "没封口就不用挖开坑口");
        assertTrue(moves.events.stream().anyMatch(event -> event.contains("没有能封坑口的方块")), moves.events.toString());
        assertEquals(List.of(SURFACE), moves.climbedTo);
    }

    // 推进任务直到结束；每刻先让测试改世界。
    private static TaskResult run(BurrowInTask task, Moves moves, int ticks, IntConsumer world) {
        Tick tick = new Tick();
        task.start(tick);
        for (int i = 0; i < ticks; i++) {
            world.accept(i);
            tick.now = i;
            if (task.tick(tick) instanceof TickResult.Finished finished) return finished.result();
        }
        throw new AssertionError("没有在 " + ticks + " 刻内收场");
    }

    private static BurrowPlan.Site site(Ground floor, Ground walls) {
        return new BurrowPlan.Site(List.of(Ground.DIGGABLE, Ground.DIGGABLE, Ground.DIGGABLE, floor),
                Collections.nCopies(12, walls));
    }

    private static BurrowPlan.Site column(Ground top) {
        return new BurrowPlan.Site(List.of(top, Ground.DIGGABLE, Ground.DIGGABLE, Ground.DIGGABLE),
                Collections.nCopies(12, Ground.DIGGABLE));
    }

    /** 刻号替身：挖坑任务只经 Moves 替身读现场。 */
    private static final class Tick implements TickContext {
        long now;

        @Override public long gameTick() { return now; }

        @Override public PlayerContext player() {
            throw new IllegalStateException("挖坑任务测试不碰角色对象");
        }
    }

    /** 现场替身：挖开一格角色就落进去；封口、出坑都一刻做完。 */
    private static final class Moves implements BurrowInTask.Moves {
        boolean night = true;
        boolean hasSealBlock = true;
        BurrowPlan.Site site = BurrowInTaskTest.site(Ground.DIGGABLE, Ground.DIGGABLE);
        BlockPos feet = SURFACE;
        final List<BlockPos> dugCells = new ArrayList<>();
        final List<BlockPos> sealedCells = new ArrayList<>();
        final List<BlockPos> climbedTo = new ArrayList<>();
        final List<String> events = new ArrayList<>();

        @Override public boolean stillNight(TickContext context) { return night; }

        @Override public BlockPos feet(TickContext context) { return feet; }

        @Override public boolean onGround(TickContext context) { return true; }

        @Override public BurrowPlan.Site site(TickContext context, BlockPos at) { return site; }

        @Override public BlockBreaking digging() {
            return new BlockBreaking() {
                private BlockPos cell;

                @Override public void aimAt(BlockPos target) { cell = target; }

                @Override public ActionStatus tick(TickContext context) {
                    dugCells.add(cell);
                    // 挖开脚下那格就落进去；挖开头顶的坑口不动。
                    if (cell.getY() < feet.getY()) feet = cell;
                    return ActionStatus.done();
                }

                @Override public String describe() { return "挖一格"; }
            };
        }

        @Override public Optional<Action> sealing(BlockPos cell) {
            if (!hasSealBlock) return Optional.empty();
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    sealedCells.add(cell);
                    return ActionStatus.done();
                }

                @Override public String describe() { return "封口"; }
            });
        }

        @Override public Action climbingTo(BlockPos surface) {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    climbedTo.add(surface);
                    feet = surface;
                    return ActionStatus.done();
                }

                @Override public String describe() { return "爬回地面"; }
            };
        }
    }
}
