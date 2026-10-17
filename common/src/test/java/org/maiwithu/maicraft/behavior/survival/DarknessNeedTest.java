// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.lighting.FollowLighting;
import org.maiwithu.maicraft.behavior.lighting.HandlesOwnLighting;
import org.maiwithu.maicraft.behavior.lighting.TorchesAlongTheWay;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TaskProgress;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.kernel.task.Urgency;
import org.maiwithu.maicraft.kernel.result.TaskResult;

/** 太暗这项生存需求：暗处有火把找空当插一支；阈值内、缺火把、刚出过手、照明任务自己管灯时都不插。 */
class DarknessNeedTest {

    private static final BlockPos FEET = new BlockPos(10, 64, 10);

    /** 暗处处境替身：给最暗亮度、有没有火把、站在哪。 */
    private static DarknessNeed.ReadsDarkness dark(int light, boolean torches) {
        return dark(light, torches, FEET);
    }

    private static DarknessNeed.ReadsDarkness dark(int light, boolean torches, BlockPos feet) {
        // 默认在地下：头顶遮着，天空光 0。
        return context -> new DarknessNeed.DarknessFacts(OptionalInt.of(light), torches, feet, OptionalInt.of(0), false);
    }

    /** 露天的暗处：方块光 0、天空光 15（白天黑夜都是这个原始值）。 */
    private static DarknessNeed.ReadsDarkness openSky() {
        return context -> new DarknessNeed.DarknessFacts(OptionalInt.of(0), true, FEET, OptionalInt.of(15), false);
    }

    /** 密林底下：方块光 0，几层树叶把天空光压到 5，头顶只有树叶。 */
    private static DarknessNeed.ReadsDarkness underCanopy() {
        return context -> new DarknessNeed.DarknessFacts(OptionalInt.of(0), true, FEET, OptionalInt.of(5), true);
    }

    private static final DarknessNeed.TorchMoves NO_MOVES = (context, onAttempt) -> null;

    private static TickContext tick() {
        return TickContext.of(100, null);
    }

    private static Urgency urgency(DarknessNeed need, Task currentTask) {
        return need.urgency(tick(), currentTask);
    }

    @Test
    void 暗处有火把_找空当插一支() {
        FollowLighting follow = new FollowLighting();
        DarknessNeed need = new DarknessNeed(dark(3, true), NO_MOVES, follow);
        assertEquals(Urgency.LATER, urgency(need, null));
        // 走过并实测仍暗的格记进路线记录，status 查得到。
        assertEquals(1, follow.darkOnRoute().size());
        assertEquals(3, follow.darkOnRoute().getFirst().light());
    }

    @Test
    void 阈值内不插_关了不插() {
        DarknessNeed need = new DarknessNeed(dark(8, true), NO_MOVES, new FollowLighting());
        assertNull(urgency(need, null), "默认阈值 8，亮度 8 不算太暗");
        FollowLighting off = new FollowLighting();
        off.disable();
        DarknessNeed disabled = new DarknessNeed(dark(0, true), NO_MOVES, off);
        assertNull(urgency(disabled, null));
    }

    @Test
    void 阈值可以调_上限十三() {
        FollowLighting follow = new FollowLighting();
        follow.enable(13);
        DarknessNeed need = new DarknessNeed(dark(13, true), NO_MOVES, follow);
        assertNull(urgency(need, null), "亮度 13 达到阈值");
        assertEquals(13, follow.minimumLight());
    }

    @Test
    void 缺火把不插_也不报错() {
        DarknessNeed need = new DarknessNeed(dark(0, false), NO_MOVES, new FollowLighting());
        assertNull(urgency(need, null), "缺火把这次临时任务都不开始，主任务照常");
    }

    @Test
    void 露天不算太暗_白天黑夜都不插() {
        FollowLighting follow = new FollowLighting();
        DarknessNeed need = new DarknessNeed(openSky(), NO_MOVES, follow);
        assertNull(urgency(need, null), "露天的方块光处处是 0，按它算会每两格插一支");
        assertTrue(follow.darkOnRoute().isEmpty(), "露天不记暗格");
    }

