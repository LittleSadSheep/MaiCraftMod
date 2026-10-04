package org.maiwithu.maicraft.core.integration.physics.flight;

/** 设计者声明飞行速度和操纵范围，飞控在此范围内调节原生输入；这些数值本身不证明飞机能够飞。 */
public record FlightEnvelope(Kind kind,double takeoffSpeed,double cruiseSpeed,double maximumBank,
                             double climbPitch,double approachPitch,double climbRate,double descentRate) {
    public enum Kind { FIXED_WING, AIRSHIP }
    public FlightEnvelope {
        if(kind==null)throw new IllegalArgumentException("aircraft kind is required");
        for(double value:new double[]{takeoffSpeed,cruiseSpeed,maximumBank,climbPitch,approachPitch,climbRate,descentRate})
            if(!Double.isFinite(value))throw new IllegalArgumentException("flight envelope must be finite");
        if(takeoffSpeed<=0||cruiseSpeed<=takeoffSpeed||cruiseSpeed>128||maximumBank<=0||maximumBank>Math.toRadians(60)
                ||climbPitch<=0||climbPitch>Math.toRadians(30)||approachPitch<=0||approachPitch>Math.toRadians(20)
                ||climbRate<=0||climbRate>16||descentRate<=0||descentRate>8)
            throw new IllegalArgumentException("invalid flight control ranges");
    }
    public static FlightEnvelope fixedWing(){return new FlightEnvelope(Kind.FIXED_WING,8,16,Math.toRadians(30),Math.toRadians(12),Math.toRadians(6),3,2);}
    public static FlightEnvelope airship(){return new FlightEnvelope(Kind.AIRSHIP,1,6,Math.toRadians(12),Math.toRadians(8),Math.toRadians(5),2,1);}
}
