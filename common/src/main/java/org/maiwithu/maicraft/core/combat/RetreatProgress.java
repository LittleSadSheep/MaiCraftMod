package org.maiwithu.maicraft.core.combat;

import net.minecraft.world.phys.Vec3;

/** 保持本次撤离承诺；路线计算不算逃生进展，身体移动两格才重置失败计数，真正脱离后才结束承诺。 */
public final class RetreatProgress {
    private Vec3 anchor;
    private int failures;
    private boolean committed;

    // 一旦决定脱离，就持续到远处威胁也消失；移动和自然回血只更新进展，不撤销这次撤离。
    public void commit() { committed = true; }
    public boolean committed() { return committed; }
    public void complete() { committed = false; anchor = null; failures = 0; }

    public void observe(Vec3 position) {
        if (anchor == null || anchor.distanceToSqr(position) >= 4.0) {
            anchor = position;
            failures = 0;
        }
    }

    public void failed() { failures++; }
    public int failures() { return failures; }
}
