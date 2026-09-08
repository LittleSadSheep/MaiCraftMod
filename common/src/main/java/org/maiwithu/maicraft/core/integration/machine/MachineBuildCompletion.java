// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.Map;

/** Acceptance state shared by the build workflow's transitions and externally reported evidence. */
final class MachineBuildCompletion {
    private final boolean explicitBlueprint;
    private boolean geometryVerified;
    private boolean complete;

    MachineBuildCompletion(boolean explicitBlueprint) { this.explicitBlueprint = explicitBlueprint; }

    /** Explicit structure requests finish here; semantic layouts retain their commissioning obligations. */
    boolean acceptGeometry() {
        geometryVerified = true;
        if (explicitBlueprint) complete = true;
        return complete;
    }

    void acceptCommissioning() {
        if (!geometryVerified || explicitBlueprint)
            throw new IllegalStateException("Only a verified semantic layout has construction commissioning obligations");
        complete = true;
    }

    Map<String, Object> report() {
        return Map.of("machine_geometry_verified", geometryVerified,
                "machine_production_verified", false,
                "construction_complete", complete,
                "configuration_complete", !explicitBlueprint && complete,
                "configuration_status", explicitBlueprint ? "separate_use_phase" : complete ? "complete" : "pending");
    }
}
