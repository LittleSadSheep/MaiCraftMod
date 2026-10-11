// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Standing;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 钓鱼能力的入口场景：给了目标对象就是参数错误；不给 count 建常驻任务；
 * 缺竿走拿到物品的引擎，弄不到以 NEED_ITEM 结束。
 */
class FishModuleTest {

    private static final String ROD = "minecraft:fishing_rod";

    /** 背包替身：一张可以改的格子表。 */
    static final class FakeBackpack implements BackpackView {
        final List<BackpackStack> stacks = new ArrayList<>();

        void add(String itemId, int count) {
            stacks.add(new BackpackStack(itemId, count, 64, false, false, false, false));
        }

        @Override public List<BackpackStack> stacks() { return List.copyOf(stacks); }
        @Override public int usedSlots() { return stacks.size(); }
        @Override public int totalSlots() { return 36; }
    }

    /** 角色上下文替身：只有背包与鱼钩事实，不碰真实客户端。 */
    static final class FakeContext implements PlayerContext {
        final FakeBackpack backpack = new FakeBackpack();
        @Override public LocalPlayer localPlayer() { return null; }
        @Override public ClientLevel level() { return null; }
        @Override public ClientPacketListener connection() { return null; }
        @Override public PlayerInput input() { return null; }
        @Override public InteractionSender interactionSender() { return null; }
        @Override public MenuActions menuActions() { return null; }
        @Override public BackpackView backpack() { return backpack; }
        @Override public long clientTick() { return 0; }
        @Override public boolean isCurrent() { return true; }
        @Override public boolean canInteractThisTick() { return true; }
        @Override public boolean tryClaimInteraction() { return true; }
    }

    /** 一直说"没有角色"的本刻上下文：入口判断用。 */
    private static final TickContext NO_PLAYER = new TickContext() {
        @Override public long gameTick() { return 0; }
        @Override public PlayerContext player() { return new FakeContext(); }
    };

    /** 步骤上下文替身：只带目标与参数。 */
    private static StepContext stepOf(Target target, ParamValues params) {
        return new StepContext() {
            @Override public Goal goal() { return Goal.of("maicraft:fish", target, params); }
            @Override public int stepIndex() { return 0; }
            @Override public TickContext tick() { return NO_PLAYER; }
        };
    }

    @Test
    void 给了地点目标按参数错误结束不悄悄从当前钓() {
        FishModule module = new FishModule(() -> new FakeContext(),
                null, null, null, null, null, null);
        var decision = module.decide(stepOf(new Target.Position(100, 64, 200, null), ParamValues.EMPTY));
        assertInstanceOf(StepDecision.Finish.class, decision);
        TaskResult result = ((StepDecision.Finish) decision).result();
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.INVALID_PARAMETER, result.problem().kind());
        assertTrue(result.problem().message().contains("travel"));
    }

    @Test
    void 不给count建常驻任务给count建普通任务() {
        FishModule module = new FishModule(() -> new FakeContext(),
                null, null, null, null, null, null);
        TaskFactories factories = new TaskFactories();
        module.registerTasks(factories);
        Task standing = factories.create(new FishInput(0, 0));
        assertInstanceOf(Standing.class, standing, "不给 count 的任务豁免内核的兜底时限");
        Task counted = factories.create(new FishInput(3, 0));
        assertFalse(counted instanceof Standing, "钓满 count 的任务有做完的时刻");
    }

    @Test
    void 缺竿走拿到物品的引擎弄不到以NEED_ITEM结束() {
        FakeContext context = new FakeContext();
        // 拿东西的引擎替身：竿弄不到，以 NEED_ITEM 失败。
        ItemNeeds noRod = (request, permissions) -> ScriptedItems.failing(
                Problem.of(Problem.Kind.NEED_ITEM, "弄不到鱼竿"));
        FishModule module = new FishModule(() -> context, noRod, null, null, null, null, null);
        TaskFactories factories = new TaskFactories();
        module.registerTasks(factories);
        Task task = factories.create(new FishInput(1, 0));
        task.start(NO_PLAYER);
        TaskResult result = null;
        for (int i = 0; i < 20 && result == null; i++) {
            var ticked = task.tick(NO_PLAYER);
            if (ticked instanceof TickResult.Finished finished) result = finished.result();
        }
        assertTrue(result != null, "缺竿的任务很快结束");
        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind());
    }

    /** 引擎替身用的动作：一上来就带着问题失败。 */
    static final class ScriptedItems {
        static Action failing(Problem problem) {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) { return ActionStatus.failed(problem); }
                @Override public String describe() { return "弄不到"; }
            };
        }
    }
}
