package org.maiwithu.maicraft.core.integration.create;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** 压路机物品会在完整地面上抬高一格，空的轮座底部则仍按原上下文放置；两者都只做只读预测。 */
public final class CreateRollerPlacementTest {
    public static void run() throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        try(var h=new InteractionWorldTestHarness()) {
            BlockPos at=new BlockPos(4,1,4),side=at.east();h.set(side,Blocks.STONE.defaultBlockState());h.set(at.below(),Blocks.STONE.defaultBlockState());
            var context=new BlockPlaceContext(h.player,InteractionHand.MAIN_HAND,new ItemStack(Items.STONE),
                    new BlockHitResult(new Vec3(5,1.5,4.5),Direction.WEST,side,false));
            check(context.getClickedPos().equals(at),"测试原始右键没有落在声明格");
            check(CreateRollerPlacement.raisedContext(context).getClickedPos().equals(at.above()),"完整地面上的轮座原生上移没有被识别");
            check(CreateRollerPlacement.context((BlockItem)Items.STONE,context)==context,"普通建筑方块被错误套用轮座抬高规则");
            h.set(at.below(),Blocks.AIR.defaultBlockState());
            check(CreateRollerPlacement.raisedContext(context)==context,"轮座下方空出后仍被错误抬高");
            check(h.blockUses()==0&&h.level.getBlockState(at).isAir()&&h.level.getBlockState(at.above()).isAir(),"预测轮座落点实际放置了方块");
        }
    }
    private static void check(boolean okay,String why) {if(!okay)throw new AssertionError(why);}
}
