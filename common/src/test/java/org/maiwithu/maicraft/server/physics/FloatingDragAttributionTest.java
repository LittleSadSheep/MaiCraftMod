package org.maiwithu.maicraft.server.physics;

import java.util.ArrayList;
import java.util.List;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsBody;
import org.maiwithu.maicraft.core.integration.physics.balance.PhysicsVector;

/** 只有全部残余与已核验的蒙皮力偶吻合才取消未知标记；任意其他力或坐标系差异都必须保留。 */
public final class FloatingDragAttributionTest {
    public static void run() {
        var known=new PhysicsVector(-11.694452281620187,-33.35465938986698,-11.959202085291423);
        for(int variant=0;variant<4;variant++) {
            var force=variant==1?new PhysicsVector(1,0,0):PhysicsVector.ZERO;
            var torque=variant==2?known.add(new PhysicsVector(0,0,1)):known;
            var frame=variant==3?PhysicsBody.Frame.WORLD:PhysicsBody.Frame.BODY;
            var original=new PhysicsBody.Load("unattributed_impulse","sable:unattributed_impulse",PhysicsVector.ZERO,force,torque,frame,false,0);
            var loads=new ArrayList<>(List.of(original));var unknowns=new ArrayList<>(List.of(NativePhysicsCapture.UNATTRIBUTED_WARNING));
            PreflightFloatingDrag.attribute(loads,unknowns,known);
            if(variant==0) {
                if(!unknowns.isEmpty()||!loads.getFirst().torque().equals(known)||!loads.getFirst().id().equals("floating_drag_couple"))
                    throw new AssertionError("已解释的原生力偶必须保留数值并重新归属");
            } else if(loads.getFirst()!=original||unknowns.isEmpty())throw new AssertionError("未知的其他载荷不能被删掉");
        }
        System.out.println("FloatingDragAttributionTest: complete attribution and unknown-source preservation passed");
    }
}
