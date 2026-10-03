package org.maiwithu.maicraft.core.integration.physics.balance;

/** 复制车轮原生参数与接地证据；轮胎缺失、悬空和已经施力分别报告，不把静止的车体冒充已获支撑。 */
public record PhysicsWheel(String itemId, double radius, double strength, double steeringRadians,
                           PhysicsVector forward, PhysicsVector side, double driveSign, double rpm,
                           double brake, double friction, double extension, PhysicsVector groundPoint,
                           PhysicsVector groundNormal, String groundStructureId, String contactState,
                           boolean forceApplied) {
    public PhysicsWheel {
        if(itemId==null||forward==null||side==null||contactState==null
                ||!Double.isFinite(radius+strength+steeringRadians+driveSign+rpm+brake+friction+extension)
                ||radius<0||strength<0||brake<0||brake>1||friction<0||extension<0)
            throw new IllegalArgumentException("车轮观察缺少有效的轮胎、悬挂、动力或接地参数");
        if((groundPoint==null)!=(groundNormal==null))throw new IllegalArgumentException("接地位置和法线必须同时存在");
    }
}
