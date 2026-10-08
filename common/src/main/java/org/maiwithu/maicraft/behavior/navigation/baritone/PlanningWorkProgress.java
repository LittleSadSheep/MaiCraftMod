// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

/** 同一起点、目标和已知地形内只认超过已有检查量的新进展，不能靠重启相同计算反复续命。 */
public final class PlanningWorkProgress {
    private long highWater, revision;
    public long observe(long completed) {
        if (completed > highWater) { revision += completed - highWater; highWater = completed; }
        return revision;
    }
    /** 起点、目标或可用地形真实改变后允许检查新问题；换问题本身不冒充已经取得进展。 */
    public void newScope() { highWater = 0; }
    public long revision() { return revision; }
    public long highWater() { return highWater; }
    public void clear() { highWater = 0; revision = 0; }
}
