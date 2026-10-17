// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 回去捡尸体：重生后身上少了东西、还来得及、同一个维度、离得不远才回去；一次死亡只回去一次。
 */
class LostItemsNeedTest {

    private record Tick(long gameTick) implements TickContext {
        @Override public PlayerContext player() {
            return null;
        }
    }

    /** 替身：身体由测试摆，死了的身体记在名单里。 */
    private static final class Players implements LostItemsNeed.ReadsPlayer {
        LostItemsNeed.PlayerNow now;
        final Set<Object> dead = new HashSet<>();

        @Override public LostItemsNeed.PlayerNow read(TickContext context) {
            return now;
        }

        @Override public boolean died(Object identity) {
            return dead.contains(identity);
        }
    }

    private final Players players = new Players();
    private LostItemsNeed.Death recovered;
    private final LostItemsNeed need = new LostItemsNeed(players, (death, expires) -> {
        recovered = death;
        return null;
    }, null);

    private static LostItemsNeed.PlayerNow player(Object who, double x, String dimension, long tick, int stacks) {
        return new LostItemsNeed.PlayerNow(who, x, 64, 0, dimension, tick, stacks);
    }

    private Urgency at(LostItemsNeed.PlayerNow body) {
        players.now = body;
        return need.urgency(new Tick(body.gameTick()));
    }

    @Test
    void respawnedLighterRunsBackToTheDeathSpot() {
        Object first = new Object();
        Object second = new Object();
        assertNull(at(player(first, 10, "minecraft:overworld", 100, 12)));
        // 死了：循环停摆，旧身体是死的；40 刻后在出生点重生，身上空了。
        players.dead.add(first);
        assertNull(at(player(second, 40, "minecraft:overworld", 140, 0)), "刚重生先等世界就绪");
        assertEquals(Urgency.SOON, at(player(second, 40, "minecraft:overworld", 140 + LostItemsNeed.SETTLE_TICKS, 0)));
        Task task = need.createTask(new Tick(141 + LostItemsNeed.SETTLE_TICKS));
        assertNull(task);
        assertNotNull(recovered);
        assertEquals(10, recovered.x(), "回到死前最后一刻在的地方");
        assertNull(at(player(second, 40, "minecraft:overworld", 142 + LostItemsNeed.SETTLE_TICKS, 0)), "一次死亡只回去一次");
    }

    @Test
    void keptInventoryOrTooLateOrElsewhereStaysPut() {
        Object first = new Object();
        Object second = new Object();
        at(player(first, 10, "minecraft:overworld", 100, 12));
        players.dead.add(first);
        assertNull(at(player(second, 40, "minecraft:overworld", 140, 12)), "保留物品栏：身上没少，不用回去");

        Object third = new Object();
        at(player(third, 10, "minecraft:overworld", 200, 12));
        players.dead.add(third);
        Object fourth = new Object();
        assertNull(at(player(fourth, 10, "minecraft:the_nether", 240, 0)), "重生在别的维度：不回去");

        Object fifth = new Object();
        at(player(fifth, 10, "minecraft:overworld", 300, 12));
        players.dead.add(fifth);
        assertNull(at(player(new Object(), 10 + LostItemsNeed.REACH_BLOCKS + 1, "minecraft:overworld", 340, 0)),
                "重生在太远的地方：跑回去东西早没了");
    }

    @Test
    void waitsForTheInventoryToArriveBeforeCallingItLost() {
        Object first = new Object();
        Object second = new Object();
        at(player(first, 10, "minecraft:overworld", 100, 12));
        players.dead.add(first);
        // 开着死亡不掉落：重生那一刻背包内容还没同步过来，读出来是空的；下一刻到了，一件没少。
        assertNull(at(player(second, 40, "minecraft:overworld", 140, 0)), "背包还没到，先不下结论");
        assertNull(at(player(second, 40, "minecraft:overworld", 141, 12)), "背包到了，身上没少");
        assertNull(at(player(second, 40, "minecraft:overworld", 141 + LostItemsNeed.SETTLE_TICKS, 12)),
                "过了等候也不回去捡");
        assertNull(recovered);
    }

    @Test
    void givesUpWhenTheDropsAreAboutToVanish() {
        Object first = new Object();
        Object second = new Object();
        at(player(first, 10, "minecraft:overworld", 100, 12));
        players.dead.add(first);
        assertNull(at(player(second, 40, "minecraft:overworld", 140, 0)));
        assertEquals(Urgency.SOON, at(player(second, 40, "minecraft:overworld", 140 + LostItemsNeed.SETTLE_TICKS, 0)));
        assertNull(at(player(second, 40, "minecraft:overworld", 100 + LostItemsNeed.DESPAWN_TICKS, 0)),
                "快消失了还没插进去：不白跑");
    }
}
