// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.obtain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.ItemAcquisition;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.StartsAcquisition;
import org.maiwithu.maicraft.behavior.acquire.WantedItem;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.CloseReason;

/**
 * 拿东西的任务：开始时已够直接完成；引擎做完按实际入包结算，拿到一部分是 partial 加还差几件；
 * 实际拿到东西的途径进结果细节。引擎与背包都是替身，不碰游戏。
 */
class ObtainTaskTest {

    /** 替身：背包就是一张可以改的格子表。 */
    static final class FakeBackpack implements BackpackView {
        final List<BackpackStack> stacks = new ArrayList<>();

        @Override public List<BackpackStack> stacks() {
            return List.copyOf(stacks);
        }

        @Override public int usedSlots() {
            return stacks.size();
        }

        @Override public int totalSlots() {
            return 36;
        }

        void add(String itemId, int count) {
            stacks.add(new BackpackStack(itemId, count, 64, false, false, false, false));
        }
    }

    /** 替身：发起拿东西时记下请求与限定，推进时按剧本收场。 */
    static final class ScriptedAcquisition implements StartsAcquisition {
        ItemRequest lastRequest;
        ItemAcquisition.Scope lastScope;
        Consumer<String> lastDelivered;
        /** 每推进一步做的事：往背包放几件货、报哪条途径；null 表示以问题失败。 */
        Integer deliverPerTick;
        String deliveredRoute;
        Problem failure;

        @Override public Action need(ItemRequest request, Permissions permissions,
                ItemAcquisition.Scope scope, Consumer<String> onDelivered) {
            lastRequest = request;
            lastScope = scope;
            lastDelivered = onDelivered;
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    // 先把这次动作能拿到的东西放进背包（真实引擎里来源做完才重新清点），
                    // 剧本设了失败就接着以问题收场——对应"做了一个来源之后还是不够"。
                    if (deliverPerTick != null) {
                        ((FakeBackpack) backpack).add(deliveredRouteItem(), deliverPerTick);
                        if (lastDelivered != null) {
                            lastDelivered.accept(deliveredRoute);
                        }
                    }
                    return failure != null ? ActionStatus.failed(failure) : ActionStatus.done();
                }

