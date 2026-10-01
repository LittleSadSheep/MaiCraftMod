// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import java.util.Locale;
import java.util.Set;

/** 位置是现场观察证据；展示或保存位置不会授权点击、拆除或重放执行器路线。 */
public final class ObservedGameEvidence {
    private ObservedGameEvidence() {}
    public static boolean spatialField(String raw) {
        String key = raw.toLowerCase(Locale.ROOT);
        return Set.of("x", "y", "z", "position", "center", "location", "destination", "bounds", "origin",
                "site_min", "site_max", "remaining_scaffolds").contains(key)
                || key.endsWith("_position") || key.endsWith("_center") || key.endsWith("_location")
                || key.endsWith("_destination") || key.endsWith("_bounds")
                || key.endsWith("_x") || key.endsWith("_y") || key.endsWith("_z");
    }
}
