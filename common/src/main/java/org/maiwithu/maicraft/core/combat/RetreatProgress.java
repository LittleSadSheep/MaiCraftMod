package org.maiwithu.maicraft.core.combat;

import net.minecraft.world.phys.Vec3;

/** 路线计算中不代表逃离成功；累计失败只在身体确实移动两格后重置。 */
public final class RetreatProgress {
    private Vec3 anchor;
    private int failures;

    public void observe(Vec3 position) {
        if (anchor == null || anchor.distanceToSqr(position) >= 4.0) {
            anchor = position;
            failures = 0;
        }
    }

    public void failed() { failures++; }
    public int failures() { return failures; }
}
