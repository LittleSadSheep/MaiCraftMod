// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine.runtime;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/** A production child shares the parent's navigation, serialized request and native child task slot. */
public interface ProductionWork {
    boolean approach(BlockPos position);
    /** Read-only evidence can use the server's bounded observation range without touching the machine. */
    default boolean observe(BlockPos position) { return approach(position); }
    default void stopMovement() {}
    default java.util.Map<String, Long> processingProgress() { return java.util.Map.of(); }
    /** Returns null while pending. A settled result is consumed once; unknown mutations throw without replay. */
    JsonObject request(String operation, JsonObject arguments, boolean mutating);
    TaskState advanceChild(Task task);
    void extendDeadlineTo(long gameTick);
    default void initialSupply(String source, org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Resource resource,
                               long credited, JsonObject snapshot) {}
    default void confirmedSupply(String source, org.maiwithu.maicraft.core.integration.machine.production.ProductionManifest.Resource resource,
                                 String requestId, JsonObject result) {}
}
