// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.UUID;
import java.util.List;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 扫村民的只读接缝：以一处为圆心，把附近已加载的村民与流浪商人报上来。
 * 谁能点开交易界面（成年、有业）由读端按游戏事实标好，排除谁、为什么排除由来源写——判断不藏在读数里。
 */
public interface ScansMerchants {

    /**
     * 半径内的村民与流浪商人，由近及远。已加载的才算；客户端认得的实体就是服务器同步过来的这一批。
     *
     * @param center       从哪里找起
     * @param radiusBlocks 半径，单位格
     */
    List<MerchantSpot> merchants(WorldPosition center, int radiusBlocks);

    /**
     * 一只村民候选。
     *
     * @param entityId     游戏的实体编号：走到跟前、右键搭话都按它找回这只村民
     * @param uuid         生物编号：问处境（有没有名字、拴没拴绳）用它
     * @param at           此刻所在的位置
     * @param describe     给人看的一句话，例如"工具匠村民""流浪商人""傻子村民"
     * @param baby         是不是幼年：幼年的右键开不出交易界面
     * @param opensTrading 成年且有业（或流浪商人）：右键能不能开出交易界面是游戏事实，读端按职业标好
     */
    record MerchantSpot(int entityId, UUID uuid, WorldPosition at, String describe,
            boolean baby, boolean opensTrading) {
    }
}
