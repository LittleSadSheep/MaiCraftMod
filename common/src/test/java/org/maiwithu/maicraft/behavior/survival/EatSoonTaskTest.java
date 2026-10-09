// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 临时吃饭任务：一次吃一口；吃不上、弄不到如实以没做成收场；每一口的动作都由任务收尾。
 * 确认只认真的：只有真的咽下一口、处境过了线才说"吃上了"，"还不算饿"从来不是"吃上了"的证据
 * ——死亡态饿度不再恢复，谎报一次就会每刻重播一遍。
 */
class EatSoonTaskTest {

    private final SurvivalFakes.TestPlayer player = new SurvivalFakes.TestPlayer();
    private final List<String> events = new ArrayList<>();

    @Test
    void hungryWithFoodTakesOneBiteAndFinishes() {
        // 饿了身上有面包：吃一口就收场，还饿的话由饥饿需求下一次报急再插。
        Bites bites = new Bites(ActionStatus.running(), ActionStatus.done());
        TaskResult result = run(bites, facts(10, true), () -> facts(10, true));

        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(1, bites.started);
        assertEquals(bites.started, bites.closed, "吃完这一口的动作要收尾，不能一直按着使用键");
    }

    @Test
    void noFoodAndNoWayToFetchFailsWithNeedItem() {
        // 饿到不能疾跑、身上没吃的、弄吃的也没接上：以缺东西收场并发事件，不按完成算。
        TaskResult result = run(new Bites(), facts(5, false), () -> facts(5, false));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.NEED_ITEM, result.problem().kind());
        assertEquals(1, events.size());
    }

    @Test
    void biteThatKeepsFailingGivesUpAfterLimitAndClosesEveryTry() {
        // 游戏一直不让吃：每一口都换新动作重试、旧动作收尾，接连失败到上限如实收手。
        Problem refused = Problem.of(Problem.Kind.REFUSED_BY_GAME, "吃不了");
        Bites bites = new Bites(ActionStatus.failed(refused));
        TaskResult result = run(bites, facts(5, true), () -> facts(5, true));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.REFUSED_BY_GAME, result.problem().kind());
        assertEquals(EatSoonTask.MAX_FAILED_BITES, bites.started);
        assertEquals(bites.started, bites.closed, "每一口的动作都要收尾");
    }

    @Test
    void noLongerHungryFinishesWithoutEating() {
        Bites bites = new Bites(ActionStatus.done());
        TaskResult result = run(bites, facts(10, true), () -> facts(20, true));

        assertEquals(TaskResult.Status.DONE, result.status());
        assertTrue(result.summary().contains("不饿"), result.summary());
    }

    @Test
    void notHungryAndNothingEdibleEndsWithoutClaimingItAte() {
        // 饱食度还没掉到线以下、身上也没有能吃的：如实说"不饿"，不冒充吃过。
        Bites bites = new Bites(ActionStatus.done());
        TaskResult result = run(bites, facts(15, false), () -> facts(15, false));

        assertEquals(TaskResult.Status.DONE, result.status());
        assertFalse(result.summary().contains("吃上了"), "没吃过就不能说吃上了：" + result.summary());
        assertEquals(0, bites.bitesTicked, "不饿也没得吃，不去动嘴");
    }

    @Test
    void toppingOffCountsAsAteOnlyWhenFoodCrossesTheLineAfterSwallow() {
        // 不算饿但身上有吃的：这正是被插进来的原因（找空当垫一口）。咽下之后饱食度过了线才说"吃上了"。
        AtomicReference<HungerNeed.Facts> now = new AtomicReference<>(facts(15, true));
        Bites bites = new Bites(ActionStatus.running(), ActionStatus.done());
        bites.onBiteDone = () -> now.set(facts(20, true));
        TaskResult result = run(bites, now.get(), now::get);

        assertTrue(result.summary().contains("吃上了"), "真咽下一口、不再饿了才这么说：" + result.summary());
        assertEquals(2, bites.bitesTicked, "垫一口要真的在吃");
    }

    @Test
    void stillHungryAfterABiteReportsTheBiteWithoutClaimingItAte() {
        // 吃了一口还饿：如实说吃了一口，等饥饿需求下一次报急再插，不说"吃上了"。
        Bites bites = new Bites(ActionStatus.done());
        TaskResult result = run(bites, facts(5, true), () -> facts(5, true));

        assertEquals(TaskResult.Status.DONE, result.status());
        assertTrue(result.summary().contains("吃了一口"), result.summary());
        assertFalse(result.summary().contains("吃上了"), "还饿着就不能说吃上了：" + result.summary());
    }

    @Test
    void mouthThatNeverSwallowsKeepsTheTaskRunningWithoutFalseSuccess() {
        // 吃的动作一直没成（比如死亡界面点不动嘴）：任务不收工，也不谎报"吃上了"。
        AtomicReference<HungerNeed.Facts> now = new AtomicReference<>(facts(15, true));
        Bites bites = new Bites(ActionStatus.running());
        EatSoonTask task = new EatSoonTask(bites, context -> now.get(),
                (kind, message) -> events.add(message), now.get());
        TickContext context = new SurvivalFakes.TestTick(player);
        task.start(context);
        for (int i = 0; i < 10; i++) {
            assertTrue(task.tick(context) instanceof TickResult.Running,
                    "没吃成就不该收工，更不该报成功");
            player.nextTick();
        }
        assertEquals(10, bites.bitesTicked, "每刻都在试着吃");
        assertEquals(0, events.size(), "进食在正常推进，不发打扰的事件");
    }

    // 插进来时读到 initial，之后每刻读到 now 的最新值；推进到结束，最多一百刻。
    private TaskResult run(Bites bites, HungerNeed.Facts initial, Supplier<HungerNeed.Facts> now) {
        EatSoonTask task = new EatSoonTask(bites, context -> now.get(),
                (kind, message) -> events.add(message), initial);
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

    /** 饥饿处境替身：掉不掉血固定为否，饱食度与身上有没有能吃的是测试给的。 */
    @Test
    void carryingFoodButUnableToEatEndsAsUnsupportedSoTheLoopWaits() {
        // 身上有面包、进食流程却给不出吃的动作：以"不支持"收场，控制循环据此缓一阵再插，不每个空当都插进来又立刻收场。
        TaskResult result = run(new Bites(), facts(5, true), () -> facts(5, true));

        assertEquals(TaskResult.Status.FAILED, result.status());
        assertEquals(Problem.Kind.UNSUPPORTED, result.problem().kind());
        assertEquals(1, events.size(), "真饿着才把事实告诉 LLM");
    }

    // 带着吃的时身上最小的一件普通食物按面包（补 5）算。
    private static HungerNeed.Facts facts(int food, boolean carryingEdible) {
        return new HungerNeed.Facts(food, false, carryingEdible ? 5 : 0);
    }

    /** 吃一口的替身：每次给一个新动作，按脚本回答推进结果，记下开了几口、收尾了几口、真推了几刻。 */
    static final class Bites implements EatSoonTask.FoodMoves {
        private final List<ActionStatus> script;
        /** 一口吃完时调用，测试用它改变之后的饥饿处境；平时为 null。 */
        Runnable onBiteDone;
        int started;
        int closed;
        int bitesTicked;

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
                    bitesTicked++;
                    ActionStatus status = script.get(Math.min(step++, script.size() - 1));
                    if (status instanceof ActionStatus.Done && onBiteDone != null) {
                        onBiteDone.run();
                    }
                    return status;
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
