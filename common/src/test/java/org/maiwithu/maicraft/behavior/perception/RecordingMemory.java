// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/** 世界记忆替身：把感知写进来的看见记录一条条收着，供断言。 */
final class RecordingMemory implements RemembersSightings {

    record Call(String what, WorldPosition position, String blockType, List<String> roughlyThere, Instant when) {}

    final List<Call> calls = new ArrayList<>();

    @Override
    public void containerSeen(WorldPosition position, String blockType, Instant when) {
        calls.add(new Call("container", position, blockType, List.of(), when));
    }

    @Override
    public void workstationSeen(WorldPosition position, String blockType, Instant when) {
        calls.add(new Call("workstation", position, blockType, List.of(), when));
    }

    @Override
    public void siteSeen(WorldPosition position, List<String> roughlyThere, Instant when) {
        calls.add(new Call("site", position, null, List.copyOf(roughlyThere), when));
    }
}
