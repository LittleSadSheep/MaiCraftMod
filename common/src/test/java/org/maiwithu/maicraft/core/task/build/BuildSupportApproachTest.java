// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.task.TaskState;

/** 远处支撑直接进入逐块施工，由普通导航接近每一格，不另加回到指定楼层的前置阶段。 */
public final class BuildSupportApproachTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        try(var h=new InteractionWorldTestHarness()) {
            h.position(new Vec3(1.5,1,7.5));h.player.setDeltaMovement(Vec3.ZERO);h.inventory.setItem(0,new ItemStack(Items.DIRT,16));
            var target=new BuildTaskRecord.Target(Blocks.STONE,Items.STONE,new BlockPos(12,3,7),"另一侧楼梯",null,null,null);
            var record=new BuildTaskRecord("support-approach",1000,List.of(target),false,true);
            var task=new FirstPersonBuildCompanionTask(h.player,record);
            var type=Class.forName(FirstPersonBuildCompanionTask.class.getName()+"$CellPlan");
            var constructor=type.getDeclaredConstructor(BuildTaskRecord.Target.class,List.class);constructor.setAccessible(true);
            Object cell=constructor.newInstance(target,List.of());field("cell").set(task,cell);field("queue").set(task,new ArrayList<>(List.of(cell)));
            check(invoke(task,"prepareTemporarySupports")==TaskState.RUNNING && field("phase").get(task).toString().equals("SELECT"),
                    "远处支撑直接进入普通逐块施工，不先证明全程通路");
            check(!((Map<?,?>)field("temporaryTargets").get(task)).isEmpty()
                            && h.inventory.getItem(0).getCount()==16 && h.blockUses()==0 && h.itemUses()==0,
                    "排入队列尚未消耗材料，成功数量仍必须来自实际动作");
        }
        System.out.println("BuildSupportApproachTest: passed");
    }
    private static Object invoke(Object target,String name) throws Exception {var method=FirstPersonBuildCompanionTask.class.getDeclaredMethod(name);method.setAccessible(true);return method.invoke(target);}
    private static Field field(String name) throws Exception {var field=FirstPersonBuildCompanionTask.class.getDeclaredField(name);field.setAccessible(true);return field;}
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
