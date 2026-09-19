// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.process;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.task.inventory.TargetedDropGeometry;
import org.maiwithu.maicraft.core.task.inventory.TargetedDropRegion;

/** 重放实机缺角池，保留防误拾与完整原生散布；精确接收格和最后触发物的取物邻域均不得被包围盒替代。 */
public final class WorldProcessSiteTest {
    private static final Set<BlockPos> SOURCES=Set.of(new BlockPos(10,1,5),new BlockPos(10,1,6),
            new BlockPos(11,1,4),new BlockPos(11,1,5),new BlockPos(11,1,6),new BlockPos(12,1,4),new BlockPos(12,1,5),new BlockPos(12,1,6));
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        try(var world=new InteractionWorldTestHarness()) {
            for(int x=8;x<=14;x++)for(int z=2;z<=8;z++)for(int y=0;y<=1;y++) {
                BlockPos at=new BlockPos(x,y,z);world.set(at,SOURCES.contains(at)?Blocks.WATER.defaultBlockState():Blocks.STONE.defaultBlockState());
            }
            world.position(new Vec3(12.716992959374187,2,7.715705611363118));
            var receiver=new BlockPos(11,1,5);var site=WorldProcessSite.inspect(world.player,receiver,recipe());
            var region=TargetedDropRegion.ofCells(site.fluids);var original=world.player.position();float yaw=world.player.getYRot();
            check(site.fluids.size()==8,"缺角池必须保留实际的8个流体格");
            check(!site.safeWaiting(world.player,new BlockPos(12,2,7))&&site.safeWaiting(world.player,new BlockPos(12,2,8)),
                    "靠池一圈仍有误拾风险，修复不能缩小原有拾取隔离距离");
            var stands=site.feedingStands(world.player);
            var reachable=stands.stream().filter(stand->TargetedDropGeometry.canReach(world.player,
                    Vec3.atBottomCenterOf(stand.feet()),stand.receiver(),region)).toList();
            check(!reachable.isEmpty(),"实际可见流体面与精确连通格集合必须允许正常原生投料，而不是强求全部散布落在一格里");
            check(world.player.position().equals(original)&&world.player.getYRot()==yaw&&world.itemUses()==0,
                    "候选站位检查只读，不能移动角色、转头或预投材料");
            check(!region.contains(new Vec3(10.5,1.9,4.5)),"连通域包围盒中的缺角不得被当成接收格");
            rejects(()->region.narrowTo(new AABB(new BlockPos(10,1,4))));
            var tiny=region.narrowTo(new AABB(11.49,1.8,5.49,11.51,1.95,5.51));
            check(!tiny.narrowTo(new AABB(0,0,0,16,16,16)).contains(new Vec3(12.5,1.9,5.5)),"二次约束只能收窄，不能把原生取物范围再扩大");
            var stand=reachable.getFirst();world.position(Vec3.atBottomCenterOf(stand.feet()));
            check(!TargetedDropGeometry.canReach(world.player,world.player.position(),receiver,tiny),
                    "取物邻域过窄时必须在提交触发物前拒绝，不能回退到整池投料");
            Vec3 aim=TargetedDropGeometry.aim(world.player,receiver,region).orElseThrow();Vec3 direction=aim.subtract(world.player.getEyePosition());
            world.player.setYRot((float)Math.toDegrees(Math.atan2(-direction.x,direction.z)));
            world.player.setXRot((float)-Math.toDegrees(Math.atan2(direction.y,Math.sqrt(direction.horizontalDistanceSqr()))));
            check(TargetedDropGeometry.safeActualView(world.player,receiver,region),"实际相机对齐后仍须落在同一严格接收域");
            world.player.setYRot(world.player.getYRot()+90);
            check(!TargetedDropGeometry.safeActualView(world.player,receiver,region),"朝向改变时不能沿用计划的弹道许可");
            // 在角色与池子之间立起夹具障碍，证明点视线修复没有绕过物品整段路径的真实碰撞。
            for(int x=0;x<16;x++)for(int y=2;y<7;y++)world.set(new BlockPos(x,y,7),Blocks.STONE.defaultBlockState());
            check(!TargetedDropGeometry.canReach(world.player,world.player.position(),receiver,region),"围墙实际挡住水面时不能借高抛或包围盒强行投料");
            System.out.println("WorldProcessSiteTest: "+stands.size()+" safe candidates, "+reachable.size()+" reachable; native scatter, exact cells and narrowed bounds passed");
        }
    }
    private static WorldProcessRecipe recipe() {
        return new WorldProcessRecipe() {
            @Override public ResourceLocation id(){return ResourceLocation.parse("test:water_process");}
            @Override public List<Ingredient> inputs(){return List.of(Ingredient.of(Items.QUARTZ));}
            @Override public ItemStack result(){return new ItemStack(Items.DIAMOND);}
            @Override public boolean supports(FluidState fluid){return fluid.getType().isSame(Blocks.WATER.defaultBlockState().getFluidState().getType());}
            @Override public boolean isFluid(){return true;}
            @Override public JsonObject describe(){return new JsonObject();}
            @Override public int triggerInputIndex(){return 0;}
        };
    }
    private static void rejects(Runnable action){try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("无接收格的交集必须拒绝");}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
