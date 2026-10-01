// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.UUID;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.base.PickupNavigationRetry;

/** 掉落暂时无路只在同一任务内短等；另一堆、真正未知的效果和持续失败不能无限重开动作。 */
public final class PickupNavigationRetryTest {
    public static void main(String[] args) {
        var retry = new PickupNavigationRetry(); var drop = UUID.randomUUID();
        // 每十刻允许重新观察落点；重复失败不能把最初四十刻的结算期限向后无限推移。
        for (int tick = 100; tick < 140; tick += 10) {
            check(retry.afterFailure(drop, tick, FailureType.NO_PATH), "短暂无路保留当前拾取任务");
            check(retry.waiting(drop, tick + 9) && !retry.waiting(drop, tick + 10), "等待结束才重新寻路");
            check(!retry.waiting(UUID.randomUUID(), tick), "一堆物品的等待不能挡住另一堆");
        }
        check(!retry.afterFailure(drop, 140, FailureType.NO_PATH), "持续不可达最终如实报告，不循环调用模型或导航");
        check(!retry.afterFailure(UUID.randomUUID(), 140, FailureType.UNKNOWN), "未知动作效果不能自动重试");
        check(!retry.afterFailure(UUID.randomUUID(), 140, FailureType.NO_SPACE), "背包容量不足不冒充短暂路径问题");
        System.out.println("PickupNavigationRetryTest: passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
