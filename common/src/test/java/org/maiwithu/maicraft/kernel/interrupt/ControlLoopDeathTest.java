// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.interrupt;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.player.DeathFacts;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoopTest.FakeNeed;
import org.maiwithu.maicraft.kernel.interrupt.ControlLoopTest.FakeTask;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色死亡时控制循环停摆：不再插生存需求的临时任务、不推进任务，插着的临时任务按"角色没了"收尾，
 * 只发一条死亡事件；重生后（同一个上下文替身改回活着）循环照常推进。
 */
class ControlLoopDeathTest {

    @Test
    void deadCharacterHaltsTheLoopWithOneDeathEvent() {
        // 被僵尸磨死在死亡界面：饥饿还急也不能再插吃饭任务，事件只报一条，不刷屏。
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        FakeNeed hunger = FakeNeed.always("饥饿", Urgency.NOW);
        RecordingEvents events = new RecordingEvents();
        ControlLoop loop = new ControlLoop(List.of(hunger), events);
        loop.setMainTask(main);

        DyingPlayer body = new DyingPlayer();
        body.dead = true;

        assertInstanceOf(ControlLoop.Decision.WaitingRespawn.class, loop.tick(new Tick(body)));
        assertEquals(0, hunger.created, "死了不再插生存需求的临时任务");
        assertEquals(0, main.ticks, "死了不推进主任务");
        assertEquals(1, events.published.size(), "死亡只报一条事件");
        assertTrue(events.published.get(0).startsWith(TaskEvent.Kind.CHARACTER_DIED.name()),
                "发的是角色死亡事件：" + events.published.get(0));

        // 继续死着：不重复报，也不偷偷恢复推进。
        assertInstanceOf(ControlLoop.Decision.WaitingRespawn.class, loop.tick(new Tick(body)));
        assertEquals(1, events.published.size());
        assertEquals(0, hunger.created);
        assertEquals(0, main.ticks);
    }

    @Test
    void tempTasksEndButMainTaskSurvivesTheDeath() {
        // 死亡时插着的临时任务按"角色没了"收尾（已经发生的消耗留在它的结果里），
        // 主任务不收尾，只停手；重生后从原地接着做。
        FakeTask main = new FakeTask("挖矿", Interruptibility.WORKING);
        // 重生后处境不再急（脚本第二次回答 null），轮到的就是主任务。
        FakeNeed breath = FakeNeed.of("换气", Urgency.NOW, null);
        ControlLoop loop = new ControlLoop(List.of(breath), new RecordingEvents());
        loop.setMainTask(main);

        DyingPlayer body = new DyingPlayer();
        loop.tick(new Tick(body));
        FakeTask temp = (FakeTask) loop.currentTask();
        assertFalse(temp.closed, "前置：临时任务已经插进来在推进");

        body.dead = true;
        assertInstanceOf(ControlLoop.Decision.WaitingRespawn.class, loop.tick(new Tick(body)));
        assertTrue(temp.closed, "临时任务按角色没了收尾");
        assertEquals(CloseReason.PLAYER_GONE, temp.closeReason);
        assertFalse(main.closed, "主任务不收尾，等重生接着做");
        assertEquals(2, main.pauses, "插临时任务时停过一次，死亡停摆时再停一次");

        // 重生：同一个身体活了，循环恢复，轮到主任务。
        body.dead = false;
        ControlLoop.Decision.Advanced advanced = assertInstanceOf(
                ControlLoop.Decision.Advanced.class, loop.tick(new Tick(body)));
        assertSame(main, advanced.task());
        assertNull(advanced.interrupting());
    }

    @Test
    void deathHandsFactsToTheDecisionHostOnce() {
        // 停摆的第一刻把死亡事实与连接状态交给挂决策的一方，之后每刻停摆不再重复叫。
        RecordingDeathDecisions decisions = new RecordingDeathDecisions();
        DyingPlayer body = new DyingPlayer();
        ControlLoop loop = new ControlLoop(List.of(), new RecordingEvents(), decisions);
        loop.setMainTask(new FakeTask("挖矿", Interruptibility.WORKING));

        body.dead = true;
        assertInstanceOf(ControlLoop.Decision.WaitingRespawn.class, loop.tick(new Tick(body)));
        loop.tick(new Tick(body));
        assertEquals(1, decisions.deaths, "同一个死亡过程只叫一次");
        assertEquals(12, decisions.facts.score(), "死亡事实原样交给挂决策的一方");
        assertFalse(decisions.connectionAlive, "替身没有连接，如实交给挂决策的一方");
        assertEquals(0, decisions.aliveAgain, "还没活过来不算了结");

        // 重生：本轮死亡决策了结，下次死亡再挂新的。
        body.dead = false;
        loop.tick(new Tick(body));
        assertEquals(1, decisions.aliveAgain);
    }

    @Test
    void deathWithoutAHostStillHaltsTheLoop() {
        // 没接挂决策的一方：死亡照常停摆，不挂问题也不出错。
        DyingPlayer body = new DyingPlayer();
        ControlLoop loop = new ControlLoop(List.of(), new RecordingEvents());
        body.dead = true;
        assertInstanceOf(ControlLoop.Decision.WaitingRespawn.class, loop.tick(new Tick(body)));
    }

    /** 记下死亡决策挂载口被叫了几次：断言一个死亡过程只挂一次、活过来才了结。 */
    private static final class RecordingDeathDecisions implements DeathDecisionHost {
        int deaths;
        int aliveAgain;
        DeathFacts facts;
        boolean connectionAlive;

        @Override public void characterDied(DeathFacts deathFacts, boolean aliveConnection) {
            deaths++;
            facts = deathFacts;
            connectionAlive = aliveConnection;
        }

        @Override public void characterAliveAgain() {
            aliveAgain++;
        }
    }

    /** 只回答"死没死"的角色上下文替身；其他入口这些测试用不到，如实报缺。 */
    private static final class DyingPlayer implements PlayerContext {
        boolean dead;
        DeathFacts facts = new DeathFacts(12, "minecraft:overworld", 1, 64, 2);

        @Override public LocalPlayer localPlayer() { return null; }
        @Override public ClientLevel level() { return null; }
        @Override public ClientPacketListener connection() { return null; }
        @Override public PlayerInput input() {
            throw new UnsupportedOperationException("死亡测试用不到输入入口");
        }
        @Override public InteractionSender interactionSender() {
            throw new UnsupportedOperationException("死亡测试用不到交互提交入口");
        }
        @Override public MenuActions menuActions() {
            throw new UnsupportedOperationException("死亡测试用不到容器界面入口");
        }
        @Override public long clientTick() { return 0; }
        @Override public boolean isCurrent() { return true; }
        @Override public boolean canInteractThisTick() { return false; }
        @Override public boolean tryClaimInteraction() { return false; }
        @Override public boolean isDeadOrDying() { return dead; }
        @Override public DeathFacts deathFacts() { return facts; }
    }

    /** 本刻上下文替身：把身体递给循环。 */
    private record Tick(DyingPlayer body) implements TickContext {
        @Override public long gameTick() { return 0; }
        @Override public PlayerContext player() { return body; }
    }

    /** 记下发过的事件：断言死亡只发一条。 */
    private static final class RecordingEvents implements TaskEventSink {
        final List<String> published = new ArrayList<>();

        @Override public void publish(TaskEvent.Kind kind, String message) {
            published.add(kind + ":" + message);
        }
    }
}
