// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import java.util.function.IntSupplier;
import net.minecraft.world.phys.Vec3;

/**
 * 每个小分区记住何时检查现场、上次方块摘要和相机位置；本帧没轮到的刷新要求会留到下一帧。
 */
final class PreviewSectionRefresh {
    enum Work { NONE, REBUILD, RESORT }
    private boolean built;
    private int worldHash;
    private long nextCheck;
    private Vec3 sortedFrom;
    private Work pending = Work.REBUILD;

    // 未画过的必须重建；之后每隔一秒查一次，世界变化优先重建，只移动相机超过一格则重排透明面的顺序。
    Work required(long now, Vec3 camera, IntSupplier observedHash) {
        if (!built) return Work.REBUILD;
        if (now >= nextCheck) {
            nextCheck = now + 1000;
            if (observedHash.getAsInt() != worldHash) pending = Work.REBUILD;
            else if (pending == Work.NONE && sortedFrom.distanceToSqr(camera) > 1) pending = Work.RESORT;
        }
        return pending;
    }

    // 成功重建后保存本次观察和相机位置，并把下次现场检查推迟一秒。
    void rebuilt(int hash, Vec3 camera, long now) {
        built = true; worldHash = hash; nextCheck = now + 1000;
        sorted(camera);
    }
    void sorted(Vec3 camera) { sortedFrom = camera; pending = Work.NONE; }
    boolean built() { return built; }
    // 释放旧图形后重新标为必须重建，不能把空缓存当作已经可绘制。
    void reset() { built = false; pending = Work.REBUILD; sortedFrom = null; }
}
