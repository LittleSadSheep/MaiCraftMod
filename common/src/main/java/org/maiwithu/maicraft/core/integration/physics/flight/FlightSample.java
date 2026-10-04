package org.maiwithu.maicraft.core.integration.physics.flight;

import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.integration.physics.StructurePose;

/** 飞控只接受实际姿态、按时间计算的运动和独立接地证据；高度接近地面不能冒充已经着陆。 */
public record FlightSample(long tick,Vec3 position,Vec3 velocity,double heading,double pitch,double bank,
                           double pitchRate,double bankRate,double headingRate,Contact contact) {
    public enum Contact { GROUNDED, AIRBORNE, UNKNOWN }
    public FlightSample {
        if(position==null||velocity==null)throw new IllegalArgumentException("flight position and velocity are required");
        for(double value:new double[]{position.x,position.y,position.z,velocity.x,velocity.y,velocity.z,
                heading,pitch,bank,pitchRate,bankRate,headingRate})
            if(!Double.isFinite(value))throw new IllegalArgumentException("flight sample must be finite");
        if(contact==null)throw new IllegalArgumentException("flight contact evidence is required");
    }
    public double speed(){return velocity.length();}
    public double horizontalSpeed(){return velocity.horizontalDistance();}
    public double forwardSpeed(){return velocity.dot(forward(heading,pitch));}
    // 采用 Minecraft 航向：南为零、向西为正；右机翼下降为正滚转，抬头为正俯仰。
    public static Vec3 forward(double heading,double pitch) {
        return new Vec3(-Math.sin(heading)*Math.cos(pitch),Math.sin(pitch),Math.cos(heading)*Math.cos(pitch));
    }
    public static double heading(Vec3 direction){return Math.atan2(-direction.x,direction.z);}
    public static double wrap(double radians){return Math.atan2(Math.sin(radians),Math.cos(radians));}

    /** 重复或过期的姿态刻不产生第二笔控制；首次采样没有速度证据，须等待下一刻。 */
    public static final class Sampler {
        private StructurePose previous;
        private long previousTick=Long.MIN_VALUE;
        private double previousHeading,previousPitch,previousBank;
        public FlightSample observe(long tick,StructurePose pose,Vec3 localForward,Contact contact) {
            if(previous!=null&&tick<=previousTick)return null;
            Vec3 forward=pose.normalToWorld(localForward),up=pose.normalToWorld(new Vec3(0,1,0));
            Vec3 right=forward.cross(up).normalize();
            double heading=heading(forward),pitch=Math.atan2(forward.y,forward.horizontalDistance());
            double bank=Math.atan2(-right.y,up.y);
            FlightSample sample=null;
            // 超过半秒没有姿态更新时重新建立基线，避免把重载或暂停跨度当成稳定飞行反馈。
            if(previous!=null&&tick-previousTick<=10) {
                double seconds=(tick-previousTick)/20.0;
                sample=new FlightSample(tick,pose.position(),pose.position().subtract(previous.position()).scale(1/seconds),
                        heading,pitch,bank,wrap(pitch-previousPitch)/seconds,wrap(bank-previousBank)/seconds,
                        wrap(heading-previousHeading)/seconds,contact);
            }
            previous=pose;previousTick=tick;previousHeading=heading;previousPitch=pitch;previousBank=bank;
            return sample;
        }
    }
}
