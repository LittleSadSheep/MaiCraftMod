package org.maiwithu.maicraft.core.integration.machine.control;

import net.minecraft.world.phys.Vec3;

public final class WirelessControlRulesTest {
    public static void main(String[] args) {
        var origin=new Vec3(100,64,-20);
        check(WirelessControlRules.plain(true,origin,origin.add(3,0,0),4,15).strength()==15,"use projected world separation");
        check(!WirelessControlRules.plain(true,origin,origin.add(4,0,0),4,15).inRange(),"native plain range is strict");
        check(!WirelessControlRules.plain(false,origin,origin,4,15).inRange(),"reversed/different native frequencies cannot match");
        check(WirelessControlRules.modulating(true,origin.add(12,0,0),origin,8,16,15).strength()==8,"distance attenuation rounds up natively");
        check(WirelessControlRules.directional(true,origin.add(2,0,0),origin,new Vec3(1,0,0),8,15).strength()==15,"front receiver direction");
        check(!WirelessControlRules.directional(true,origin.add(-2,0,0),origin,new Vec3(1,0,0),8,15).inRange(),"rear hemisphere cannot drive receiver");
        System.out.println("WirelessControlRulesTest: passed");
    }
    private static void check(boolean value,String reason) { if(!value) throw new AssertionError(reason); }
}
