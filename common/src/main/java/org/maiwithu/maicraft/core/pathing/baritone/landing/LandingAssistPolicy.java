package org.maiwithu.maicraft.core.pathing.baritone.landing;

import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;

/** Captured into each calculation before worker execution, like the embedded terrain settings. */
public final class LandingAssistPolicy {
    private static volatile TerrainPermit permit = TerrainPermit.PRESERVE;
    private static volatile java.util.Map<String, Object> diagnostic = java.util.Map.of("phase", "no landing assist observed");
    private LandingAssistPolicy() {}
    public static void configure(TerrainPermit next) { permit = next; }
    public static TerrainPermit current() { return permit; }
    public static void report(java.util.Map<String, Object> value) { diagnostic = java.util.Map.copyOf(value); }
    public static java.util.Map<String, Object> diagnosticState() { return diagnostic; }
}
