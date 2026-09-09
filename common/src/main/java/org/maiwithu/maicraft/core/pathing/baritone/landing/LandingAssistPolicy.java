package org.maiwithu.maicraft.core.pathing.baritone.landing;

import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;

/**
 * 保存当前导航允许的落地手段和最近一次救援状态；本轮自动补料失败后先不重复补料，新导航配置时重新允许尝试。
 */
public final class LandingAssistPolicy {
    private static volatile TerrainPermit permit = TerrainPermit.PRESERVE;
    private static volatile boolean automaticSupplyAllowed = true;
    public record Observation(long revision, java.util.Map<String,Object> facts) {}
    private static volatile Observation diagnostic = new Observation(0,java.util.Map.of("phase", "no landing assist observed"));
    private LandingAssistPolicy() {}
    public static void configure(TerrainPermit next) { permit = next; automaticSupplyAllowed = true; }
    public static boolean automaticSupplyAllowed() { return automaticSupplyAllowed; }
    public static void automaticSupplyFailed() { automaticSupplyAllowed = false; }
    public static TerrainPermit current() { return permit; }
    public static void report(java.util.Map<String, Object> value) { diagnostic = new Observation(diagnostic.revision()+1,java.util.Map.copyOf(value)); }
    public static java.util.Map<String, Object> diagnosticState() { return diagnostic.facts(); }
    public static Observation observation() { return diagnostic; }
}
