// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;

/**
 * 被拒站位：一项或几项检查不过的候选位置，记着最先败在哪一项。
 * 换站位时用它向结果交代"试过哪里、败在哪"；站位补救也从这里挑"只差一点"的位置。
 *
 * @param feet       被拒的落脚格
 * @param failedItem 败在哪一项：到不了、够不着、看不见、站不稳或受保护
 */
public record RejectedSpot(BlockPos feet, String failedItem) {
}
