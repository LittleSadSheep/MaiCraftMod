// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;

import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/**
 * 施工目标本身是本次授权的变更集合：材料/导航保护区不能把自家目标格判成不可变更。
 * 末地门/下界门准备曾把整个门框 footprint 注册为材料保护，clearance 随即把需要清障的
 * 门框格判为"explicitly protected"，任何场地都报 protected or unbreakable cells。
 */
public final class BuildClearanceOwnTargetsTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            // 模拟嵌进地形的目标格：门框格被石头占据（坡地工地常态），且该格同时在材料保护名单里。
            BlockPos frame = new BlockPos(2, 1, 3);
            h.set(frame, Blocks.STONE.defaultBlockState());
            var record = new BuildTaskRecord("portal-build", 10_000,
                    List.of(new BuildTaskRecord.Target(Blocks.OBSIDIAN.defaultBlockState(),
                            Items.OBSIDIAN, frame, "frame", null, null, null)),
                    ReplaceMode.REPLACE_ANY, true, false, false);
            record.materialSupplyProtection(List.of(frame));
            var survey = BuildClearanceSurvey.forPlan(h.player, record);
            survey.advance(512);
            check(!survey.blocked(), "材料保护名单里的自家目标格不得被判为不可变更");
        }
        System.out.println("BuildClearanceOwnTargetsTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
