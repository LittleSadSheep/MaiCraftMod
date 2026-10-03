package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiFunction;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 只为明确声明的方块属性规划原生扳手点击面，不替模型改变翼面、轴承或车轮的设计朝向。 */
final class StructureWrenchPlan {
    private static final String WRENCHABLE="com.simibubi.create.content.equipment.wrench.IWrenchable";
    private record Step(BlockState state,int distance) {}
    private StructureWrenchPlan() {}
    static Set<Direction> faces(BlockState state,JsonObject edit) {
        if(!NativeApi.is(state.getBlock(),WRENCHABLE)||!edit.has("properties"))return Set.of();
        return faces(state,edit,(before,face)->(BlockState)NativeApi.call(before.getBlock(),null,"getRotatedBlockState",before,face));
    }
    static Set<Direction> faces(BlockState state,JsonObject edit,BiFunction<BlockState,Direction,BlockState> rotate) {
        var result=EnumSet.noneOf(Direction.class);int best=Integer.MAX_VALUE;
        // 同一显式目标可能从多个面旋到，保留所有最短首步，让执行器按真实可见面选择站位。
        for(Direction first:Direction.values()) {
            BlockState next=rotate.apply(state,first);if(next==null||next.equals(state)||!next.is(state.getBlock()))continue;
            var seen=new HashSet<BlockState>();seen.add(state);seen.add(next);
            var queue=new ArrayDeque<Step>();queue.add(new Step(next,1));
            while(!queue.isEmpty()&&seen.size()<=64) {
                Step step=queue.removeFirst();if(step.distance()>best)continue;
                if(matches(step.state(),edit)) {
                    if(step.distance()<best) {best=step.distance();result.clear();}
                    result.add(first);break;
                }
                if(step.distance()>=6)continue;
                for(Direction face:Direction.values()) {
                    BlockState turned=rotate.apply(step.state(),face);
                    if(turned!=null&&turned.is(state.getBlock())&&seen.add(turned))queue.addLast(new Step(turned,step.distance()+1));
                }
            }
        }
        return Set.copyOf(result);
    }
    static boolean matches(BlockState state,JsonObject edit) {
        if(!edit.has("properties"))return true;
        for(var entry:edit.getAsJsonObject("properties").entrySet()) {
            var property=state.getBlock().getStateDefinition().getProperty(entry.getKey());
            if(property==null||!state.getValue(property).toString().equalsIgnoreCase(entry.getValue().getAsString()))return false;
        }
        return true;
    }
}
