// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 近身走位环：离远了凑、太近了退、环里站住；够得着就挥，挥和迈步同一刻都做；撤离时只退不凑，被追上照样还手。 */
class CloseCombatTest {

    private record Tick(long number) implements TickContext {
        @Override public long gameTick() { return number; }
        @Override public PlayerContext player() { return null; }
    }

    private static final class Moves implements CloseCombat.Moves {
        final List<String> log = new ArrayList<>();

        @Override public Action strike(TickContext context, int entityId) {
            return new Action() {
                @Override public ActionStatus tick(TickContext tick) {
                    log.add("挥");
                    return ActionStatus.done();
                }

                @Override public String describe() { return "替身出手"; }
            };
        }

        @Override public void step(TickContext context, int entityId, float forward) {
            log.add(forward > 0 ? "凑" : forward < 0 ? "退" : "站");
        }
    }

    @Test
    void theRingApproachesBacksOffOrHolds() {
        assertEquals(1f, CloseCombat.forward(4.0, false));
        assertEquals(-1f, CloseCombat.forward(1.5, false));
        assertEquals(0f, CloseCombat.forward(2.5, false));
        assertEquals(-1f, CloseCombat.forward(2.5, true));
    }

    @Test
    void strikingAndSteppingHappenInTheSameTick() {
        Moves moves = new Moves();
        CloseCombat combat = new CloseCombat();
        // 贴得太近（1.5 格）：这一刻既挥又往后退。
        assertTrue(combat.tick(new Tick(1), moves, 7, 1.5, 3.0, false));
        assertEquals(List.of("挥", "退"), moves.log);
        // 离得远（4 格）够不着：只往前凑，不挥空拳。
        moves.log.clear();
        combat.tick(new Tick(2), moves, 7, 4.0, 3.0, false);
        assertEquals(List.of("凑"), moves.log);
        // 撤离时被追上（2 格）：边退边还手。
        moves.log.clear();
        combat.tick(new Tick(3), moves, 7, 2.0, 3.0, true);
        assertEquals(List.of("挥", "退"), moves.log);
    }
}
