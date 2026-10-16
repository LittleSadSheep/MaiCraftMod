// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
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
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 狩猎的执行：看得见但够不着先朝它现在的位置走，够得着才出手；
 * 打倒了先回到它倒下的地方把这次掉出来的收走；放弃一只时交代它最后在哪、看得见还是被挡住；
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

    /** 出手与走位的替身：记下每次是走还是打；够不够得着、它在哪由测试摆。 */
    private static final class FakeMoves implements HuntMoves {
        final List<String> done = new ArrayList<>();
        boolean inReach;
        double[] targetAt = {12, 64, 0};
        /** 身上有一把剑斧还没拿到手上：要它时给一个换武器的动作，换完就拿在手上了。 */
        boolean weaponInBackpack;

        @Override public Optional<Action> readyWeapon(TickContext context) {
            if (!weaponInBackpack) return Optional.empty();
            weaponInBackpack = false;
            done.add("拿武器");
            return Optional.of(instant());
        }

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

        /** 人和它是不是泡在同一片水里，由测试摆。 */
        boolean sameWater;

        @Override public boolean sameWater(TickContext context, int entityId) {
            return sameWater;
        }

        @Override public Action swimTo(int entityId) {
            done.add("游过去追");
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

    /** 视线替身：这只此刻看得见、被挡住还是已经不在，由测试一路摆。 */
    private static final class FakeSight implements SeesCreatures {
        Optional<Boolean> visible = Optional.of(true);

        @Override public Optional<Boolean> visible(int entityId) {
            return visible;
        }
    }

    /** 捡掉落的替身：记下捡的动作被要了几次；捡得了捡不了由测试摆。 */
    private static final class RecordedDrops implements PicksUpDrops {
        final List<String> calls = new ArrayList<>();
        boolean unreachable;

        @Override public Set<Integer> nearby() {
            return Set.of();
        }

        @Override public Action pickUpNewSince(Set<Integer> before) {
            calls.add("捡这次掉出来的");
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return unreachable
                            ? ActionStatus.failed(Problem.of(Problem.Kind.STUCK,
                                    "有 1 件掉出的东西走过去了还在地上，先不捡了", null))
                            : ActionStatus.done();
                }

                @Override public String describe() {
                    return "捡掉落替身";
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
        return hunts(moves, NO_DROPS, new FakeSight());
    }

    private static LiveHunts hunts(FakeMoves moves, PicksUpDrops drops, SeesCreatures sight) {
        return new LiveHunts(moves, drops, sight, WILD,
                new FakeBackpack(36), new FakeOffhand(), new FakeTags());
    }

    /** 一只 5 格外的白羊，开局就能打着。 */
    private static ScansCreatures.CreatureSpot sheep() {
        return new ScansCreatures.CreatureSpot(3, new UUID(0, 3), "minecraft:sheep",
                WorldPosition.here(5, 64, 0), true, "white", false);
    }

    @Test
    void 看得见但够不着_先朝它现在的位置走_够得着才出手() {
        FakeMoves moves = new FakeMoves();
        Action hunting = hunts(moves).hunt(List.of(sheep()), wool(), Permissions.DEFAULT).orElseThrow();
        for (int i = 0; i < 3; i++) hunting.tick(TICK);
        // 它已经走到 12 格外：朝它现在的位置走，不站在原地对着空气挥。
        assertTrue(moves.done.contains("走到 12"), moves.done.toString());
        assertTrue(!moves.done.contains("打"), "够不着不出手：" + moves.done);
        moves.inReach = true;
        hunting.tick(TICK);
        assertEquals("打", moves.done.get(moves.done.size() - 1), moves.done.toString());
    }

    @Test
    void 同一片水里够不着_直接游过去追_不交给寻路_够得着就砍() {
        FakeMoves moves = new FakeMoves();
        moves.sameWater = true;
        Action hunting = hunts(moves).hunt(List.of(sheep()), wool(), Permissions.DEFAULT).orElseThrow();
        for (int i = 0; i < 3; i++) hunting.tick(TICK);
        // 寻路在水里到不了水下它跟前：直接游，不再一遍遍算路停在它头顶上。
        assertTrue(moves.done.contains("游过去追"), moves.done.toString());
        assertFalse(moves.done.stream().anyMatch(done -> done.startsWith("走到")), moves.done.toString());
        moves.inReach = true;
        hunting.tick(TICK);
        assertEquals("打", moves.done.get(moves.done.size() - 1), moves.done.toString());
    }

    @Test
    void 锁定一只后先把剑斧拿到手上再去追_手上有了就不再换() {
        FakeMoves moves = new FakeMoves();
        moves.weaponInBackpack = true;
        Action hunting = hunts(moves).hunt(List.of(sheep()), wool(), Permissions.DEFAULT).orElseThrow();
        for (int i = 0; i < 4; i++) hunting.tick(TICK);
        // 追到跟前才出手，那时不为换武器开背包：所以先换好武器再走过去。
        assertEquals("拿武器", moves.done.get(0), moves.done.toString());
        assertTrue(moves.done.contains("走到 12"), moves.done.toString());
        assertEquals(1, Collections.frequency(moves.done, "拿武器"), moves.done.toString());
    }

    @Test
    void 打倒了_先回到它倒下的地方_把这次掉出来的目标与副产物都捡走() {
        FakeMoves moves = new FakeMoves();
        moves.targetAt = new double[] {5, 64, 0};
        moves.inReach = true;
        FakeSight sight = new FakeSight();
        RecordedDrops drops = new RecordedDrops();
        Action hunting = hunts(moves, drops, sight).hunt(List.of(sheep()), wool(), Permissions.DEFAULT)
                .orElseThrow();
        hunting.tick(TICK); // 第一刻挑中这只
        hunting.tick(TICK); // 第二刻看得见又够得着，出手
        assertEquals("打", moves.done.get(moves.done.size() - 1), moves.done.toString());
        // 打倒了它不在了：不转身去追别的，先回它倒下的地方，再捡这次掉出来的。
        sight.visible = Optional.empty();
        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 10 && !(status instanceof ActionStatus.Failed); i++) {
            status = hunting.tick(TICK);
        }
        assertInstanceOf(ActionStatus.Failed.class, status);
        assertTrue(moves.done.contains("走到 5"), moves.done.toString());
        assertTrue(moves.done.indexOf("打") < moves.done.indexOf("走到 5"), moves.done.toString());
        assertEquals(List.of("捡这次掉出来的"), drops.calls, "捡的动作只要一次：" + drops.calls);
    }

    @Test
    void 打倒了_掉落物收不上来_如实说没能收进包_不冒充没见到() {
        FakeMoves moves = new FakeMoves();
        moves.targetAt = new double[] {5, 64, 0};
        moves.inReach = true;
        FakeSight sight = new FakeSight();
        RecordedDrops drops = new RecordedDrops();
        drops.unreachable = true;
        Action hunting = hunts(moves, drops, sight).hunt(List.of(sheep()), wool(), Permissions.DEFAULT)
                .orElseThrow();
        hunting.tick(TICK); // 第一刻挑中这只
        hunting.tick(TICK); // 第二刻出手
        sight.visible = Optional.empty();
        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 10 && !(status instanceof ActionStatus.Failed); i++) {
            status = hunting.tick(TICK);
        }
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertTrue(failed.problem().message().contains("没能收进包"), failed.problem().message());
        assertFalse(failed.problem().message().contains("没见到"),
                "掉落物明明在地上，不能说没见到：" + failed.problem().message());
    }

    @Test
    void 放弃一只时交代现场_它在哪多远_看得见还是被挡住() {
        FakeMoves moves = new FakeMoves();
        FakeSight sight = new FakeSight();
        Action hunting = hunts(moves, NO_DROPS, sight).hunt(List.of(sheep()), wool(), Permissions.DEFAULT)
                .orElseThrow();
        // 每只猎物身上的期限是 800 刻：够不着就一直走，超时放弃时说明要带现场。
        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 810 && !(status instanceof ActionStatus.Failed); i++) {
            status = hunting.tick(TICK);
        }
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        String message = failed.problem().message();
        assertTrue(message.contains("先不追了"), message);
        assertTrue(message.contains("东 12"), message);
        assertTrue(message.contains("同高"), message);
        assertTrue(message.contains("看得见"), message);
    }

    @Test
    void 放弃一只时被挡住_说明写视线被挡住() {
        FakeMoves moves = new FakeMoves();
        FakeSight sight = new FakeSight();
        sight.visible = Optional.of(false);
        Action hunting = hunts(moves, NO_DROPS, sight).hunt(List.of(sheep()), wool(), Permissions.DEFAULT)
                .orElseThrow();
        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 810 && !(status instanceof ActionStatus.Failed); i++) {
            status = hunting.tick(TICK);
        }
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertTrue(failed.problem().message().contains("视线被挡住"), failed.problem().message());
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
