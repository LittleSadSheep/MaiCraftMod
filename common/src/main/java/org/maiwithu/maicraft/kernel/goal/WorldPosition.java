// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.goal;

/**
 * 世界里的一个方块位置。
 *
 * @param dimension 维度 ID，例如 minecraft:overworld；null 表示角色当前所在的维度
 */
public record WorldPosition(int x, int y, int z, String dimension) {

    /** 角色当前维度里的一个位置。 */
    public static WorldPosition here(int x, int y, int z) {
        return new WorldPosition(x, y, z, null);
    }
}
