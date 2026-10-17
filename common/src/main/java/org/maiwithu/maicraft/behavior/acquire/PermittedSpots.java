// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 候选格子的许可筛选：采集与采掘要挖的是角色自己挑中的格子，动手前逐格过一遍
 * 全仓唯一的许可检查点。全被挡下时把第一个拒绝原样带给引擎，由它向 LLM 要许可；
 * 许可检查点不存在（还没接上）时一格都不挖——宁可不做，不越权。
 */
public final class PermittedSpots {

    private PermittedSpots() {}

    /** 筛过的一批格子：动得了的，和第一个被挡下时的拒绝。 */
    public record Screen<T>(List<T> allowed, Problem firstRefusal) {

        public static <T> Screen<T> none(Problem refusal) {
            return new Screen<>(List.of(), refusal);
        }
    }

    /**
     * 逐格过许可：挖方块走 DIG_BLOCK 档位，位置取格子自己的坐标。
     * 位置写成"角色当前维度"的坐标，与扫描时看到的是同一个维度。
     */
    public static <T> Screen<T> screen(List<T> spots, Function<T, BlockPos> at,
            Function<T, String> blockType, Permissions permissions, PermissionCheck check) {
        List<T> allowed = new ArrayList<>();
        Problem firstRefusal = null;
        for (T spot : spots) {
            BlockPos pos = at.apply(spot);
            WorldPosition where = WorldPosition.here(pos.getX(), pos.getY(), pos.getZ());
            Optional<Problem> refusal = check.blockAllowed(permissions,
                    PermissionCheck.WorldAction.DIG_BLOCK, where, blockType.apply(spot));
            if (refusal.isEmpty()) {
                allowed.add(spot);
            } else if (firstRefusal == null) {
                firstRefusal = refusal.get();
            }
        }
        return new Screen<>(allowed, firstRefusal);
    }
}
