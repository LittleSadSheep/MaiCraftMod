// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.platform.body;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

/**
 * 本刻的身体上下文：这一刻的玩家、世界，以及本刻还能不能提交原生操作。
 *
 * <p>每刻由平台层重新取得，只在本刻有效，不能留到下一刻使用：重生、换世界之后玩家对象会换掉。
 * WP-1.4 移植 v1 的 {@code LocalPlayerContext} 时并入本接口，补上身体输入、原生操作与菜单三个端口。
 */
public interface BodyContext {

    /** 本刻的本地玩家。 */
    LocalPlayer player();

    /** 本刻玩家所在的客户端世界。 */
    ClientLevel level();

    /** 本刻编号；同一刻内重复读取得到相同的值，用来判断手里的上下文是不是已经过期。 */
    long tickRevision();

    /** 这份上下文是否仍属于本刻；过期的上下文不能再用来操作身体。 */
    boolean isCurrent();

    /** 本刻是否还能提交一次原生操作。每刻只准提交一次，这是所有任务都"每刻做一点"的根本原因。 */
    boolean mutationAvailable();
}
