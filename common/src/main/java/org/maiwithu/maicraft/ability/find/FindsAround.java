// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.find;

import java.util.List;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 附近寻找的接缝：任务每刻问一遍"这一轮扫到哪了"，实现方去做世界里的事——
 * 扫方块、数实体、翻记过的线索。只读，不动身体、不改方块。
 *
 * <p>视线闸、命中复核实际状态、最近变化补查、多部件实体折叠、受保护事实
 * 都在实现方读现场时裁决；任务只管收命中、发编号、按数量与覆盖收束。
 * 实现留给启动一侧接真实客户端，测试用替身。
 */
public interface FindsAround {

    /**
     * 找一轮：给这一刻新确认的命中与覆盖情况。
     * 没扫完时已找到的部分照常给出，任务下一刻再来问；"没扫完"不冒充"没有"。
     */
    Round scan(FindInput input);

    /**
     * 一轮寻找的收成。
     *
     * @param hits         这一刻确认的命中；同一回合可能重复给出之前给过的，任务负责去重
     * @param complete     愿意找的范围扫完了没有
     * @param worldChanged 扫描期间换了世界：这轮结果作废，任务如实结束，不冒充结论
     */
    record Round(List<Hit> hits, boolean complete, boolean worldChanged) {

        public Round {
            hits = List.copyOf(hits);
        }
    }

    /**
     * 一个确认的命中。
     *
     * @param kind              找到的是方块、实体还是结构线索
     * @param typeId            方块或实体类型注册 ID；结构线索是要找的结构 ID
     * @param position          命中位置；结构线索是线索记的大概位置
     * @param gameEntityId      游戏实体编号；方块与线索没有，为 -1
     * @param creatureProtected 是不是受保护的生物（有名字、被驯服、拴着绳、圈养着）；
     *                          照常报告，但不冒充"无主的找到了"
     * @param protectionReason  受保护的具体原因；不受保护为 null
     */
    record Hit(FindInput.FindKind kind, String typeId, WorldPosition position,
            int gameEntityId, boolean creatureProtected, String protectionReason) {

        /** 方块与结构线索的命中：没有游戏实体编号，也没有保护事实。 */
        public static Hit place(FindInput.FindKind kind, String typeId, WorldPosition position) {
            return new Hit(kind, typeId, position, -1, false, null);
        }
    }
}
