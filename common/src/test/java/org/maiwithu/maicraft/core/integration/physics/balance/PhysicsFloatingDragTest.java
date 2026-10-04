package org.maiwithu.maicraft.core.integration.physics.balance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 用隔离飞艇的真实原生样本核对阻力和未画成箭头的力偶，再检查航速变化后的重算。 */
public final class PhysicsFloatingDragTest {
    public static void run() {
        var cells=new ArrayList<PhysicsFloatingDrag.Cell>();
        for(int x=-4;x<=0;x++)for(int z=-4;z<=0;z++) {
            cells.add(new PhysicsFloatingDrag.Cell(v(x+.5,.5,z+.5),.33));
            if(x==-4||x==0||z==-4||z==0)for(int y=-3;y<0;y++)
                cells.add(new PhysicsFloatingDrag.Cell(v(x+.5,y+.5,z+.5),.33));
        }
        var cloud=PhysicsFloatingDrag.cloud(cells);
        near(cloud.scale(),24.09);near(cloud.center().y(),-.815068493150685);
        var material=new PhysicsFloatingDrag(cloud.scale(),.9222340386240373,1,1,false,.025,cloud.spread());
        var actual=material.force(v(-.5525447632668924,-.4654582207033025,.1238199883906774),
                v(.12636514217186826,.28222582554243836,.12922591288393298),
                v(.009880601640175818,-10.999995553500177,.0004434947222963668));
        check(actual.force(),v(12.27828918469915,7.431589633347996,-2.750744084600547));
        check(actual.couple(),v(-11.694452281620187,-33.35465938986698,-11.959202085291423));
        // 单格也有原生体积阻尼；水平与竖直系数不能在换姿态时混成一个标量。
        var single=PhysicsFloatingDrag.cloud(List.of(new PhysicsFloatingDrag.Cell(PhysicsVector.ZERO,1)));
        var directional=new PhysicsFloatingDrag(1,1,2,4,false,.025,single.spread());
        var response=directional.force(v(2,3,4),v(1,2,3),v(0,-11,0));
        check(response.force(),v(-4,-4*(3+11*.025/2.1),-8));check(response.couple(),v(-1,-4.0/3,-3));
        near(directional.force(v(2,3,4),PhysicsVector.ZERO,PhysicsVector.ZERO).force().x(),-2);
        var baseline=directional.force(PhysicsVector.ZERO,PhysicsVector.ZERO,v(0,-10,0));
        var load=new PhysicsBody.Load("skin","floating_drag_delta",PhysicsVector.ZERO,baseline.force().scale(-1),
                baseline.couple().scale(-1),PhysicsBody.Frame.BODY,false,0,0,null,null,directional);
        var body=PhysicsBalanceRegression.vessel(List.of(load));
        var still=PhysicsWrench.evaluate(body,body.rotation(),body.position(),PhysicsVector.ZERO,PhysicsVector.ZERO,Map.of(),0,false);
        var moving=PhysicsWrench.evaluate(body,body.rotation(),body.position(),v(2,0,0),PhysicsVector.ZERO,Map.of(),0,false);
        near(still.force().x(),0);near(moving.force().x(),-4);near(moving.force().y(),still.force().y());
        System.out.println("PhysicsFloatingDragTest: native airship force/couple and velocity-dependent drag passed");
    }
    private static PhysicsVector v(double x,double y,double z){return new PhysicsVector(x,y,z);}
    private static void check(PhysicsVector actual,PhysicsVector expected){near(actual.x(),expected.x());near(actual.y(),expected.y());near(actual.z(),expected.z());}
    private static void near(double actual,double expected){if(Math.abs(actual-expected)>1e-5)throw new AssertionError(actual+" != "+expected);}
}
