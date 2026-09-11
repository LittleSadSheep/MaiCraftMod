// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.inventory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.server.machine.ServerAccess;

/** AE2 standalone submissions return no requester link; bind the unique link created inside the native CPU. */
final class Ae2SubmissionTracking {
    private static final String CPU = "appeng.me.cluster.implementations.CraftingCPUCluster";
    private static final String LOGIC = "appeng.crafting.execution.CraftingCpuLogic";
    private record Before(Object logic, UUID linkId) {}
    private final List<Before> before;
    private final Object output;

    private Ae2SubmissionTracking(List<Before> before, Object output) { this.before = List.copyOf(before); this.output = output; }

    static Ae2SubmissionTracking before(Ae2Access access, Object plan) {
        Collection<?> cpus = (Collection<?>) NativeApi.call(Ae2Keys.crafting(access), Ae2Keys.CRAFTING, "getCpus");
        if (cpus.size() > 128) throw ServerAccess.denied("crafting_cpu_limit", "Native CPU tracking exceeds the bounded network observation");
        List<Before> before = new ArrayList<>();
        for (Object cpu : cpus) {
            if (!NativeApi.is(cpu, CPU)) throw ServerAccess.denied("unsupported_crafting_tracking", "Native CPU link tracking is unavailable");
            Object logic = NativeApi.field(cpu, CPU, "craftingLogic");
            Object link = NativeApi.call(logic, LOGIC, "getLastLink");
            before.add(new Before(logic, id(link)));
        }
        return new Ae2SubmissionTracking(before, NativeApi.call(plan, Ae2Crafting.PLAN, "finalOutput"));
    }

    Object completedSubmission(Object returnedLink) {
        if (returnedLink != null) return returnedLink;
        Object found = null;
        // This read immediately follows the single synchronous native submit, before another server tick.
        for (Before snapshot : before) {
            Object link = NativeApi.call(snapshot.logic(), LOGIC, "getLastLink");
            if (link == null || Objects.equals(snapshot.linkId(), id(link))
                    || !NativeApi.truth(NativeApi.call(link, Ae2Crafting.LINK, "isStandalone"))
                    || !Objects.equals(output, NativeApi.call(snapshot.logic(), LOGIC, "getFinalJobOutput"))) continue;
            if (found != null && !id(found).equals(id(link))) return null;
            found = link;
        }
        return found;
    }

    private static UUID id(Object link) {
        return link == null ? null : (UUID) NativeApi.call(link, Ae2Crafting.LINK, "getCraftingID");
    }

    Object logicFor(Object link) {
        if (link == null) return null;
        UUID expected = id(link);
        for (Before snapshot : before) {
            Object current = NativeApi.call(snapshot.logic(), LOGIC, "getLastLink");
            if (expected.equals(id(current))) return snapshot.logic();
        }
        return null;
    }
}
