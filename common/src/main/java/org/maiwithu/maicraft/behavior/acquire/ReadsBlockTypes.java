// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 读现场的一格此刻是什么方块：记忆里记着的床、台子、炉子，动手前到现场核对还在不在。
 * 生产实现读当刻的客户端世界，测试用替身。
 */
public interface ReadsBlockTypes {

    /** 这一格此刻的方块注册 ID；没加载、读不到、是空气都为空——不把读不到当成"还在"。 */
    Optional<String> blockTypeAt(WorldPosition at);
}
