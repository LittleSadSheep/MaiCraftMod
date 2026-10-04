package org.maiwithu.maicraft.core.integration.physics.flight;

/** 归一化的动力、舵面、升降与刹车意图；执行器将其映射为已配频的原生控制，不直接施加力或位移。 */
public record FlightCommand(double power,double pitch,double bank,double yaw,double lift,boolean brake) {
    public FlightCommand {
        for(double value:new double[]{power,pitch,bank,yaw,lift})if(!Double.isFinite(value))
            throw new IllegalArgumentException("flight command must be finite");
        power=Math.clamp(power,0,1);pitch=Math.clamp(pitch,-1,1);bank=Math.clamp(bank,-1,1);
        yaw=Math.clamp(yaw,-1,1);lift=Math.clamp(lift,0,1);
    }
    public static FlightCommand parked(){return new FlightCommand(0,0,0,0,0,true);}
}
