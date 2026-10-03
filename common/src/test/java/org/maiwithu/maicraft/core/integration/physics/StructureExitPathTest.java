package org.maiwithu.maicraft.core.integration.physics;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 座面、台阶、围挡和落差使用同一条身体扫掠路径，防止只验证出口而忽略车沿中间碰撞。 */
public final class StructureExitPathTest {
    public static void main(String[] args) {
        var ground = new AABB(-6,-1,-6,6,0,6);
        var seat = new AABB(-.5,1,-.5,.5,2,.5);
        Vec3 start = new Vec3(0,2,0), end = new Vec3(-2,0,0);
        check(StructureExitPath.clear(List.of(ground,seat),start,end,.64,1.84),"可以从两格高座面向外走到地面");
        var wall = new AABB(-1.4,0,-1,-1,4,1);
        check(!StructureExitPath.clear(List.of(ground,seat,wall),start,end,.64,1.84),"中途围挡会阻止离车候选");
        check(!StructureExitPath.clear(List.of(seat),start,end,.64,1.84),"未观察到地面不能当作可落脚");
        check(!StructureExitPath.clear(List.of(ground,new AABB(-.5,0,-.5,.5,4,.5)),new Vec3(0,4,0),end,.64,1.84),"离车不能擅自跳下高甲板");
        check(!StructureExitPath.clear(List.of(ground,seat),List.of(new AABB(-2.5,0,-.5,-1.5,2,.5)),start,end,.64,1.84),"禁入格不能被地面支撑掩盖");
        var step = new AABB(-1.5,0,-.5,-.5,1.5,.5);
        check(StructureExitPath.clear(List.of(ground,seat,step),start,end,.64,1.84),"允许先踩低一级车沿再落地");
        // 角色还在看向控制台时，移动仍沿出口方向，镜头旋转不能把第一步带到车头另一侧。
        for (float yaw : new float[]{0,45,90,180,-135}) {
            var command = StructureDeparture.toward(new Vec3(-1,0,-1),yaw);
            double angle = Math.toRadians(yaw);
            double x = -command.forward()*Math.sin(angle)+command.strafe()*Math.cos(angle);
            double z = command.forward()*Math.cos(angle)+command.strafe()*Math.sin(angle);
            check(x < 0 && Math.abs(x-z)<1e-6,"所有初始视角都应保持相同出口方向");
        }
        System.out.println("StructureExitPathTest: passed");
    }
    private static void check(boolean okay,String detail) { if (!okay) throw new AssertionError(detail); }
}
