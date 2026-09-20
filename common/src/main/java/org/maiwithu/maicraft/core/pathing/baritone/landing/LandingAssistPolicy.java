package org.maiwithu.maicraft.core.pathing.baritone.landing;

import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import java.util.Map;

/**
 * 保存当前导航允许的落地手段和最近一次救援状态；本轮自动补料失败后先不重复补料，新导航配置时重新允许尝试。
 */
public final class LandingAssistPolicy {
    private static volatile TerrainPermit permit = TerrainPermit.PRESERVE;
    private static volatile boolean automaticSupplyAllowed = true;
    public record Observation(long revision, Map<String,Object> facts) {}
    private static volatile Observation diagnostic = new Observation(0,Map.of("phase", "no landing assist observed"));
    private LandingAssistPolicy() {}
    public static void configure(TerrainPermit next) { permit = next; automaticSupplyAllowed = true; }
    public static boolean automaticSupplyAllowed() { return automaticSupplyAllowed; }
    public static void automaticSupplyFailed() { automaticSupplyAllowed = false; }
    public static TerrainPermit current() { return permit; }
    public static void report(Map<String, Object> value) { diagnostic = new Observation(diagnostic.revision()+1,Map.copyOf(value)); }
    public static Map<String, Object> diagnosticState() { return diagnostic.facts(); }
    public static Observation observation() { return diagnostic; }
}
