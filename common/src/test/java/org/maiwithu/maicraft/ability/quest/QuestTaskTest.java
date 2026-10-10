// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.quest;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.quest.spi.QuestBookOperations;
import org.maiwithu.maicraft.ability.quest.spi.QuestBookStatus;
import org.maiwithu.maicraft.ability.quest.spi.QuestView;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务书动作任务：只发一次，按任务书与背包的变化如实收场，等不到反应就按没能确认交代。 */
class QuestTaskTest {

    private static final String QUEST = "1234567890ABCDEF";
    private static final String REQUIREMENT = "0102030405060708";
    private static final String REWARD = "1122334455667788";
    private static final String IRON = "minecraft:iron_ingot";

    /** 任务书操作替身：条目此刻的样子由测试换，发送只记次数。 */
    private static final class ScriptedBook implements QuestBookOperations {
        Optional<QuestView> view = Optional.empty();
        boolean sendResult = true;
        int sends;

        @Override public QuestBookStatus status() {
            return QuestBookStatus.ready();
        }

        @Override public Optional<QuestView> quest(String questId) {
            return view;
        }

        @Override public boolean submit(String questId, String requirementId) {
            sends++;
            return sendResult;
        }

        @Override public boolean confirm(String questId, String requirementId) {
            sends++;
            return sendResult;
        }

        @Override public boolean claim(String questId, String rewardId, String choice) {
            sends++;
            return sendResult;
        }
    }

    /** 背包替身：读测试摆在列表里的堆。 */
    private record StubBackpack(List<BackpackStack> stacks) implements BackpackView {
        @Override public int usedSlots() {
            return stacks.size();
        }

        @Override public int totalSlots() {
            return 36;
        }
    }

    /** 角色替身：交互机会开不开放、背包里有什么，都由测试摆。 */
    private static final class ScriptedPlayer implements PlayerContext {
        boolean canInteract = true;
        final List<BackpackStack> stacks = new ArrayList<>();

        void setItems(String itemId, int count) {
            stacks.removeIf(stack -> stack.itemId().equals(itemId));
            if (count > 0) {
                stacks.add(new BackpackStack(itemId, count, 64, false, false, false, false));
            }
        }

        @Override public BackpackView backpack() {
            return new StubBackpack(List.copyOf(stacks));
        }

        @Override public LocalPlayer localPlayer() {
            return null;
        }

        @Override public ClientLevel level() {
            return null;
        }

        @Override public ClientPacketListener connection() {
            return null;
        }

        @Override public PlayerInput input() {
            throw new UnsupportedOperationException("任务书测试不碰输入");
        }

        @Override public InteractionSender interactionSender() {
            throw new UnsupportedOperationException("任务书测试不碰交互提交");
        }

        @Override public MenuActions menuActions() {
            throw new UnsupportedOperationException("任务书测试不碰界面操作");
        }

        @Override public long clientTick() {
            return 0;
        }

        @Override public boolean isCurrent() {
            return true;
        }

        @Override public boolean canInteractThisTick() {
            return canInteract;
        }

        @Override public boolean tryClaimInteraction() {
            return canInteract;
        }
    }

    /** 本刻上下文替身：刻号由测试推进。 */
    private static final class TestTick implements TickContext {
        private long gameTick = 1000;
        private final ScriptedPlayer player;

        TestTick(ScriptedPlayer player) {
            this.player = player;
        }

        @Override public long gameTick() {
            return gameTick;
        }

        @Override public PlayerContext player() {
            return player;
        }

        void advance() {
            gameTick++;
        }
    }

    /**
     * 推进一个发送刻（基线在这一刻记下），然后放变化进来，推到出结果为止。
     * 发送那刻就结束的（发了发不出去）直接返回那个结果。
     */
    private static TaskResult sendThen(QuestTask task, TestTick tick, Runnable changeArrives) {
        task.start(tick);
        TickResult first = task.tick(tick);
        if (first instanceof TickResult.Finished finished) {
            return finished.result();
        }
        tick.advance();
        changeArrives.run();
        for (int i = 0; i < 300; i++) {
            TickResult result = task.tick(tick);
            if (result instanceof TickResult.Finished finished) {
                return finished.result();
            }
            tick.advance();
        }
        throw new AssertionError("任务书动作没有按时收场");
    }

    /** 一条交 8 块铁锭的要求；完成没有、还差几件由调用方定。 */
    private static QuestView ironQuest(boolean completed, long remaining) {
        return new QuestView(QUEST, "铁的基础", true, "",
                List.of(new QuestView.Requirement(REQUIREMENT, "item", completed, true, false,
                        List.of(IRON), remaining, "把要交的物品交上去")),
                List.of(), List.of());
    }

    private static int counted(TaskResult result, Change.Kind kind, String what) {
        return result.changes().stream()
                .filter(change -> change.kind() == kind && change.what().equals(what))
                .mapToInt(Change::count).sum();
    }

    @Test
    void waitsForTheInteractionOpportunityAndSendsOnce() {
        ScriptedBook book = new ScriptedBook();
        ScriptedPlayer player = new ScriptedPlayer();
        player.canInteract = false;
        book.view = Optional.of(ironQuest(false, 8));
        TestTick tick = new TestTick(player);
        QuestTask task = new QuestTask(
                new QuestInput(QuestInput.Operation.SUBMIT, QUEST, REQUIREMENT, null, null), book);

        // 没有交互机会的几刻里等，不发包。
        task.start(tick);
        for (int i = 0; i < 10; i++) {
            task.tick(tick);
            tick.advance();
        }
        assertEquals(0, book.sends, "没有交互机会不该发包");

        player.canInteract = true;
        TaskResult result = sendThen(task, tick, () -> { });
        assertEquals(1, book.sends, "再没反应也只发一次，不连点");
        assertEquals(TaskResult.Status.PARTIAL, result.status());
        assertEquals(1, result.unconfirmed().size(), "发出后没动静，按没能确认交代");
        assertTrue(result.summary().contains("没能确认"), result.summary());
    }

