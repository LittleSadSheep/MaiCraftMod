// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.PicksUpDrops;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 狩猎的执行：看得见但够不着先朝它现在的位置走，够得着才出手；
 * 附近没有猎物出去找时，一单要什么一路带着，找遍了如实说没见到会掉出它的生物。
 */
class LiveHuntsTest {

    private static final ReadsCreatureSituation WILD = uuid -> Optional.of(
            new ReadsCreatureSituation.CreatureSituation(false, false, false, false, false, false));

    private static final TickContext TICK = new TickContext() {
        @Override public long gameTick() {
            return 0;
        }

        @Override public PlayerContext player() {
            return null;
        }
    };

    private static ItemRequest wool() {
        return new ItemRequest(WantedItem.ofItem("minecraft:white_wool"), 1, "按需要拿取");
    }

    /** 出手与走位的替身：记下每次是走还是打；够不够得着由测试摆。 */
    private static final class FakeMoves implements HuntMoves {
        final List<String> done = new ArrayList<>();
        boolean inReach;
        double[] targetAt = {12, 64, 0};

        @Override public Action strike(TickContext context, int entityId) {
            done.add("打");
            return instant();
        }

        @Override public boolean inReach(TickContext context, int entityId) {
            return inReach;
        }

        @Override public Optional<double[]> positionOf(TickContext context, int entityId) {
            return Optional.of(targetAt);
        }

        @Override public Action walkTo(double x, double y, double z) {
            done.add("走到 " + (int) x);
            return instant();
        }

        @Override public double[] selfPosition(TickContext context) {
            return new double[] {0, 64, 0};
        }

        private static Action instant() {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "替身动作";
                }
            };
        }
    }

    /** 地上没有新冒出来的掉落物。 */
    private static final PicksUpDrops NO_DROPS = new PicksUpDrops() {
        @Override public Set<Integer> nearby() {
            return Set.of();
        }

        @Override public Action pickUpNewSince(Set<Integer> before) {
            return null;
        }
    };

    private static LiveHunts hunts(FakeMoves moves) {
        return new LiveHunts(moves, NO_DROPS, entityId -> Optional.of(true), WILD,
                new FakeBackpack(36), new FakeOffhand(), new FakeTags());
    }

    @Test
    void 看得见但够不着_先朝它现在的位置走_够得着才出手() {
        FakeMoves moves = new FakeMoves();
        ScansCreatures.CreatureSpot sheep = new ScansCreatures.CreatureSpot(3, new UUID(0, 3), "minecraft:sheep",
                WorldPosition.here(5, 64, 0), true, "white", false);
        Action hunting = hunts(moves).hunt(List.of(sheep), wool(), Permissions.DEFAULT).orElseThrow();
        for (int i = 0; i < 3; i++) hunting.tick(TICK);
        // 它已经走到 12 格外：朝它现在的位置走，不站在原地对着空气挥。
        assertTrue(moves.done.contains("走到 12"), moves.done.toString());
        assertTrue(!moves.done.contains("打"), "够不着不出手：" + moves.done);
        moves.inReach = true;
        hunting.tick(TICK);
        assertEquals("打", moves.done.get(moves.done.size() - 1), moves.done.toString());
    }

    @Test
    void 出去找_要什么一路带着_找遍了如实说没见到() {
        FakeMoves moves = new FakeMoves();
        PermissionCheck permission = new PermissionCheck(new Protection((dimension, x, y, z) -> Optional.empty(),
                List::of, name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self"), WILD);
        Hunts.SearchPlan plan = new Hunts.SearchPlan(List.of("minecraft:sheep"), Optional.of("white"), true,
                32, 16, 16, (types, center, radius) -> List.of(), WILD, permission);
        Action searching = hunts(moves).searchAndHunt(wool(), plan, Permissions.DEFAULT).orElseThrow();
        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 200 && !(status instanceof ActionStatus.Failed); i++) {
            status = searching.tick(TICK);
        }
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertTrue(failed.problem().message().contains("16 格"), failed.problem().message());
        assertTrue(searching.describe().contains("羊毛") || failed.problem().message().contains("wool")
                || failed.problem().message().contains("羊毛"), failed.problem().message());
    }
}
