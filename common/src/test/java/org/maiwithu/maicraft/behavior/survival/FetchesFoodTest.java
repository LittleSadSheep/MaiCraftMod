// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 饿了身上没吃的：挨类问哪类口粮最近，拿最近那类；哪儿都没有、超了预算都如实说。 */
class FetchesFoodTest {

    /** 拿东西引擎的替身：每类口粮多远按表回答，动手就记下要的是哪类，下一刻做完。 */
    private static class FakeNeeds implements ItemNeeds {
        final Map<String, Double> distances;
        final List<String> asked = new ArrayList<>();
        boolean neverDone;

        FakeNeeds(Map<String, Double> distances) {
            this.distances = distances;
        }

        @Override public Optional<Double> nearestDistanceBlocks(ItemRequest request, Permissions permissions) {
            return Optional.ofNullable(distances.get(request.wanted().specifier()));
        }

        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            asked.add(request.wanted().specifier());
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return neverDone ? ActionStatus.running() : ActionStatus.done();
                }

                @Override public String describe() {
                    return "替身去拿";
                }
            };
        }
    }

    private static TickContext at(long tick) {
        return new TickContext() {
            @Override public long gameTick() {
                return tick;
            }

            @Override public PlayerContext player() {
                return null;
            }
        };
    }

    @Test
    void 挑最近的那一类口粮去拿() {
        // 网络里没有面包，箱子里有熟肉（5 格），田里有蔬菜（30 格）：拿熟肉。
        FakeNeeds needs = new FakeNeeds(Map.of("#c:foods/cooked_meat", 5d, "#c:foods/vegetable", 30d));
        FetchesFood fetch = new FetchesFood(needs, 1200);
        assertInstanceOf(ActionStatus.Done.class, fetch.tick(at(0)));
        assertEquals(List.of("#c:foods/cooked_meat"), needs.asked);
    }

    @Test
    void 这一类弄不成就换下一类() {
        // 问价说面包最近，去弄却没弄成：换熟肉，不就此饿着。
        FakeNeeds needs = new FakeNeeds(Map.of("#c:foods/bread", 2d, "#c:foods/cooked_meat", 8d)) {
            @Override public Action actionFor(ItemRequest request, Permissions permissions) {
                asked.add(request.wanted().specifier());
                boolean bread = request.wanted().specifier().equals("#c:foods/bread");
                return new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        return bread ? ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "面包弄不到")) : ActionStatus.done();
                    }

                    @Override public String describe() {
                        return "替身去拿";
                    }
                };
            }
        };
        FetchesFood fetch = new FetchesFood(needs, 1200);
        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 5 && !(status instanceof ActionStatus.Done); i++) status = fetch.tick(at(i));
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(List.of("#c:foods/bread", "#c:foods/cooked_meat"), needs.asked);
    }

    @Test
    void 叫得出名字的几类都没有_再找能吃的一大类() {
        // 面包、熟肉、熟鱼、蔬菜、水果都弄不到，箱子里只有模组食物：按"能吃的口粮"一大类去拿，不饿着。
        FakeNeeds needs = new FakeNeeds(Map.of("#maicraft:foods/edible", 12d));
        FetchesFood fetch = new FetchesFood(needs, 1200);
        assertInstanceOf(ActionStatus.Done.class, fetch.tick(at(0)));
        assertEquals(List.of("#maicraft:foods/edible"), needs.asked);
    }

    @Test
    void 叫得出名字的有就先拿它_不拿生肉这类() {
        FakeNeeds needs = new FakeNeeds(Map.of("#maicraft:foods/edible", 2d, "#c:foods/bread", 20d));
        FetchesFood fetch = new FetchesFood(needs, 1200);
        assertInstanceOf(ActionStatus.Done.class, fetch.tick(at(0)));
        assertEquals(List.of("#c:foods/bread"), needs.asked);
    }

    @Test
    void 哪儿都没有现成口粮如实说() {
        FetchesFood fetch = new FetchesFood(new FakeNeeds(Map.of()), 1200);
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, fetch.tick(at(0)));
        assertEquals(Problem.Kind.NEED_ITEM, failed.problem().kind());
    }

    @Test
    void 超了预算收手() {
        FakeNeeds needs = new FakeNeeds(Map.of("#c:foods/bread", 3d));
        needs.neverDone = true;
        FetchesFood fetch = new FetchesFood(needs, 100);
        assertInstanceOf(ActionStatus.Running.class, fetch.tick(at(0)));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, fetch.tick(at(101)));
        assertEquals(Problem.Kind.STUCK, failed.problem().kind());
    }
}
