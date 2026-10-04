package org.maiwithu.maicraft.server.physics;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsVector;

/** 移位预览必须使用新桨叶平面和新作用点；原世界未被修改，反推与缺少桨叶也单独检验。 */
public final class PreflightPropellerEditsTest {
    public static void run() {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var old=new BlockPos(0,0,0);var raised=old.above(2);
        Map<BlockPos,BlockState> original=new HashMap<>();
        original.put(old,Blocks.OAK_PLANKS.defaultBlockState());
        original.put(old.north(),Blocks.WHITE_WOOL.defaultBlockState());
        var proposed=new HashMap<>(original);proposed.put(old,Blocks.AIR.defaultBlockState());proposed.put(old.north(),Blocks.AIR.defaultBlockState());
        proposed.put(raised,Blocks.OAK_PLANKS.defaultBlockState());
        for(var side:new Direction[]{Direction.UP,Direction.DOWN,Direction.NORTH,Direction.SOUTH})
            proposed.put(raised.relative(side),Blocks.WHITE_WOOL.defaultBlockState());
        check(power(original,old)==1&&power(proposed,old)==0&&power(proposed,raised)==4,"候选平面须读新增四片桨叶并清除旧平面，原图仍不变");
        proposed.put(raised.east(),Blocks.WHITE_WOOL.defaultBlockState());
        check(power(proposed,raised)==4,"轴向另一层不应被前方平面预览擅自算入");
        var point=new PhysicsVector(-6.5,-.5,2.5);
        var forward=PreflightPropellerEdits.load("raised",point,Direction.WEST,4,64,.2,.05,.9,false);
        var reverse=PreflightPropellerEdits.load("raised",point,Direction.WEST,4,64,.2,.05,.9,true);
        check(forward.point().equals(point)&&forward.force().x()<0,"西向轴承的正 RPM 应在新位置向西推");
        check(Math.abs(forward.force().x()+reverse.force().x())<1e-9,"原生反推只反转方向");
        check(PreflightPropellerEdits.load("empty",point,Direction.WEST,0,64,.2,.05,.9,false).force().length()==0,"没有桨叶不应捏造推力");
        System.out.println("PreflightPropellerEditsTest: passed");
    }
    private static double power(Map<BlockPos,BlockState> view,BlockPos anchor) {
        return PreflightRotorPreview.scan(anchor,Direction.WEST,pos->new StructureBlockInfo(pos,
                view.getOrDefault(pos,Blocks.AIR.defaultBlockState()),new CompoundTag()),info->info.state().is(Blocks.WHITE_WOOL)?1:0);
    }
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
}
