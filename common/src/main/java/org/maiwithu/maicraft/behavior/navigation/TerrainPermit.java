// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation;

import org.maiwithu.maicraft.kernel.goal.Permissions;

/**
 * 地形许可：这次走到被允许动多少地形，按"能改变哪些方块"的四档说，与任务许可的
 * {@code change_blocks} 同一档位：不改 / 只垫不挖（垫上的临时方块用完如实报告，收不收回由 LLM 决定）/
 * 挖天然方块与垫 / 一切不受保护的格子。档位越低，路线越绕开要动土的地方，走不过去就如实说走不过去。
 * 用不用落地水（摔落时对手里的水桶放水缓冲）单独给，放下的水不是垫块，不随"只垫不挖"放宽。
 *
 * <p>这是走到这一件事的动手余地；超出它的更大许可（动谁的方块、保护哪些地标）
 * 仍由任务一侧的许可把关，这里不重复也不放宽。
 *
 * @param changes           能改变哪些方块，取值就是任务许可里的 changeBlocks（none/temporary/natural/any）
 * @param mayUseWaterBucket 摔落时能否用手里的水桶放水缓冲
 */
public record TerrainPermit(Permissions.BlockChanges changes, boolean mayUseWaterBucket) {

    /** 只走不改：不挖、不垫、不放水。 */
    public static final TerrainPermit WALK_ONLY = new TerrainPermit(Permissions.BlockChanges.NONE, false);

    /** 只垫不挖：垫块开路允许，挖开挡路的方块不允许；落地水放下的水随即流走，按允许处理。 */
    public static final TerrainPermit TEMPORARY = new TerrainPermit(Permissions.BlockChanges.TEMPORARY, true);

    /** 可以动土：挖开与垫上都允许，坠落缓冲的落地水也允许。 */
    public static final TerrainPermit NATURAL = new TerrainPermit(Permissions.BlockChanges.NATURAL, true);

    /** 一切不受保护的方块都可以动。 */
    public static final TerrainPermit ANY = new TerrainPermit(Permissions.BlockChanges.ANY, true);

    /**
     * 把任务许可折算成走到能动多少地形：方块档位原样对齐；能动地形时摔落缓冲的落地水也允许，
     * 一块都不许动时连水也不放。能不能挖某一格仍由寻路的方块通行判断按保护把关。
     */
    public static TerrainPermit of(Permissions permissions) {
        Permissions.BlockChanges changes = permissions.changeBlocks();
        return new TerrainPermit(changes, changes != Permissions.BlockChanges.NONE);
    }

    /** 还能不能动土（挖或垫任一）；路线要不要绕开要动土的地方看它。 */
    public boolean mayChangeTerrain() {
        return changes != Permissions.BlockChanges.NONE;
    }
}
