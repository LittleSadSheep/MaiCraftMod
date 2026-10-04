// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.base;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.maiwithu.maicraft.core.FailureType;

/** 刚挖出的物品可能还在下落或漂移；短暂无路时在原拾取任务内等待重寻，持续不可达仍交付真实失败。 */
public final class PickupNavigationRetry {
    private static final int SETTLE_TICKS = 40, RETRY_INTERVAL_TICKS = 10;
    private record Window(long deadline, long nextAttempt) {}
    private final Map<UUID, Window> windows = new HashMap<>();

    public boolean afterFailure(UUID drop, long now, FailureType failure) {
        // 只重试位置可能变化的无路结果；权限、界面和动作效果未知不能借等待窗口静默重发。
        if (failure != FailureType.NO_PATH && failure != FailureType.TERRAIN_BLOCKED) return false;
        // 同一 UUID 从首次无路起最多留四十游戏刻，每十刻才再试；重试不刷新总窗口，也不重新提交拾取目标。
        // now 直接来自世界时钟，暂停后沿用原截止刻；这不是会自动扣除暂停时间的 ProgressBudget。
        var previous = windows.get(drop);
        long deadline = previous == null ? now + SETTLE_TICKS : previous.deadline();
        if (now >= deadline) return false;
        windows.put(drop, new Window(deadline, Math.min(deadline, now + RETRY_INTERVAL_TICKS)));
        return true;
    }

    public boolean waiting(UUID drop, long now) {
        // 等待只推迟下一次寻路，不挡住接触检测和已同步入包回执，流水把物品送来时可立即收尾。
        var window = windows.get(drop);
        return window != null && now < window.nextAttempt();
    }
}
