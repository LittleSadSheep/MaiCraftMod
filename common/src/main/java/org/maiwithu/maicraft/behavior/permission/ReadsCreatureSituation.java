// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Optional;
import java.util.UUID;

/**
 * 读生物处境的只读接缝：一只生物是玩家的吗、敌对吗、有名字吗、被驯服了吗、拴着绳吗、在围栏里吗。
 *
 * <p>"围栏里"需要一个统一的看法（脚下与四周是什么、圈了多大致算圈养），游戏接口层还没有这个视图，
 * 先以这个接缝留位，实现留给游戏接口层轨；实现到位之前，行为层拿不到处境就按受保护处理。
 */
public interface ReadsCreatureSituation {

    /** 一只生物的处境；认不出（走远了、没了）返回空。 */
    Optional<CreatureSituation> situationOf(UUID entityId);

    /**
     * 一只生物的处境。
     *
     * @param player      是不是一位玩家；对玩家动手不归 fight 之外的任何许可管
     * @param hostileMob  是不是敌对生物（僵尸、骷髅这类）
     * @param named       有没有起过名字（名牌、命名牌）
     * @param tamed       有没有被驯服（别人的宠物）
     * @param leashed     有没有拴着绳
     * @param inEnclosure 在不在围栏、栏圈这类圈养设施里（围栏里的牲畜是别人的）
     */
    record CreatureSituation(boolean player, boolean hostileMob,
            boolean named, boolean tamed, boolean leashed, boolean inEnclosure) {

        /** 有没有哪条占着"这是别人的"的理由：有名字、被驯服、拴着绳、圈养着，占上一条就不碰。 */
        public boolean bondedToSomeone() {
            return named || tamed || leashed || inEnclosure;
        }
    }
}
