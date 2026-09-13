package org.maiwithu.maicraft.core.task.build;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** 按现场已加载方块检查地下室能否开挖；基岩和世界高度都读实际存档，不针对超平坦写特例。 */
public final class BuildSiteConstraints {
    private BuildSiteConstraints() {}

    public static List<String> conflicts(LocalPlayer player, List<BuildTaskRecord.Target> targets) {
        // 开工和取料前先指出图纸撞到的不可挖地层，让规划调整地下室深度、整体高度或选址。
        var level = player.level();
        List<String> result = new ArrayList<>();
        for (var target : targets) {
            var pos = target.pos();
            boolean outside = level.isOutsideBuildHeight(pos);
            if (!outside && !level.isLoaded(pos)) continue;
            BlockState live = outside ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                    : level.getBlockState(pos);
            String code = conflict(target.constructionMatches(live), outside,
                    level.getWorldBorder().isWithinBounds(pos), live.getDestroySpeed(level, pos));
            if (code == null) continue;
            result.add(code + " at " + pos.toShortString() + ": "
                    + BuiltInRegistries.BLOCK.getKey(live.getBlock())
                    + "; build height [" + level.getMinBuildHeight() + ", "
                    + level.getMaxBuildHeight() + ")");
            if (result.size() == 16) break;
        }
        return List.copyOf(result);
    }

    static String conflict(boolean matches, boolean outsideHeight, boolean withinBorder, float hardness) {
        if (outsideHeight) return "outside_build_height";
        if (!withinBorder) return "outside_world_border";
        return !matches && hardness < 0 ? "unbreakable_terrain" : null;
    }
}
