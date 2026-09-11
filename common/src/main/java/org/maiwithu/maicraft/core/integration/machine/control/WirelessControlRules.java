package org.maiwithu.maicraft.core.integration.machine.control;

import net.minecraft.world.phys.Vec3;

/** Create frequency equality is supplied by the native key object; positions are already in world space. */
public final class WirelessControlRules {
    public record Transfer(boolean inRange,int strength,String behavior) {}
    private WirelessControlRules() {}
    public static Transfer plain(boolean sameFrequency,Vec3 from,Vec3 to,double range,int strength) {
        boolean valid=sameFrequency && Double.isFinite(range) && range>0 && from.distanceTo(to)<range;
        return new Transfer(valid,valid?Math.clamp(strength,0,15):0,"ordered_frequency_native_range");
    }
    public static Transfer modulating(boolean sameFrequency,Vec3 from,Vec3 to,int min,int max,int strength) {
        double distance=from.distanceTo(to);
        boolean valid=sameFrequency && min>=0 && max>=min && distance<=max;
        double fraction=min==max ? 1:Math.clamp((distance-max)/(min-max),0,1);
        return new Transfer(valid,valid?(int)Math.ceil(fraction*Math.clamp(strength,0,15)):0,"distance_attenuated");
    }
    public static Transfer directional(boolean sameFrequency,Vec3 from,Vec3 to,Vec3 normal,double range,int strength) {
        Vec3 delta=from.subtract(to); double distance=delta.length();
        if(!sameFrequency || distance>range || distance<1e-9 || normal.lengthSqr()<1e-9)
            return new Transfer(false,0,"direction_attenuated");
        double dot=delta.normalize().dot(normal.normalize());
        return new Transfer(dot>=0,dot<0?0:(int)Math.ceil(Math.asin(Math.clamp(dot,0,1))*2/Math.PI*Math.clamp(strength,0,15)),"direction_attenuated");
    }
}
