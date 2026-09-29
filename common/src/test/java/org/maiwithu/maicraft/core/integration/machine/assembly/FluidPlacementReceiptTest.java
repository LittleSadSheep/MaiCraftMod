// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import org.maiwithu.maicraft.client.actor.NativeConfirmation.Verdict;

/** 原生动作以确认后的准确桶账结算；最终源状态交给机器差异验收，不能把确定的设计后果称为未知。 */
public final class FluidPlacementReceiptTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var air = Blocks.AIR.defaultBlockState(); var water = Blocks.WATER.defaultBlockState();
        check(FluidPlacementReceipt.compare(water,air,water,2,3,2,3,false)==Verdict.PENDING,"仅看到源格不能冒充桶已结算");
        check(FluidPlacementReceipt.compare(air,air,water,2,3,1,4,false)==Verdict.APPLIED,"已确认返桶的蒸发结果仍是动作发生，源格另行验收");
        check(FluidPlacementReceipt.compare(water,air,water,2,3,1,3,false)==Verdict.PENDING,"空桶返还未同步仍须等待");
        check(FluidPlacementReceipt.compare(water,air,water,2,3,1,4,false)==Verdict.APPLIED,"源格、满桶减少一件和空桶增加一件共同证明本次操作");
        check(FluidPlacementReceipt.compare(water,air,water,2,3,0,4,false)==Verdict.DIVERGED,"不能吞掉额外一桶仍宣称成功");
        check(FluidPlacementReceipt.compare(Blocks.LAVA.defaultBlockState(),air,water,2,3,1,4,false)==Verdict.APPLIED,"桶已准确结算时状态不符留给diff");
        check(FluidPlacementReceipt.compare(water.setValue(LiquidBlock.LEVEL,2),air,water,2,3,1,4,false)==Verdict.APPLIED,"流体更新不否认已经发生的桶操作");
        check(FluidPlacementReceipt.compare(water,air,water,2,2,2,2,true)==Verdict.APPLIED,"创造真实免耗，不要求凭空产生空桶");
        // 回收源格按相反桶账结算：空桶少一、满桶多一、原格真实为空气，缺一项都不得提前完成。
        check(FluidPlacementReceipt.compare(air,water,air,2,3,1,4,false)==Verdict.APPLIED,"源格消失与真实回桶一起证明拆除");
        check(FluidPlacementReceipt.compare(air,water,air,2,3,1,3,false)==Verdict.PENDING,"只看到源格消失仍等待满桶同步");
        check(FluidPlacementReceipt.compare(water,water,air,2,3,1,4,false)==Verdict.APPLIED,"取水后源格再生不应导致盲目重复取桶");
        check(FluidPlacementReceipt.compare(Blocks.STONE.defaultBlockState(),water,air,2,3,1,4,false)==Verdict.APPLIED,"真实取桶与最终清空目标分别结算");
        check(FluidPlacementReceipt.compare(air,water,air,1,0,1,1,true)==Verdict.APPLIED,"创造首次取水保留空桶并新增满桶");
        check(FluidPlacementReceipt.compare(air,water,air,1,1,1,1,true)==Verdict.APPLIED,"创造已有同种满桶时不重复增加");
        System.out.println("FluidPlacementReceiptTest: passed");
    }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
