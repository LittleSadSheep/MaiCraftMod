// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 饥饿需求弄不到吃的之后怎么办：问遍各处都没有就歇一分钟不插，手上的活照常干；
 * 身上有了吃的、处境更急了马上照插；弄到一半超了预算不歇，照控制循环的老规矩缓几秒再试。
 */
class HungerNeedTest {

    private final SurvivalFakes.TestPlayer player = new SurvivalFakes.TestPlayer();
    private final TickContext tick = new SurvivalFakes.TestTick(player);
    private final List<String> events = new ArrayList<>();
    /** 此刻的饥饿处境：饱食度 5（不能疾跑），身上没吃的。 */
    private final AtomicReference<HungerNeed.Facts> now = new AtomicReference<>(new HungerNeed.Facts(5, false, 0));

    @Test
    void 问遍各处都弄不到吃的_歇一分钟不插() {
        // 10-15 直播：哪儿都弄不到吃的，五秒、十秒后又插进来再问一遍，把手上打猎的活停停走走。
        EatSoonTaskTest.FetchThenBite moves = new EatSoonTaskTest.FetchThenBite(-1,
                Problem.of(Problem.Kind.NEED_ITEM, "问过各来源都拿不到"));
        HungerNeed need = new HungerNeed(context -> now.get(), moves, (kind, message) -> events.add(message));

        assertEquals(Urgency.SOON, need.urgency(tick));
        runToEnd(need.createTask(tick));

        assertNull(need.urgency(tick), "刚问遍各处都没有，先不插");
        player.tick += HungerBout.REST_AFTER_NOTHING_FOUND_TICKS - 1;
        assertNull(need.urgency(tick), "一分钟没到还歇着");
        player.nextTick();
        assertEquals(Urgency.SOON, need.urgency(tick), "歇够了再找");
        assertEquals(1, events.size());
    }

    @Test
    void 歇着时饿到掉血或身上有了吃的_马上照插() {
        EatSoonTaskTest.FetchThenBite moves = new EatSoonTaskTest.FetchThenBite(-1,
                Problem.of(Problem.Kind.NEED_ITEM, "问过各来源都拿不到"));
        HungerNeed need = new HungerNeed(context -> now.get(), moves, (kind, message) -> events.add(message));
        runToEnd(need.createTask(tick));
        assertNull(need.urgency(tick));

        // 有人扔了块面包过来：身上有了这会儿能吃的，照常插进来吃。
        moves.carrying = true;
        assertEquals(Urgency.SOON, need.urgency(tick));
        moves.carrying = false;

        // 饿到掉血：比歇下时更急，马上再试。
        now.set(new HungerNeed.Facts(0, true, 0));
        assertEquals(Urgency.NOW, need.urgency(tick));
    }

    @Test
    void 弄到一半超了预算不歇() {
        // 找到了来源、只是一分钟没弄到（追鸡追丢了）：不算哪儿都没有，照控制循环的老规矩缓几秒再试。
        EatSoonTaskTest.FetchThenBite moves = new EatSoonTaskTest.FetchThenBite(-1,
                Problem.of(Problem.Kind.STUCK, "弄吃的过了 60 秒还没弄到"));
        HungerNeed need = new HungerNeed(context -> now.get(), moves, (kind, message) -> events.add(message));
        runToEnd(need.createTask(tick));

        assertEquals(Urgency.SOON, need.urgency(tick));
    }

    @Test
    void 不饿了这一回就过去_下回饿了重新说() {
        EatSoonTaskTest.FetchThenBite moves = new EatSoonTaskTest.FetchThenBite(-1,
                Problem.of(Problem.Kind.NEED_ITEM, "问过各来源都拿不到"));
        HungerNeed need = new HungerNeed(context -> now.get(), moves, (kind, message) -> events.add(message));
        runToEnd(need.createTask(tick));
        assertEquals(1, events.size());

        // 别人喂饱了：这一回过去，歇着也一并作废。
        now.set(new HungerNeed.Facts(20, false, 0));
        assertNull(need.urgency(tick));
        now.set(new HungerNeed.Facts(5, false, 0));
        assertEquals(Urgency.SOON, need.urgency(tick), "新的一回饿不再歇着");
        runToEnd(need.createTask(tick));
        assertEquals(2, events.size(), "新的一回饿没吃上，再说一次");
    }

    // 把吃饭任务推进到结束，最多一百刻。
    private void runToEnd(Task task) {
        task.start(tick);
        for (int i = 0; i < 100; i++) {
            if (task.tick(tick) instanceof TickResult.Finished) return;
            player.nextTick();
        }
        throw new AssertionError("一百刻内没有结束");
    }
}
