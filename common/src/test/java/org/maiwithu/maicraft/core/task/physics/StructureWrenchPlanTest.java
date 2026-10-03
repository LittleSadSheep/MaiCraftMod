package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonParser;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** 模拟原生绕点击面轴旋转，核对规划能把竖帆转成水平翼，且不会悄悄放宽作者属性。 */
public final class StructureWrenchPlanTest {
    public static void run() {
        var facing=BlockStateProperties.FACING;
        var state=Blocks.DISPENSER.defaultBlockState().setValue(facing,Direction.SOUTH);
        var edit=JsonParser.parseString("{\"properties\":{\"facing\":\"up\"}}").getAsJsonObject();
        var faces=StructureWrenchPlan.faces(state,edit,(current,face)->{
            Direction old=current.getValue(facing);
            return old.getAxis()==face.getAxis()?current:current.setValue(facing,old.getClockWise(face.getAxis()));
        });
        check(faces.equals(Set.of(Direction.EAST,Direction.WEST)),"帆面应点击侧面绕横轴转向，而不是反复点正面");
        check(!StructureWrenchPlan.matches(state,edit),"未调向的实际帆面不能通过朝向差异");
        check(StructureWrenchPlan.matches(state.setValue(facing,Direction.UP),edit),"原生达到明确朝向后没有完成");
        edit.getAsJsonObject("properties").addProperty("triggered","true");
        check(StructureWrenchPlan.faces(state,edit,(current,face)->current.setValue(facing,face)).isEmpty(),"扳手不能设置的属性被悄悄忽略");
        check(StructureWrenchPlan.faces(state,edit,(current,face)->current).isEmpty(),"无效旋转不应生成循环点击");
    }
    private static void check(boolean okay,String why) {if(!okay)throw new AssertionError(why);}
}
