// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.Properties;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.build.BuildingBudgets;

/** 确认真正放不成的站位会有限淘汰；换微小瞄点不重置预算，换位置或真实改变现场才有新机会。 */
public final class PlacementAttemptLedgerTest {
    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        var target = target(new BlockPos(4, 1, 4));
        var other = target(new BlockPos(5, 1, 4));
        BlockPos stance = new BlockPos(3, 1, 4);
        var ledger = new PlacementAttemptLedger(3);
        var first = gesture(stance, target.pos().below(), Direction.UP, .25);
        ledger.reject(target, first);
        var second = gesture(stance, target.pos().below(), Direction.UP, .5);
        check(ledger.allows(target, second), "一次拒绝后仍可做少量瞄点修正");
        ledger.reject(target, second);
        var third = gesture(stance, target.pos().west(), Direction.EAST, .75);
        check(ledger.allows(target, third), "预算内允许另一真实点击面");
        ledger.reject(target, third);
        check(!ledger.allows(target, gesture(stance, target.pos().west(), Direction.EAST, .750001)),
                "第三次失败后换像素也不能继续使用同一脚下格");
        check(ledger.stanceFailureCount(target, stance) == 3 && ledger.rejectedStanceCount(target) == 1,
                "回执记录整站位淘汰，而不只有微小点击候选数量");
        check(ledger.allows(target, gesture(stance.north(), target.pos().below(), Direction.UP, .25))
                && ledger.allows(other, first), "不同站位和不同目标不被连坐淘汰");
        ledger.changedNear(target.pos().east(7));
        check(!ledger.allows(target, second), "远处变化不能刷新此处预算让角色继续原地尝试");
        ledger.changedNear(target.pos().below());
        check(ledger.allows(target, first) && ledger.stanceFailureCount(target, stance) == 0,
                "附近已确认的施工变化重新开放新露出的路线和点击面");
        // 即使上层错误地重复交回同一个候选，真实拒绝次数也不能卡在一条HashSet记录上。
        ledger.reject(target, first); ledger.reject(target, first); ledger.reject(target, first);
        check(!ledger.allows(target, second), "重复的同一失败候选仍消耗站位预算");
        ledger.complete(target);
        check(ledger.allows(target, first) && ledger.rejectedStanceCount(target) == 0, "实物完成后释放该目标历史");

        Properties configured = new Properties(); configured.setProperty("placement.maxFailuresPerStance", "2");
        var configuredLedger = new PlacementAttemptLedger(BuildingBudgets.fromProperties(configured).maxPlacementFailuresPerStance());
        configuredLedger.reject(target, first); configuredLedger.reject(target, second);
        check(!configuredLedger.allows(target, third), "使用配置中的站位预算，而非隐含固定次数");
        configured.setProperty("placement.maxFailuresPerStance", "0");
        var invalid = BuildingBudgets.fromProperties(configured);
        check(invalid.maxPlacementFailuresPerStance() == 3 && !invalid.diagnostics().isEmpty(), "非法配置退回默认三次并报告");
        System.out.println("PlacementAttemptLedgerTest: bounded stance failures, independent targets and real-change recovery passed");
    }
    private static BuildTaskRecord.Target target(BlockPos position) {
        return new BuildTaskRecord.Target(Blocks.STONE, Items.STONE, position, "换位施工", null, null, null);
    }
    private static BuildPlacementGeometry.Gesture gesture(BlockPos stance, BlockPos clicked, Direction face, double coordinate) {
        return new BuildPlacementGeometry.Gesture(stance, clicked, face, Vec3.atCenterOf(clicked).add(coordinate, 0, 0), 0, 0, false, "same-stance probe");
    }
    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
