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

/** 不同楼层先回施工点，再证明局部支撑；阶段交接不能提前发方块或声称通路已经完成。 */
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
            check(invoke(task,"prepareTemporarySupports")==TaskState.RUNNING && field("phase").get(task).toString().equals("SUPPORT_APPROACH"),
                    "远处的下一段工程先进入通行准备，不从原站位误报局部支撑无路");
            check(field("supportAccess").get(task)==null && ((Map<?,?>)field("temporaryTargets").get(task)).isEmpty(),
                    "通行尚未确认时不生成可执行支撑格或放置证明");
            // 只注入已经到达附近实地的观察，检验真实状态机交接；这不是导航实机证据。
            h.position(new Vec3(10.5,1,7.5));h.nextTick();
            check(invoke(task,"supportApproachTick")==TaskState.RUNNING && field("phase").get(task).toString().equals("SUPPORT_VERIFY"),
                    "实际到达后才从新身体位置开始完整支撑验证");
            check(field("supportAccess").get(task)==null && h.inventory.getItem(0).getCount()==16 && h.blockUses()==0 && h.itemUses()==0,
                    "换区域和启动证明本身不消耗支撑材料或发送放置");
        }
        System.out.println("BuildSupportApproachTest: passed");
    }
    private static Object invoke(Object target,String name) throws Exception {var method=FirstPersonBuildCompanionTask.class.getDeclaredMethod(name);method.setAccessible(true);return method.invoke(target);}
    private static Field field(String name) throws Exception {var field=FirstPersonBuildCompanionTask.class.getDeclaredField(name);field.setAccessible(true);return field;}
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
