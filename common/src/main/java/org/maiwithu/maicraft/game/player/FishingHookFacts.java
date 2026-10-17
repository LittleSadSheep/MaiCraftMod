// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import net.minecraft.core.BlockPos;

/**
 * 鱼钩的客户端事实：角色抛竿后关联的那只浮标此刻怎样了。
 *
 * <p>咬钩状态是服务端经实体数据同步到客户端的字段，客户端拿它判断浮标下沉动画，
 * 不必等服务端的粒子或声音；钩住实体与所在格的流体同样只读客户端现场。
 */
public interface FishingHookFacts {

    /** 角色此刻有没有挂着鱼钩。抛出后为真，收线、脱钩或钩断为假。 */
    boolean hasHook();

    /** 挂着的鱼钩是不是正在咬钩（浮标被拉下水的客户端同步状态）。 */
    boolean isBiting();

    /** 挂着的鱼钩是不是钩住了实体（船、生物、别的玩家的钓竿）而不是落在水里。 */
    boolean hookedEntity();

    /** 挂着的鱼钩所在格是不是水里（该格有水即算，流动的也算）。 */
    boolean hookInWater();
}
