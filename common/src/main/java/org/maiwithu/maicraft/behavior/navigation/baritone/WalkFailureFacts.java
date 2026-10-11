// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.navigation.HazardEscapePolicy;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 走不通时交给 LLM 的现场，免得一句"没有路"把调整行动要的事实藏起来：从哪出发、脚下与身体那几格是什么
 * （哪格不在可挖名单、哪格受保护）、手边能垫什么、这次许可让不让动土、附近有没有岩浆该往哪撤。
 * 这些是现场观察，不声称已经解释了整条路线为什么走不通；建议只在原问题没给时补一句。
 */
final class WalkFailureFacts {

    private WalkFailureFacts() {}

    /**
     * 把现场补进走不通的问题里。
     *
     * @param spareScaffold 手边能拿去垫的料与件数（正要用的已经扣掉）
     */
    static Problem enrich(Problem problem, ClientLevel level, LocalPlayer player, TerrainPermit permit,
                          Map<String, Integer> spareScaffold) {
        if (level == null || player == null) return problem;
        BlockPos feet = player.blockPosition();
        StringBuilder facts = new StringBuilder(problem.message());
        facts.append("；现场：起点 ").append(at(feet)).append("，").append(columnFacts(level, feet));
        facts.append("；手边能垫的料：").append(spareScaffold.isEmpty() ? "没有" : scaffoldText(spareScaffold));
        facts.append("；这次 change_blocks=").append(permit.changes().name().toLowerCase(Locale.ROOT))
                .append(permit.mayChangeTerrain() ? "" : "，不挖不垫");
        HazardEscapePolicy escape = HazardEscapePolicy.detect(level, feet);
        if (escape.active()) {
            Map<String, Object> hazard = escape.evidence();
            facts.append("；附近 ").append(hazard.get("distance_blocks")).append(" 格有岩浆 ")
                    .append(hazard.get("nearest_hazard")).append("，往 ").append(hazard.get("escape_direction"))
                    .append(" 撤，可以先走到 ").append(hazard.get("suggested_retreat"));
        }
        String suggestion = problem.suggestion() != null ? problem.suggestion()
                : suggestion(permit, spareScaffold.isEmpty(), escape.active());
        return Problem.of(problem.kind(), facts.toString(), suggestion);
    }

    // 脚下、身体两格、头顶那一格分别是什么：不在可挖名单的、受保护的、不许进的标出来。
    private static String columnFacts(ClientLevel level, BlockPos feet) {
        List<String> cells = new ArrayList<>();
        String[] names = {"脚下", "身体下半", "身体上半", "头顶"};
        for (int dy = -1; dy <= 2; dy++) {
            BlockPos cell = feet.offset(0, dy, 0);
            String name = names[dy + 1];
            if (!level.isLoaded(cell)) {
                cells.add(name + "没加载");
                continue;
            }
            BlockState state = level.getBlockState(cell);
            StringBuilder one = new StringBuilder(name).append(' ')
                    .append(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
            if (!state.isAir() && !ClearanceWhitelist.allows(state)) one.append("（不在可挖名单）");
            if (NavigationProtection.protects(cell)) one.append("（受保护）");
            if (NavigationProtection.forbidsEntry(cell)) one.append("（不许进）");
            cells.add(one.toString());
        }
        return String.join("、", cells);
    }

    private static String scaffoldText(Map<String, Integer> spare) {
        List<String> parts = new ArrayList<>();
        spare.forEach((item, count) -> parts.add(item + "×" + count));
        return String.join("、", parts);
    }

    // 原问题没给建议时补一句：先说岩浆，再说许可，再说垫料。
    private static String suggestion(TerrainPermit permit, boolean noScaffold, boolean nearLava) {
        if (nearLava) return "先往远离岩浆的方向撤开几格，再重新走";
        return switch (permit.changes()) {
            case NONE -> "把 change_blocks 提到 natural 就能挖开挡路的天然方块、垫方块过去";
            case TEMPORARY -> "只许垫不许挖；把 change_blocks 提到 natural 就能挖开挡路的天然方块";
            case NATURAL, ANY -> noScaffold ? "身上没有能垫的料：带些泥土、圆石这类垫脚料再走，或换一条路"
                    : "换个目标点或绕一段路再试";
        };
    }

    private static String at(BlockPos cell) {
        return "(" + cell.getX() + ", " + cell.getY() + ", " + cell.getZ() + ")";
    }
}
