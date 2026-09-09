package org.maiwithu.maicraft.core.combat;

import java.util.List;

/**
 * 交给战斗判断的一张当刻快照：玩家大致能扛多久、有什么武器、是否退不出去，以及附近目标情况。
 * Foe 里的 authorized 表示允许主动选择它；engaging 表示它正在攻击玩家。两者不是同一件事。
 */
public record Battlefield(double effectiveHealth,
                          double meleeReach,
                          boolean hasMelee,
                          boolean hasRanged,
                          boolean cornered,
                          List<Foe> foes) {

    /**
     * 场上的一个。
     *
     * @param id         运行时实体 id
     * @param distance   离她多远
     * @param explosive  它会炸,不管这一刻炸没炸——走近它要留余量,别踩进点火线
     * @param armed      它<b>现在就要炸了</b>:引信在走,或者是一打就炸的末影水晶
     * @param engaging   正在针对她(锁定了她,或刚打了她)
     * @param reachable  <b>上一段寻路搜得出路</b>。够不着是拓扑性质、不是距离性质,只有
     *                   寻路自己说得清 —— 悬崖对面三格的骷髅离得很近却没有路
     * @param authorized 允许打它。点名模式下是模型给的那份清单,无差别模式下就是"在追我的"
     */
    public record Foe(int id,
                      double distance,
                      boolean explosive,
                      boolean armed,
                      boolean engaging,
                      boolean reachable,
                      boolean authorized) {

        }

    // 在这一刻的候选列表中按编号找目标；不重新读世界，离开本次列表就返回 null。
    public Foe byId(int id) {
        for (Foe f : foes) {
            if (f.id() == id) {
                return f;
            }
        }
        return null;
    }
}
