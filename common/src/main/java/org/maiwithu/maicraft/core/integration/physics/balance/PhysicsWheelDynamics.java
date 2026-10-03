package org.maiwithu.maicraft.core.integration.physics.balance;

/** 在隔离刚体上复算 Offroad 悬挂、侧滑、驱动与刹车；地面暂使用原生选中的支撑平面。 */
public final class PhysicsWheelDynamics {
    private static final PhysicsVector UP=new PhysicsVector(0,1,0);
    private static final double REST=.65;
    private PhysicsWheelDynamics() {}
    public static PhysicsVector force(PhysicsBody body,PhysicsVector point,PhysicsWheel wheel,PhysicsBody.Rotation attitude,
                                      PhysicsVector position,PhysicsVector velocity,PhysicsVector omega,double driveRpm) {
        return force(body,point,wheel,attitude,position,velocity,omega,driveRpm,wheel.brake());
    }
    public static PhysicsVector force(PhysicsBody body,PhysicsVector point,PhysicsWheel wheel,PhysicsBody.Rotation attitude,
                                      PhysicsVector position,PhysicsVector velocity,PhysicsVector omega,double driveRpm,double brake) {
        if(!Double.isFinite(driveRpm))throw new IllegalArgumentException("车轮试算转速必须有限");
        if(!Double.isFinite(brake)||brake<0||brake>1)throw new IllegalArgumentException("车轮试算刹车比例必须在 0..1 之间");
        if(wheel.radius()<=0||wheel.groundPoint()==null)return PhysicsVector.ZERO;
        PhysicsVector arm=attitude.world(point.subtract(body.center()));
        PhysicsVector worldPoint=position.add(arm),normal=wheel.groundNormal();
        double alignment=attitude.world(UP).dot(normal);
        // 原生轮下射线只接受朝向悬挂上方的表面；车轮悬空、越过射程或翻转后不能凭旧地面持续托住车体。
        if(alignment<.5)return PhysicsVector.ZERO;
        double extension=worldPoint.subtract(wheel.groundPoint()).dot(normal)/alignment;
        if(extension<=1e-5||extension>5||extension>REST+wheel.radius()+.25)return PhysicsVector.ZERO;
        var localVelocity=attitude.local(velocity.add(omega.cross(arm)));
        PhysicsVector cross=point.subtract(body.center()).cross(UP);
        double inverseNormalMass=1/body.mass()+cross.dot(PhysicsVector.of(body.inertia().matrix().invert().transform(cross.mutable())));
        // 配重改变质量和惯量后，悬挂有效质量必须一起更新；原生系数按 min(法向质量, 旋钮强度) 缩放。
        double effective=Math.min(1/inverseNormalMass,wheel.strength())*10;
        double springLength=Math.clamp(extension+REST/6-wheel.radius(),0,REST);
        double spring=(REST-springLength)*effective*40-localVelocity.y()*effective;
        PhysicsVector force=attitude.local(normal).scale(spring);
        double surface=Math.min(wheel.friction(),1),strength=effective*2;
        double rolling=-(.075+brake*.3)*surface*strength*localVelocity.dot(wheel.forward());
        double driving=driveRpm*wheel.driveSign()*(1-brake)*surface*1.75;
        // 停动力只收回驱动项，悬挂和滚阻继续存在；原生刹车同时削弱驱动并增大滚动阻力。
        return force.add(wheel.forward().scale(rolling+driving))
                .add(wheel.side().scale(-.6*wheel.friction()*strength*localVelocity.dot(wheel.side())));
    }
}
