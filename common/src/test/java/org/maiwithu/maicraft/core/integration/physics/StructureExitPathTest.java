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
        check(StructureExitPath.clear(List.of(ground,seat,new AABB(-.5,2.5,.5,.8,4,1.5)),
                new Vec3(.2,2,.2),new Vec3(-2,0,-1),.64,1.84),"贴近控制台的起点允许向外脱离保守碰撞余量");
        check(!StructureExitPath.clear(List.of(seat),start,end,.64,1.84),"未观察到地面不能当作可落脚");
        check(!StructureExitPath.clear(List.of(ground,new AABB(-.5,0,-.5,.5,4,.5)),new Vec3(0,4,0),end,.64,1.84),"离车不能擅自跳下高甲板");
        check(!StructureExitPath.clear(List.of(ground,seat),List.of(new AABB(-2.5,0,-.5,-1.5,2,.5)),start,end,.64,1.84),"禁入格不能被地面支撑掩盖");
        var step = new AABB(-1.5,0,-.5,-.5,1.5,.5);
        check(StructureExitPath.clear(List.of(ground,seat,step),start,end,.64,1.84),"允许先踩低一级车沿再落地");
        // 从真实低顶吊舱提取座面与气囊边缘的相对碰撞：直立出不去，潜行能离开座面，净空恢复后再起身。
        var lowSeat=new AABB(-.7,1,-.7,.7,1.4245,.7);
        var roof=new AABB(-1.48,3.049,-1.31,-.066,4.062,.104);
        var cabin=List.of(ground,lowSeat,roof);var seatedFeet=new Vec3(0,1.4245,0);var outside=new Vec3(2,0,0);
        check(StructureExitPath.posture(cabin,List.of(),seatedFeet,outside,.64,1.84,1.54)==StructureExitPath.Posture.CROUCHING,"低顶座面应选择原生潜行，不能忽略墙体");
        check(StructureExitPath.posture(cabin,List.of(),new Vec3(1.2,0,0),outside,.64,1.84,1.54)==StructureExitPath.Posture.STANDING,"走出低顶后必须恢复站立以离开边缘");
        check(StructureExitPath.posture(List.of(ground,lowSeat,new AABB(-1,2.7,-1,1,4,1)),List.of(),seatedFeet,outside,.64,1.84,1.54)==StructureExitPath.Posture.BLOCKED,"蹲姿仍撞头时保留原生阻挡");
        check(StructureDeparture.toward(new Vec3(1,0,0),90,true).sneaking(),"规划的蹲姿必须进入实际移动按键");
        // 临时拆轮后的固定翼侧倾约十三度；已接触稳定甲板的角色仍应进入真实出口搜索。
        double roll = Math.toRadians(15) / 2;
        var tilted = new StructurePose(Vec3.ZERO,0,0,Math.sin(roll),Math.cos(roll),Vec3.ZERO,new Vec3(1,1,1));
        check(StructureDeparture.quiet(tilted,tilted,new Vec3(2,1,0)),"静止倾斜不等于甲板在移动");
        var translating = new StructurePose(new Vec3(.05,0,0),0,0,Math.sin(roll),Math.cos(roll),Vec3.ZERO,new Vec3(1,1,1));
        check(!StructureDeparture.quiet(translating,tilted,new Vec3(2,1,0)),"快速平移不能进入步行离艇");
        var rotating = new StructurePose(Vec3.ZERO,0,0,Math.sin(roll+.02),Math.cos(roll+.02),Vec3.ZERO,new Vec3(1,1,1));
        check(!StructureDeparture.quiet(rotating,tilted,new Vec3(6,1,0)),"翼尖转动也要按实际脚位位移判断");
        check(!StructureDeparture.quiet(tilted,null,start),"缺少前一姿态仍不能声称甲板静止");
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