    @Test
    void 林下不算太暗_天空光被树叶压低也不插() {
        FollowLighting follow = new FollowLighting();
        DarknessNeed need = new DarknessNeed(underCanopy(), NO_MOVES, follow);
        assertNull(urgency(need, new RouteTask()), "密林里赶路不当成地下每几格插一支");
        assertTrue(follow.darkOnRoute().isEmpty(), "林下不记暗格");
    }

    @Test
    void 出行探索走路途中落地站稳就插_不等走完() {
        DarknessNeed need = new DarknessNeed(dark(0, true), NO_MOVES, new FollowLighting());
        assertEquals(Urgency.SOON, urgency(need, new RouteTask()), "沿路走的任务按尽快插，停下安全时插得进来");
        assertEquals(Urgency.LATER, urgency(need, null), "其余照旧找空当");
    }

    @Test
    void 照明任务自己管灯时让位() {
        FollowLighting follow = new FollowLighting();
        DarknessNeed need = new DarknessNeed(dark(0, true), NO_MOVES, follow);
        assertNull(urgency(need, new FakeTask()), "往验收范围里插野火把只会搅局");
    }

    @Test
    void 距上次出手不足两格不再出手() {
        FollowLighting follow = new FollowLighting();
        DarknessNeed need = new DarknessNeed(dark(0, true), (context, onAttempt) -> {
            onAttempt.accept(FEET.east());
            return null;
        }, follow);
        need.createTask(tick());
        assertNull(urgency(need, null), "刚在旁边出过手（原生被拒也算），不原地撒灯");
        // 走远了：照插。
        DarknessNeed moved = new DarknessNeed(dark(0, true, new BlockPos(50, 64, 50)),
                (context, onAttempt) -> {
                    onAttempt.accept(FEET.east());
                    return null;
                }, follow);
        moved.createTask(tick());
        assertEquals(Urgency.LATER, urgency(moved, null), "离上次出手远了照插");
    }

    @Test
    void 读不到现场_这一刻不评估() {
        DarknessNeed need = new DarknessNeed(context -> null, NO_MOVES, new FollowLighting());
        assertNull(urgency(need, null));
    }

    @Test
    void 创建临时任务交给插火把的动作() {
        Task placed = new FakeTask();
        DarknessNeed need = new DarknessNeed(dark(0, true), (context, onAttempt) -> placed, new FollowLighting());
        assertSame(placed, need.createTask(tick()));
    }

    /** 沿路走的任务替身：出行、探索声明的特征接口。 */
    private static final class RouteTask implements Task, TorchesAlongTheWay {
        @Override public void start(TickContext context) { }
        @Override public TickResult tick(TickContext context) { return TickResult.RUNNING; }
        @Override public void pause() { }
        @Override public TaskResult close(CloseReason reason) { return TaskResult.cancelled("替身"); }
        @Override public Interruptibility interruptibility(TickContext context) {
            return Interruptibility.WORKING;
        }
        @Override public Optional<TaskProgress> currentProgress() { return Optional.empty(); }
        @Override public String describe() { return "沿路走的替身任务"; }
    }

    /** 什么都不做的任务替身。 */
    private static final class FakeTask implements Task, HandlesOwnLighting {
        @Override public void start(TickContext context) { }
        @Override public TickResult tick(TickContext context) { return TickResult.RUNNING; }
        @Override public void pause() { }
        @Override public TaskResult close(CloseReason reason) { return TaskResult.cancelled("替身"); }
        @Override public Interruptibility interruptibility(TickContext context) {
            return Interruptibility.BETWEEN_ACTIONS;
        }
        @Override public Optional<TaskProgress> currentProgress() { return Optional.empty(); }
        @Override public String describe() { return "替身任务"; }
    }
}
