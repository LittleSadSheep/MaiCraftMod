package org.maiwithu.maicraft.core.pathing.baritone.landing;

import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;

/** Captured into each calculation before worker execution, like the embedded terrain settings. */
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
