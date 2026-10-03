package org.maiwithu.maicraft.core.integration.physics.balance;

/** 复制车轮原生参数与接地证据；轮胎缺失、悬空和已经施力分别报告，不把静止的车体冒充已获支撑。 */
public record PhysicsWheel(String itemId, double radius, double strength, double steeringRadians,
                           PhysicsVector forward, PhysicsVector side, double driveSign, double rpm,
                           double brake, double friction, double extension, PhysicsVector groundPoint,
                           PhysicsVector groundNormal, String groundStructureId, String contactState,
                           boolean forceApplied,PhysicsVector mount,Double referenceRpm,Brakes referenceBrakes) {
    /** 两端值都是假设工况；启停过渡按同一动力进度松开或压下刹车，不代表实际油门已改变。 */
    public record Brakes(double running,double stopped) {
        public Brakes {
            if(!Double.isFinite(running)||!Double.isFinite(stopped)||running<0||running>1||stopped<0||stopped>1)
                throw new IllegalArgumentException("运行和停车刹车比例必须在 0..1 之间");
        }
        public double at(double power){return stopped+(running-stopped)*Math.clamp(power,0,1);}
    }
    public PhysicsWheel(String itemId,double radius,double strength,double steeringRadians,PhysicsVector forward,PhysicsVector side,
                        double driveSign,double rpm,double brake,double friction,double extension,PhysicsVector groundPoint,
                        PhysicsVector groundNormal,String groundStructureId,String contactState,boolean forceApplied,PhysicsVector mount,Double referenceRpm) {
        this(itemId,radius,strength,steeringRadians,forward,side,driveSign,rpm,brake,friction,extension,groundPoint,groundNormal,
                groundStructureId,contactState,forceApplied,mount,referenceRpm,null);
    }
    public PhysicsWheel(String itemId,double radius,double strength,double steeringRadians,PhysicsVector forward,PhysicsVector side,
                        double driveSign,double rpm,double brake,double friction,double extension,PhysicsVector groundPoint,
                        PhysicsVector groundNormal,String groundStructureId,String contactState,boolean forceApplied) {
        this(itemId,radius,strength,steeringRadians,forward,side,driveSign,rpm,brake,friction,extension,groundPoint,groundNormal,
                groundStructureId,contactState,forceApplied,null,null);
    }
    public PhysicsWheel {
        if(itemId==null||forward==null||side==null||contactState==null
                ||!Double.isFinite(radius+strength+steeringRadians+driveSign+rpm+brake+friction+extension)
                ||radius<0||strength<0||brake<0||brake>1||friction<0||extension<0)
            throw new IllegalArgumentException("车轮观察缺少有效的轮胎、悬挂、动力或接地参数");
        if((groundPoint==null)!=(groundNormal==null))throw new IllegalArgumentException("接地位置和法线必须同时存在");
        if(referenceRpm!=null&&!Double.isFinite(referenceRpm))throw new IllegalArgumentException("车轮参考转速必须有限");
    }
    /** 轮座格与轮胎作用点分别保留，模型修改轮座时不能误把车轮外侧的施力点当成方块位置。 */
    public PhysicsWheel atMount(PhysicsVector localMount) {return copy(localMount,referenceRpm);}
    /** 候选转速单列，实际 rpm 与原生施力证据仍来自原快照，不会被试算改写。 */
    public PhysicsWheel predictAt(double speed) {return copy(mount,speed);}
    /** 单独附加候选刹车设置；brake 字段始终是实测值，默认不擅自补出不存在的刹车控制。 */
    public PhysicsWheel withBrakes(Brakes settings) {
        return new PhysicsWheel(itemId,radius,strength,steeringRadians,forward,side,driveSign,rpm,brake,friction,extension,
                groundPoint,groundNormal,groundStructureId,contactState,forceApplied,mount,referenceRpm,settings);
    }
    private PhysicsWheel copy(PhysicsVector localMount,Double speed) {
        return new PhysicsWheel(itemId,radius,strength,steeringRadians,forward,side,driveSign,rpm,brake,friction,extension,
                groundPoint,groundNormal,groundStructureId,contactState,forceApplied,localMount,speed,referenceBrakes);
    }
}
