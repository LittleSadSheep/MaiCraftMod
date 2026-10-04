package org.maiwithu.maicraft.core.integration.physics.flight;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 按速度与可用滚转角外推弯曲航迹，逐段检查整架飞机的空间；不能假设飞机瞬间转向航点。 */
public final class FlightPathProbe {
    public enum Space { CLEAR, BLOCKED, UNKNOWN }
    @FunctionalInterface public interface World { Space observe(AABB swept); }
    public record Result(Space state,List<Vec3> path,AABB obstruction,double turnRate) {
        public Result {path=List.copyOf(path);}
    }
    private FlightPathProbe() {}
    /** 已接地飞艇向上离地时容忍底面数值误差；仍检查完整宽度、顶部和上方十二格。 */
    public static Space verticalDeparture(World world,AABB bounds,boolean grounded) {
        double floorTolerance=grounded?.03:0;
        return world.observe(new AABB(bounds.minX,Math.min(bounds.maxY,bounds.minY+floorTolerance),bounds.minZ,
                bounds.maxX,bounds.maxY+12,bounds.maxZ));
    }
    public static Result trace(World world,FlightSample sample,AABB currentBounds,double wantedHeading,double verticalSpeed,
                               double seconds,FlightEnvelope envelope) {
        if(!Double.isFinite(seconds)||seconds<=0||seconds>12||!Double.isFinite(wantedHeading)||!Double.isFinite(verticalSpeed))
            throw new IllegalArgumentException("invalid flight path horizon");
        double speed=Math.max(sample.horizontalSpeed(),envelope.kind()==FlightEnvelope.Kind.FIXED_WING?envelope.takeoffSpeed():1);
        double turnRate=envelope.kind()==FlightEnvelope.Kind.FIXED_WING?9.81*Math.tan(envelope.maximumBank())/speed:.45;
        double vertical=Math.clamp(verticalSpeed,-envelope.descentRate(),envelope.climbRate());
        Vec3 position=sample.position();double heading=sample.heading();
        AABB offsets=currentBounds.move(position.scale(-1));
        var path=new ArrayList<Vec3>();path.add(position);AABB previous=currentBounds;
        // 四分之一秒的小段既覆盖机翼扫掠，也让地形读取量与实际速度、机体尺寸相关。
        int steps=(int)Math.ceil(seconds/.25);double dt=seconds/steps;
        for(int i=0;i<steps;i++) {
            double change=Math.clamp(FlightSample.wrap(wantedHeading-heading),-turnRate*dt,turnRate*dt);
            double middle=heading+change*.5;
            position=position.add(FlightSample.forward(middle,0).scale(speed*dt)).add(0,vertical*dt,0);
            heading=FlightSample.wrap(heading+change);
            AABB next=rotate(offsets,FlightSample.wrap(heading-sample.heading())).move(position);
            AABB swept=previous.minmax(next).inflate(.08,0,.08);
            Space state=world.observe(swept);path.add(position);
            if(state!=Space.CLEAR)return new Result(state,path,swept,turnRate);
            previous=next;
        }
        return new Result(Space.CLEAR,path,null,turnRate);
    }
    private static AABB rotate(AABB box,double rightTurn) {
        // Minecraft 右转航向与右手系 Y 旋转符号相反；保留真实上下边界，不把跑道地面算进机体。
        double c=Math.cos(rightTurn),s=Math.sin(rightTurn),minX=Double.POSITIVE_INFINITY,minZ=minX,maxX=-minX,maxZ=-minX;
        for(double x:new double[]{box.minX,box.maxX})for(double z:new double[]{box.minZ,box.maxZ}) {
            double nextX=c*x-s*z,nextZ=s*x+c*z;
            minX=Math.min(minX,nextX);maxX=Math.max(maxX,nextX);minZ=Math.min(minZ,nextZ);maxZ=Math.max(maxZ,nextZ);
        }
        return new AABB(minX,box.minY,minZ,maxX,box.maxY,maxZ);
    }
}
