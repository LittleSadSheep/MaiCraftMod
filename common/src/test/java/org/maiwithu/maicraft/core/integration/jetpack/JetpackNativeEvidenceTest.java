// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import java.util.HashMap;
import java.util.Map;

/** The production guard requires active native evidence, not merely two client toggle values. */
public final class JetpackNativeEvidenceTest {
    public static void main(String[] args) {
        Map<String, Object> evidence = new HashMap<>(Map.of("scope", "client_only_no_server_ack", "known", true,
                "active_context_present", true, "active_source_matches_chest", true, "native_pose", "UPRIGHT",
                "native_usable", true, "native_source_disabled", false, "abilities_flying", false, "passenger", false));
        check(JetpackNativeAdapter.uprightActive(evidence), "observed upright equipment context was rejected");
        for (String flag : new String[]{"known", "active_context_present", "active_source_matches_chest", "native_usable"}) {
            evidence.put(flag, false);
            check(!JetpackNativeAdapter.uprightActive(evidence), "missing native prerequisite accepted: " + flag);
            evidence.put(flag, true);
        }
        for (String flag : new String[]{"native_source_disabled", "abilities_flying", "passenger"}) {
            evidence.put(flag, true);
            check(!JetpackNativeAdapter.uprightActive(evidence), "native early-return condition accepted: " + flag);
            evidence.put(flag, false);
        }
        evidence.put("native_pose", "SUPERMAN");
        check(!JetpackNativeAdapter.uprightActive(evidence), "elytra/swimming pose became upright fall protection");
        evidence.put("native_pose", "UPRIGHT");
        for (String field : evidence.keySet().toArray(String[]::new)) {
            Object value = evidence.remove(field);
            check(!JetpackNativeAdapter.uprightActive(evidence), "absent evidence accepted: " + field);
            evidence.put(field, value);
        }
        check(!JetpackNativeAdapter.uprightActive(Map.of("active", true, "hover", true)), "toggle predictions alone became active context");
        check(!JetpackNativeAdapter.uprightActive(null), "null observation accepted");
        var missingPlayer = JetpackNativeAdapter.activeEvidence(null);
        check(Boolean.FALSE.equals(missingPlayer.get("known")) && !JetpackNativeAdapter.uprightActive(missingPlayer), "missing player became known active flight");
        check("client_only_no_server_ack".equals(missingPlayer.get("scope")), "observation lost its client-only scope");
        check(!power(0).controllable(), "zero configured descent exceeds the supported landing model");
        check(!power(Double.NaN).controllable() && power(-0.03).controllable(), "descent support bounds changed unexpectedly");
        System.out.println("JetpackNativeEvidenceTest: passed");
    }

    private static JetpackNativeAdapter.Snapshot power(double descent) {
        return new JetpackNativeAdapter.Snapshot(true, "fixture", "create_jetpack:netherite_jetpack", true, true,
                900, 17000, 0.016, 0.32, 0.6, descent, 0.08);
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
