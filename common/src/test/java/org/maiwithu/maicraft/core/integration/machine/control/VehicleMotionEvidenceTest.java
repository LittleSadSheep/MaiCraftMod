package org.maiwithu.maicraft.core.integration.machine.control;

import java.util.Map;
import net.minecraft.world.phys.Vec3;

/** 驾驶结果须剔除校准路程，并正确跨越正负一百八十度的航向边界。 */
public final class VehicleMotionEvidenceTest {
    public static void run() {
        var evidence=new VehicleMotionEvidence();
        evidence.observe(Vec3.ZERO,Math.toRadians(170),new Vec3(0,1,0),false);
        evidence.observe(new Vec3(0,0,2),Math.toRadians(179),new Vec3(0,1,0),true);
        evidence.observe(new Vec3(3,0,6),Math.toRadians(-179),new Vec3(0,Math.cos(.1),Math.sin(.1)),true);
        var facts=evidence.facts();var drive=(Map<?,?>)facts.get("driving_only");
        near(((Number)facts.get("distance_blocks")).doubleValue(),7);
        near(((Number)drive.get("distance_blocks")).doubleValue(),5);
        near(((Number)drive.get("heading_change_degrees")).doubleValue(),2);
        near(((Number)facts.get("maximum_tilt_degrees")).doubleValue(),Math.toDegrees(.1));
        System.out.println("VehicleMotionEvidenceTest: passed");
    }
    private static void near(double actual,double expected){if(Math.abs(actual-expected)>1e-7)throw new AssertionError(actual+" != "+expected);}
}
