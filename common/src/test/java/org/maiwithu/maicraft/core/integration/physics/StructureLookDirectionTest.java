package org.maiwithu.maicraft.core.integration.physics;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** 先换回相机局部方向，再让原生叠加车体姿态；世界目标应在转弯、俯仰和横滚后保持相同。 */
public final class StructureLookDirectionTest {
    public static void run() {
        var world=new Vec3(1,0,0);var yaw=new Quaterniond().rotateY(Math.PI/2);
        near(StructureLookDirection.inverse(world,yaw),new Vec3(0,0,1),"右转九十度后应在相机正前方瞄准世界东方");
        var rotation=new Quaterniond().rotateXYZ(.3,1.2,-.4);world=new Vec3(2,-1,3);
        var local=StructureLookDirection.inverse(world,rotation);
        var restored=rotation.transform(new Vector3d(local.x,local.y,local.z));
        near(new Vec3(restored.x,restored.y,restored.z),world,"俯仰和横滚不能让世界瞄准点漂移");
        near(StructureLookDirection.inverse(world,null),world,"普通或解锁相机不应被额外旋转");
        try {StructureLookDirection.inverse(world,new Quaterniond(0,0,0,0));throw new AssertionError("无效姿态不能发送相机输入");}
        catch(IllegalStateException expected){}
        System.out.println("StructureLookDirectionTest: native camera inverse rotation passed");
    }
    private static void near(Vec3 actual,Vec3 expected,String reason){if(actual.distanceTo(expected)>1e-10)throw new AssertionError(reason+": "+actual);}
}
