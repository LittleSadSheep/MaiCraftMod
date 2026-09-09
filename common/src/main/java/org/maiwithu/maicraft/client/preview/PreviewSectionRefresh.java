// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import java.util.function.IntSupplier;
import net.minecraft.world.phys.Vec3;

/** Each mesh keeps its own world-check interval and pending work until the frame budget admits it. */
final class PreviewSectionRefresh {
    enum Work { NONE, REBUILD, RESORT }
    private boolean built;
    private int worldHash;
    private long nextCheck;
    private Vec3 sortedFrom;
    private Work pending = Work.REBUILD;

    Work required(long now, Vec3 camera, IntSupplier observedHash) {
        if (!built) return Work.REBUILD;
        if (now >= nextCheck) {
            nextCheck = now + 1000;
            if (observedHash.getAsInt() != worldHash) pending = Work.REBUILD;
            else if (pending == Work.NONE && sortedFrom.distanceToSqr(camera) > 1) pending = Work.RESORT;
        }
        return pending;
    }

    void rebuilt(int hash, Vec3 camera, long now) {
        built = true; worldHash = hash; nextCheck = now + 1000;
        sorted(camera);
    }
    void sorted(Vec3 camera) { sortedFrom = camera; pending = Work.NONE; }
    boolean built() { return built; }
    void reset() { built = false; pending = Work.REBUILD; sortedFrom = null; }
}
