// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 交互距离：角色够得着多远，全仓只有这一处实现。
 *
 * <p>方块与实体的交互距离读玩家身上的同名属性，和原版判定同源；
 * 床的距离另按服务端的床距离消息，消息没接上之前先按原版属性值回答，
 * 并在 {@link ServerBedDistance} 的默认实现里写明原因。
 */
public final class InteractionRange {

    /** 床的交互距离由服务端告诉客户端；这个接口就是读取它的出口。 */
    public interface ServerBedDistance {
        /** 角色与床之间允许的最大距离（格）。 */
        double bedDistance();
    }

    /**
     * 服务端床距离的默认实现：先按角色自己的方块交互距离回答。
     * 服务端距离消息属于网络消息移植轨，接入后在启动时换成读消息的实现。
     */
    public static final class VanillaAttributeBedDistance implements ServerBedDistance {
        private final LocalPlayer player;

        public VanillaAttributeBedDistance(LocalPlayer player) {
            this.player = player;
        }

        @Override public double bedDistance() {
            return blockRange(player);
        }
    }

    private InteractionRange() {}

    /** 方块交互距离：原版生存模式约 4.5 格，属性可被装备与效果改变。 */
    public static double blockRange(LocalPlayer player) {
        return player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
    }

    /** 实体交互距离：原版生存模式为 3 格，同样读属性。 */
    public static double entityRange(LocalPlayer player) {
        return player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
    }
}
