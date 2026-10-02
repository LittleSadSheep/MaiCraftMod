package org.maiwithu.maicraft.core.pathing.baritone;

/** 只统计真正获准驱动却没有搜索或路线的游戏刻；人工接管、原生确认与暂停不消耗恢复额度。 */
final class NavigationDispatchWatchdog {
    enum Action { NONE, RESTART, FAIL }
    private long lastTick = Long.MIN_VALUE;
    private int missingTicks, restarts;

    Action observe(long tick, boolean readyToDrive, boolean workPresent, boolean progressed) {
        if (lastTick == tick) return Action.NONE;
        lastTick = tick;
        if (progressed) restarts = 0;
        if (!readyToDrive || workPresent) { missingTicks = 0; return Action.NONE; }
        if (++missingTicks < 40) return Action.NONE;
        missingTicks = 0;
        if (restarts == 0) { restarts++; return Action.RESTART; }
        return Action.FAIL;
    }

    int restarts() { return restarts; }
}
