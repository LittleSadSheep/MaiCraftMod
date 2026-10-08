// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

/**
 * 地形许可：这次走到被允许动多少地形。只走不改时，路线绕开要挖要垫的地方，
 * 走不过去就如实说走不过去；允许动土时可以挖开挡路的方块、垫上垫块开路。
 * 用不用落地水（摔落时对手里的水桶放水缓冲）单独给，放水本身就是一次地形变化之外的额外消耗。
 *
 * <p>这是走到这一件事的动手余地；超出它的更大许可（动谁的方块、保护哪些地标）
 * 仍由任务一侧的许可把关，这里不重复也不放宽。
 *
 * @param mayChangeTerrain 能否挖开与垫上方块来开路
 * @param mayUseWaterBucket 摔落时能否用手里的水桶放水缓冲
 */
public record TerrainPermit(boolean mayChangeTerrain, boolean mayUseWaterBucket) {

    /** 只走不改：不挖、不垫、不放水。 */
    public static final TerrainPermit WALK_ONLY = new TerrainPermit(false, false);

    /** 可以动土：挖开与垫上都允许，坠落缓冲的落地水也允许。 */
    public static final TerrainPermit TERRAFORM = new TerrainPermit(true, true);
}