                @Override public String describe() {
                    return "按剧本拿货";
                }
            };
        }

        BackpackView backpack;
        String deliveredRouteItem = "minecraft:torch";
        String deliveredRouteItem() {
            return deliveredRouteItem;
        }
    }

    private static TickContext tick(long gameTick) {
        return new TickContext() {
            @Override public long gameTick() {
                return gameTick;
            }

            @Override public PlayerContext player() {
                return null;
            }
        };
    }

    private static ObtainItems input(int count, ItemAcquisition.Scope scope) {
        return new ObtainItems(new WantedItem("minecraft:torch"), count, scope,
                Permissions.DEFAULT, "按需要拿取", "再拿 " + count + " 个 minecraft:torch");
    }

    @Test
    void 开始时已够直接完成() {
        FakeBackpack backpack = new FakeBackpack();
        backpack.add("minecraft:torch", 8);
        ObtainTask task = new ObtainTask(input(8, ItemAcquisition.Scope.ALL),
                new ScriptedAcquisition(), backpack, null, itemId -> Set.of());
        task.start(tick(0));
        TaskResult finished = runTask(task);
        assertEquals(TaskResult.Status.DONE, finished.status());
        assertTrue(finished.summary().contains("开始时身上已够"));
        assertEquals(0, finished.changes().size(), "开始时已够不该记入包变化");
    }

    @Test
    void 引擎拿到货结算完成并记途径() {
        FakeBackpack backpack = new FakeBackpack();
        ScriptedAcquisition acquisition = new ScriptedAcquisition();
        acquisition.backpack = backpack;
        acquisition.deliverPerTick = 8;
        acquisition.deliveredRoute = "craft";
        ObtainTask task = new ObtainTask(input(8, ItemAcquisition.Scope.ALL),
                acquisition, backpack, null, itemId -> Set.of());
        task.start(tick(0));
        TaskResult finished = runTask(task);
        assertEquals(TaskResult.Status.DONE, finished.status());
        assertEquals(1, finished.changes().size());
        Change gained = finished.changes().get(0);
        assertEquals(Change.Kind.ITEM_GAINED, gained.kind());
        assertEquals("minecraft:torch", gained.what());
        assertEquals(8, gained.count());
        ObtainedVia via = (ObtainedVia) finished.details();
        assertEquals(List.of("craft"), via.routes());
    }

    @Test
    void 引擎认输时拿到一部分是partial() {
        FakeBackpack backpack = new FakeBackpack();
        backpack.add("minecraft:torch", 3);
        ScriptedAcquisition acquisition = new ScriptedAcquisition();
        acquisition.backpack = backpack;
        acquisition.deliverPerTick = 2;
        acquisition.deliveredRoute = "container";
        acquisition.failure = Problem.of(Problem.Kind.NEED_ITEM, "附近没有会掉出火把的方块了");
        ObtainTask task = new ObtainTask(input(8, ItemAcquisition.Scope.ALL),
                acquisition, backpack, null, itemId -> Set.of());
        task.start(tick(0));
        TaskResult finished = runTask(task);
        assertEquals(TaskResult.Status.PARTIAL, finished.status());
        assertEquals(Problem.Kind.NEED_ITEM, finished.problem().kind());
        assertEquals(List.of("还差 3 个" + new WantedItem("minecraft:torch").describe()),
                finished.remaining());
        assertEquals(2, finished.changes().get(0).count());
    }

    @Test
    void 引擎认输且一件没拿到是失败() {
        FakeBackpack backpack = new FakeBackpack();
        ScriptedAcquisition acquisition = new ScriptedAcquisition();
        acquisition.backpack = backpack;
        acquisition.failure = Problem.of(Problem.Kind.NEED_ITEM, "问过各来源都拿不到");
        ObtainTask task = new ObtainTask(input(4, ItemAcquisition.Scope.ALL),
                acquisition, backpack, null, itemId -> Set.of());
        task.start(tick(0));
        TaskResult finished = runTask(task);
        assertEquals(TaskResult.Status.FAILED, finished.status());
        assertEquals(0, finished.changes().size());
    }

    @Test
    void via与半径原样交给引擎() {
        FakeBackpack backpack = new FakeBackpack();
        ScriptedAcquisition acquisition = new ScriptedAcquisition();
        acquisition.backpack = backpack;
        acquisition.deliverPerTick = 1;
        acquisition.deliveredRoute = "mine";
        ItemAcquisition.Scope scope = new ItemAcquisition.Scope(Set.of("mine"), 30.0, 12);
        ObtainTask task = new ObtainTask(input(1, scope), acquisition, backpack, null, itemId -> Set.of());
        task.start(tick(0));
        runTask(task);
        assertEquals(Set.of("mine"), acquisition.lastScope.routes());
        assertEquals(Double.valueOf(30), acquisition.lastScope.maxDistanceBlocks());
        assertEquals(Integer.valueOf(12), acquisition.lastScope.radiusBlocks());
        assertEquals(1, acquisition.lastRequest.count());
    }

    private TaskResult runTask(ObtainTask task) {
        TickResult result = TickResult.RUNNING;
        for (int i = 1; i <= 100; i++) {
            result = task.tick(tick(i));
            if (result instanceof TickResult.Finished finished) {
                task.close(CloseReasonForTest.REASON);
                return finished.result();
            }
        }
        throw new AssertionError("任务一百刻都没跑完");
    }

    /** 收尾理由占位：测试只关心 tick 出的结果。 */
    private static final class CloseReasonForTest {
        static final CloseReason REASON =
                CloseReason.FINISHED;
    }
}
