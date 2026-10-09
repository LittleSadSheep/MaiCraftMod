// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/** 临时吃饭任务：一次吃一口；吃不上、弄不到如实以没做成收场；每一口的动作都由任务收尾。 */
class EatSoonTaskTest {

    private final SurvivalFakes.TestPlayer player = new SurvivalFakes.TestPlayer();
    private final List<String> events = new ArrayList<>();

    @Test
    void hungryWithFoodTakesOneBiteAndFinishes() {
        // 饿了身上有面包：吃一口就收场，还饿的话由饥饿需求下一次报急再插。
        Bites bites = new Bites(ActionStatus.running(), ActionStatus.done());
        TaskResult result = run(bites, new HungerNeed.Facts(10, false, true), new HungerNeed.Facts(10, false, true));

        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(1, bites.started);
        assertEquals(1, bites.closed, "吃完这一口的动作要收尾，不能一直按着使用键");
    }

    @Test
    void noFoodAndNoWayToFetchFailsWithNeedItem() {
        // 饿到不能疾跑、身上没吃的、弄吃的也没接上：以缺东西收场并发事件，不按完成算。
        TaskResult result = run(new Bites(), new HungerNeed.Facts(5, false, false), new HungerNeed.Facts(5, false, false));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind());
        assertEquals(1, events.size());
    }

    @Test
    void biteThatKeepsFailingGivesUpAfterLimitAndClosesEveryTry() {
        // 游戏一直不让吃：每一口都换新动作重试、旧动作收尾，接连失败到上限如实收手。
        Problem refused = Problem.of(Problem.Kind.REFUSED_BY_GAME, "吃不了");
        Bites bites = new Bites(ActionStatus.failed(refused));
        TaskResult result = run(bites, new HungerNeed.Facts(5, false, true), new HungerNeed.Facts(5, false, true));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.REFUSED_BY_GAME, result.problem().kind());
        assertEquals(EatSoonTask.MAX_FAILED_BITES, bites.started);
        assertEquals(bites.started, bites.closed, "每一口的动作都要收尾");
    }

    @Test
    void noLongerHungryFinishesWithoutEating() {
        Bites bites = new Bites(ActionStatus.done());
        TaskResult result = run(bites, new HungerNeed.Facts(10, false, true), new HungerNeed.Facts(20, false, true));

        assertEquals(TaskResult.Status.DONE, result.status());
        assertTrue(result.summary().contains("不饿"), result.summary());
    }

    // 插进来时读到 initial，之后每刻读到 now；推进到结束，最多一百刻。
    private TaskResult run(Bites bites, HungerNeed.Facts initial, HungerNeed.Facts now) {
        EatSoonTask task = new EatSoonTask(bites, context -> now, (kind, message) -> events.add(message), initial);
        TickContext context = new SurvivalFakes.TestTick(player);
        task.start(context);
        for (int i = 0; i < 100; i++) {
            if (task.tick(context) instanceof TickResult.Finished finished) {
                return finished.result();
            }
            player.nextTick();
        }
        throw new AssertionError("一百刻内没有结束");
    }

    /** 吃一口的替身：每次给一个新动作，按脚本回答推进结果，记下开了几口、收尾了几口。 */
    static final class Bites implements EatSoonTask.FoodMoves {
        private final List<ActionStatus> script;
        int started;
        int closed;

        Bites(ActionStatus... script) {
            this.script = List.of(script);
        }

        @Override
        public Action eating(HungerNeed.Facts hunger) {
            if (script.isEmpty() || !hunger.carryingEdible()) return null;
            started++;
            return new Action() {
                private int step;

                @Override public ActionStatus tick(TickContext context) {
                    return script.get(Math.min(step++, script.size() - 1));
                }

                @Override public void close() { closed++; }

                @Override public String describe() { return "吃一口"; }
            };
        }

        @Override
        public Action fetching(long budgetTicks) {
            return null;
        }
    }
}
