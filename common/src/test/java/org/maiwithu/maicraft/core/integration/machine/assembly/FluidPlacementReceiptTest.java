// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.assembly;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import org.maiwithu.maicraft.client.actor.NativeConfirmation.Verdict;

/** 桶账和源格可以分包更新；只有真实费用与最终源状态共同成立才成功，另一格或另一种流体不算。 */
public final class FluidPlacementReceiptTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var air = Blocks.AIR.defaultBlockState(); var water = Blocks.WATER.defaultBlockState();
        check(FluidPlacementReceipt.compare(water,air,water,2,3,2,3,false)==Verdict.PENDING,"仅看到源格不能冒充桶已结算");
        check(FluidPlacementReceipt.compare(air,air,water,2,3,1,4,false)==Verdict.PENDING,"仅扣桶不能证明目标格已填好");
        check(FluidPlacementReceipt.compare(water,air,water,2,3,1,3,false)==Verdict.PENDING,"空桶返还未同步仍须等待");
        check(FluidPlacementReceipt.compare(water,air,water,2,3,1,4,false)==Verdict.APPLIED,"源格、满桶减少一件和空桶增加一件共同证明本次操作");
        check(FluidPlacementReceipt.compare(water,air,water,2,3,0,4,false)==Verdict.DIVERGED,"不能吞掉额外一桶仍宣称成功");
        check(FluidPlacementReceipt.compare(Blocks.LAVA.defaultBlockState(),air,water,2,3,1,4,false)==Verdict.DIVERGED,"另一流体不能顶替所需源格");
        check(FluidPlacementReceipt.compare(water.setValue(LiquidBlock.LEVEL,2),air,water,2,3,1,4,false)==Verdict.PENDING,"同种流动格仍不是最终源状态");
        check(FluidPlacementReceipt.compare(water,air,water,2,2,2,2,true)==Verdict.APPLIED,"创造真实免耗，不要求凭空产生空桶");
        System.out.println("FluidPlacementReceiptTest: passed");
    }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
