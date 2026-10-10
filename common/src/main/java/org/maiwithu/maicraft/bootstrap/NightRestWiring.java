// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.Objects;

import org.maiwithu.maicraft.behavior.survival.NightfallNeed;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 夜间休息与睡床判断的晚接：睡觉能力在进世界时才按清单登记，而夜晚这项生存需求
 * 在客户端启动时就登记进控制循环。这里先接住两个接缝，能力登记好之后接上真实现；
 * 没接上时（还没进世界）按"弄不到床"对待，夜里照旧按处境封坑或接着干活。
 */
final class NightRestWiring implements NightfallNeed.ReadsBedAvailability, NightfallNeed.NightRestMoves {

    private NightfallNeed.ReadsBedAvailability beds;
    private NightfallNeed.NightRestMoves nightRest;

    /** 睡觉能力登记好后接上：找床读端与夜间休息的落地都来自它。 */
    void attach(NightfallNeed.ReadsBedAvailability beds, NightfallNeed.NightRestMoves nightRest) {
        this.beds = Objects.requireNonNull(beds, "beds");
        this.nightRest = Objects.requireNonNull(nightRest, "nightRest");
    }

    @Override
    public boolean bedReady(TickContext context) {
        return beds != null && beds.bedReady(context);
    }

    @Override
    public Task nightRest(TickContext context) {
        return nightRest == null ? null : nightRest.nightRest(context);
    }
}
