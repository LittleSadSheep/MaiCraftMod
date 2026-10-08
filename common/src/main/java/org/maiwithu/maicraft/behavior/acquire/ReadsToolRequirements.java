// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.Optional;

/**
 * 读工具要求的只读接缝：挖掉某种方块需要什么工具、手上这件够不够格。
 * 工具等级与方块硬度的对应是游戏自己的规则，实现在游戏接口层读真实的工具与方块属性；
 * 缺镐先弄镐的判断只用这里给出的结论。
 */
public interface ReadsToolRequirements {

    /**
     * 挖掉这种方块需要的工具；不需要特定工具的方块（泥土、木头）返回 empty。
     * 返回的写法与 {@link WantedItem} 一致：具体物品或 {@code #} 开头的标签。
     */
    Optional<String> toolRequired(String blockTypeId);

    /** 这件工具（或手里的东西）够不够格挖掉这种方块；等级不够时挖了也不掉落。 */
    boolean sufficient(String toolItemId, String blockTypeId);
}
