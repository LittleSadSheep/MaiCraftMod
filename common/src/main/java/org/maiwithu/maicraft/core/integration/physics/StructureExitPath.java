package org.maiwithu.maicraft.core.integration.physics;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 离开低矮甲板时逐段核对真实碰撞顶面；只允许不需跳跃的台阶和三格内落差。 */
public final class StructureExitPath {
    public enum Posture { STANDING, CROUCHING, BLOCKED }
    private StructureExitPath() {}

    /** 低顶吊舱先按站立检查，再尝试原生潜行身高；两种姿态都过不去时仍保留碰撞阻挡。 */
    public static Posture posture(List<AABB> boxes,List<AABB> forbidden,Vec3 from,Vec3 to,double width,double standingHeight,double crouchingHeight) {
        if(clear(boxes,forbidden,from,to,width,standingHeight))return Posture.STANDING;
        return clear(boxes,forbidden,from,to,width,crouchingHeight)?Posture.CROUCHING:Posture.BLOCKED;
    }

    public static boolean clear(List<AABB> boxes, Vec3 from, Vec3 to, double width, double height) {
        return clear(boxes, List.of(), from, to, width, height);
    }
    public static boolean clear(List<AABB> boxes, List<AABB> forbidden, Vec3 from, Vec3 to, double width, double height) {
        double distance = from.subtract(to).horizontalDistance();
        if (distance < .01 || distance > 5 || from.y - to.y > 3 || to.y > from.y + .6) return false;
        Vec3 previous = from;
        var obstacles = new PhysicalObstacleSnapshot(boxes,0,0,"observed");
        int steps = (int) Math.ceil(distance / .15);
        for (int i = 0; i <= steps; i++) {
            Vec3 column = from.lerp(to, (double) i / steps);
            double floor = Double.NEGATIVE_INFINITY;
            for (AABB box : boxes) if (box.minX < column.x + width / 2 && box.maxX > column.x - width / 2
                    && box.minZ < column.z + width / 2 && box.maxZ > column.z - width / 2
                    && box.maxY <= previous.y + .6 && box.maxY >= from.y - 3)
                floor = Math.max(floor, box.maxY);
            if (!Double.isFinite(floor)) return false;
            Vec3 feet = new Vec3(column.x, floor, column.z);
            // 身体需能扫过更高一侧的台阶，并在新列下降；薄座面和略倾斜的甲板不能被当成整格实心墙。
            double top = Math.max(previous.y, floor) + .002;
            AABB across = body(new Vec3(previous.x, top, previous.z), width, height)
                    .minmax(body(new Vec3(feet.x, top, feet.z), width, height));
            AABB down = body(feet.add(0, .002, 0), width, height)
                    .minmax(body(new Vec3(feet.x, top, feet.z), width, height));
            // 原生身体可能已贴在旋转部件的保守包围盒内；首点是已知站姿，后续只允许沿最近面向外脱离。
            if (i>0 && (!obstacles.clearSegment(new Vec3(previous.x,top,previous.z),new Vec3(feet.x,top,feet.z),width,height)
                    || !obstacles.clearSegment(new Vec3(feet.x,top,feet.z),feet.add(0,.002,0),width,height))) return false;
            // 禁入格和危险地面连脚底接触一起检查，不能把岩浆旁或保护区内的近路当成离车出口。
            for (AABB box : forbidden) if (box.intersects(across.inflate(0,.01,0)) || box.intersects(down.inflate(0,.01,0))) return false;
            previous = feet;
        }
        return Math.abs(previous.y - to.y) < .1;
    }

    private static AABB body(Vec3 feet, double width, double height) {
        return new AABB(feet.x-width/2, feet.y, feet.z-width/2, feet.x+width/2, feet.y+height, feet.z+width/2);
    }
}
