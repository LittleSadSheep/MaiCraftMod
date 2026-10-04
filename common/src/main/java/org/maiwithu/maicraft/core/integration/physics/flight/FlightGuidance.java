package org.maiwithu.maicraft.core.integration.physics.flight;

import net.minecraft.world.phys.Vec3;

/** 航路层提供已检查的下一航点与起降通道；飞控不得把未加载的地形当作已检查的空气。 */
public record FlightGuidance(Vec3 waypoint,Vec3 approachPoint,Vec3 touchdown,double landingHeading,
                             double cruiseAltitude,boolean departureClear,boolean corridorObserved,
                             boolean corridorClear,boolean landingSiteObserved,double commandAltitude) {
    public FlightGuidance(Vec3 waypoint,Vec3 approachPoint,Vec3 touchdown,double landingHeading,double cruiseAltitude,
                          boolean departureClear,boolean corridorObserved,boolean corridorClear,boolean landingSiteObserved) {
        this(waypoint,approachPoint,touchdown,landingHeading,cruiseAltitude,departureClear,corridorObserved,corridorClear,landingSiteObserved,cruiseAltitude);
    }
    public FlightGuidance {
        for(Vec3 point:new Vec3[]{waypoint,approachPoint,touchdown})
            if(point==null||!Double.isFinite(point.x)||!Double.isFinite(point.y)||!Double.isFinite(point.z))
                throw new IllegalArgumentException("flight guidance requires finite world positions");
        if(!Double.isFinite(landingHeading)||!Double.isFinite(cruiseAltitude)||!Double.isFinite(commandAltitude))throw new IllegalArgumentException("invalid flight course");
    }
}