    @Test
    void consumedItemsAndProgressEndDone() {
        ScriptedBook book = new ScriptedBook();
        ScriptedPlayer player = new ScriptedPlayer();
        player.setItems(IRON, 12);
        book.view = Optional.of(ironQuest(false, 8));
        TestTick tick = new TestTick(player);
        QuestTask task = new QuestTask(
                new QuestInput(QuestInput.Operation.SUBMIT, QUEST, REQUIREMENT, null, null), book);

        // 发出的下一刻：背包少了 8 块铁，任务书里那条要求完成。
        TaskResult result = sendThen(task, tick, () -> {
            player.setItems(IRON, 4);
            book.view = Optional.of(ironQuest(true, 0));
        });

        assertEquals(TaskResult.Status.DONE, result.status(), result.toString());
        assertEquals(8, counted(result, Change.Kind.ITEM_CONSUMED, IRON));
        assertTrue(result.unconfirmed().isEmpty());
        assertTrue(result.summary().contains("交上"), result.summary());
        QuestDetails details = assertInstanceOf(QuestDetails.class, result.details());
        assertTrue(details.sent());
        assertTrue(details.entry().contains("铁的基础"), details.entry());
        assertTrue(details.entry().contains("→"), "条目写前后对照：" + details.entry());
    }

    @Test
    void silenceEndsPartialWithUnconfirmedAndNeverResends() {
        ScriptedBook book = new ScriptedBook();
        ScriptedPlayer player = new ScriptedPlayer();
        player.setItems(IRON, 12);
        book.view = Optional.of(ironQuest(false, 8));
        TaskResult result = sendThen(new QuestTask(
                new QuestInput(QuestInput.Operation.SUBMIT, QUEST, REQUIREMENT, null, null), book),
                new TestTick(player), () -> { });

        assertEquals(1, book.sends);
        assertEquals(TaskResult.Status.PARTIAL, result.status());
        assertEquals(1, result.unconfirmed().size());
        assertTrue(result.unconfirmed().get(0).note().contains("没有反应"), result.unconfirmed().toString());
    }

    @Test
    void backpackMovedWithoutBookEndsPartialWaitingForSync() {
        ScriptedBook book = new ScriptedBook();
        ScriptedPlayer player = new ScriptedPlayer();
        player.setItems(IRON, 12);
        book.view = Optional.of(ironQuest(false, 8));
        TestTick tick = new TestTick(player);
        QuestTask task = new QuestTask(
                new QuestInput(QuestInput.Operation.SUBMIT, QUEST, REQUIREMENT, null, null), book);

        // 背包少了铁，但任务书里那条要求没有任何变化：按同步晚到如实说。
        TaskResult result = sendThen(task, tick, () -> player.setItems(IRON, 4));

        assertEquals(TaskResult.Status.PARTIAL, result.status());
        assertEquals(8, counted(result, Change.Kind.ITEM_CONSUMED, IRON), "背包的净变化照实写");
        assertTrue(result.remaining().stream().anyMatch(part -> part.contains("同步")), result.remaining().toString());
        assertTrue(result.summary().contains("没动静"), result.summary());
    }

    @Test
    void claimWithGainedItemsAndClaimedFlagEndsDone() {
        ScriptedBook book = new ScriptedBook();
        ScriptedPlayer player = new ScriptedPlayer();
        book.view = Optional.of(new QuestView(QUEST, "有奖的", true, "", List.of(),
                List.of(new QuestView.Reward(REWARD, false, true, List.of())), List.of()));
        TestTick tick = new TestTick(player);
        QuestTask task = new QuestTask(
                new QuestInput(QuestInput.Operation.CLAIM, QUEST, null, REWARD, null), book);

        TaskResult result = sendThen(task, tick, () -> {
            player.setItems("minecraft:diamond", 3);
            book.view = Optional.of(new QuestView(QUEST, "有奖的", true, "", List.of(),
                    List.of(new QuestView.Reward(REWARD, true, false, List.of())), List.of()));
        });

        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(3, counted(result, Change.Kind.ITEM_GAINED, "minecraft:diamond"));
        assertTrue(result.summary().contains("领"), result.summary());
    }

    @Test
    void sendRefusedEndsFailedWithoutWaiting() {
        ScriptedBook book = new ScriptedBook();
        book.sendResult = false;
        ScriptedPlayer player = new ScriptedPlayer();
        TaskResult result = sendThen(new QuestTask(
                new QuestInput(QuestInput.Operation.CONFIRM, QUEST, REQUIREMENT, null, null), book),
                new TestTick(player), () -> { });

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertTrue(result.unconfirmed().isEmpty(), "没发出去就不存在没能确认的交互");
        assertTrue(result.summary().contains("没有发出去"), result.summary());
    }

    @Test
    void stoppingIsUnsafeWhileWaitingForTheReply() {
        ScriptedBook book = new ScriptedBook();
        ScriptedPlayer player = new ScriptedPlayer();
        book.view = Optional.of(ironQuest(false, 8));
        TestTick tick = new TestTick(player);
        QuestTask task = new QuestTask(
                new QuestInput(QuestInput.Operation.SUBMIT, QUEST, REQUIREMENT, null, null), book);

        task.start(tick);
        assertEquals(Interruptibility.BETWEEN_ACTIONS, task.interruptibility(tick), "还没发出，两个动作之间可以插");
        task.tick(tick);
        assertEquals(Interruptibility.UNSAFE_TO_STOP, task.interruptibility(tick), "发出后到收尾之间停不下来");
    }
}
