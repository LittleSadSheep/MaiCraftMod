// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import org.maiwithu.maicraft.behavior.interaction.spi.ChainKeyHold;
import org.maiwithu.maicraft.behavior.survival.BlockBreaking;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 连锁挖一格：等连锁键准备好才挥第一下，挥下去以后一直续按，触发格确认挖掉后等副格更新到齐再松键；
 * 答只挖这一格时照常挖；挖到一半键丢了就停手松键改挖这一格；被打断先松键。
 */
class ChainedDigTest {

    private static final BlockPos TRIGGER = new BlockPos(0, 64, 0);

    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            return null;
        }
    }

    /** 替身现场：挖掉的格记在这里；连锁时触发格挖掉那一刻整批一起没。 */
    private final Set<BlockPos> gone = new HashSet<>();

    /** 替身挖掘：过了第一下之前的那道问，挥两刻等确认，第三刻触发格没了、报一次进展。 */
    private final class Digging implements BlockBreaking {
        Predicate<BlockHitResult> gate = hit -> true;
        Direction face;
        int swingTicks = -1;
        int pauses;
        List<BlockPos> alsoBreaks = List.of();

        @Override public void aimAt(BlockPos cell) {}
        @Override public void fromFace(Direction required) {
            face = required;
        }
        @Override public void beforeFirstSwing(Predicate<BlockHitResult> check) {
            gate = check;
        }
        @Override public boolean swingPending() {
            return swingTicks >= 0;
        }
        @Override public ActionStatus tick(TickContext context) {
            if (swingTicks < 0) {
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(TRIGGER), Direction.NORTH, TRIGGER, false);
                if (!gate.test(hit)) return ActionStatus.running();
                swingTicks = 0;
                return ActionStatus.running();
            }
            if (++swingTicks < 3) return ActionStatus.running();
            gone.add(TRIGGER);
            gone.addAll(alsoBreaks);
            swingTicks = -1;
            return ActionStatus.progressed();
        }
        @Override public void pause() {
            pauses++;
            swingTicks = -1;
        }
        @Override public String describe() {
            return "替身挖";
        }
    }

    /** 替身连锁键：按排好的答复回答第一下之前的问；记下续按、松键各几次。 */
    private static final class Hold implements ChainKeyHold {
        final Deque<Readiness> answers = new ArrayDeque<>();
        boolean keepHolding = true;
        int keeps;
        int releases;

        @Override public Readiness ready(PlayerContext context, BlockHitResult hit) {
            return answers.size() > 1 ? answers.poll() : answers.peek();
        }
        @Override public boolean keep(PlayerContext context) {
            keeps++;
            return keepHolding;
        }
        @Override public boolean release(PlayerContext context) {
            releases++;
            return true;
        }
    }

    private record Accelerator(Hold hold) implements BreakAccelerator {
        @Override public Optional<ChainKeyHold> hold(ChainShape shape, Predicate<BlockPos> mayBreak) {
            return Optional.ofNullable(hold);
        }
    }

    private ChainedDig dig(Digging digging, Hold hold) {
        return new ChainedDig(TRIGGER, Direction.SOUTH, BreakAccelerator.ChainShape.CONNECTED, pos -> true,
                List.of(new Accelerator(hold)), digging, (context, cell) -> gone.contains(cell));
    }

    private List<ActionStatus> run(ChainedDig action, int ticks) {
        List<ActionStatus> statuses = new ArrayList<>();
        for (int tick = 1; tick <= ticks; tick++) {
            ActionStatus status = action.tick(new Tick(tick));
            statuses.add(status);
            if (status instanceof ActionStatus.Done || status instanceof ActionStatus.Failed) break;
        }
        return statuses;
    }

    @Test
    void 等连锁键准备好才挥_挖掉后等副格到齐再松键() {
        Digging digging = new Digging();
        Hold hold = new Hold();
        hold.answers.add(new ChainKeyHold.Readiness.Wait("键刚按下"));
        hold.answers.add(new ChainKeyHold.Readiness.Wait("等预览"));
        hold.answers.add(new ChainKeyHold.Readiness.Chain(List.of(TRIGGER, TRIGGER.east(), TRIGGER.east(2))));
        digging.alsoBreaks = List.of(TRIGGER.east(), TRIGGER.east(2));
        ChainedDig action = dig(digging, hold);
        List<ActionStatus> statuses = run(action, 100);
        assertInstanceOf(ActionStatus.Done.class, statuses.getLast());
        assertEquals(Direction.SOUTH, digging.face, "先从要求的那一面挖");
        assertTrue(hold.keeps >= 2, "挥下去以后每刻续按");
        assertEquals(1, hold.releases, "做完松一次键");
        assertTrue(gone.containsAll(List.of(TRIGGER, TRIGGER.east(), TRIGGER.east(2))));
    }

    @Test
    void 副格没到齐也只等一秒就松键() {
        Digging digging = new Digging();
        Hold hold = new Hold();
        hold.answers.add(new ChainKeyHold.Readiness.Chain(List.of(TRIGGER, TRIGGER.east())));
        ChainedDig action = dig(digging, hold);
        List<ActionStatus> statuses = run(action, 200);
        assertInstanceOf(ActionStatus.Done.class, statuses.getLast());
        assertTrue(statuses.size() <= 4 + ChainedDig.SETTLE_TICKS + 2, "副格没挖掉也不一直按着：" + statuses.size());
        assertEquals(1, hold.releases);
    }

    @Test
    void 答只挖这一格时照常挖_描述写原因() {
        Digging digging = new Digging();
        Hold hold = new Hold();
        hold.answers.add(new ChainKeyHold.Readiness.Single("选区里有别人放的方块"));
        ChainedDig action = dig(digging, hold);
        List<ActionStatus> statuses = run(action, 50);
        assertInstanceOf(ActionStatus.Done.class, statuses.getLast());
        assertEquals(0, hold.keeps, "不连锁就不续按");
        assertEquals(0, hold.releases, "答只挖这一格时连锁键已经松好了");
        assertTrue(action.describe().contains("选区里有别人放的方块"), action.describe());
    }

    @Test
    void 没装连锁挖就普通地挖() {
        Digging digging = new Digging();
        ChainedDig action = dig(digging, null);
        List<ActionStatus> statuses = run(action, 50);
        assertInstanceOf(ActionStatus.Done.class, statuses.getLast());
        assertTrue(gone.contains(TRIGGER));
    }

    @Test
    void 挖到一半键丢了_停手松键改挖这一格() {
        Digging digging = new Digging();
        Hold hold = new Hold();
        hold.answers.add(new ChainKeyHold.Readiness.Chain(List.of(TRIGGER, TRIGGER.east())));
        hold.keepHolding = false;
        digging.alsoBreaks = List.of();
        ChainedDig action = dig(digging, hold);
        List<ActionStatus> statuses = run(action, 100);
        assertInstanceOf(ActionStatus.Done.class, statuses.getLast());
        assertEquals(1, digging.pauses, "键丢了先停下那一下");
        assertEquals(1, hold.releases, "松开连锁键再挖");
        assertTrue(gone.contains(TRIGGER), "触发格照样挖掉");
    }

    @Test
    void 被打断先松键_回来重新开一次() {
        Digging digging = new Digging();
        Hold hold = new Hold();
        hold.answers.add(new ChainKeyHold.Readiness.Chain(List.of(TRIGGER, TRIGGER.east())));
        ChainedDig action = dig(digging, hold);
        action.tick(new Tick(1));
        action.tick(new Tick(2));
        action.pause();
        assertEquals(1, hold.releases, "暂停时松开连锁键");
        List<ActionStatus> statuses = run(action, 100);
        assertInstanceOf(ActionStatus.Done.class, statuses.getLast());
        assertEquals(2, hold.releases, "回来重新按、做完再松");
    }
}
