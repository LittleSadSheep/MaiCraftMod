// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.survival.AutoSleep;
import org.maiwithu.maicraft.behavior.survival.NightfallNeed;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 生存需求与进世界才建好的能力之间的晚接：睡觉能力、拿东西的引擎在进世界时才按清单建，而夜晚、饥饿这些生存需求
 * 在客户端启动时就登记进控制循环。这里先接住接缝，能力建好之后接上真实现；
 * 没接上时（还没进世界）按"弄不到床""弄不了吃的"对待，夜里照旧按处境封坑或接着干活。
 */
final class SurvivalWiring implements NightfallNeed.ReadsBedAvailability, NightfallNeed.NightRestMoves {

    private NightfallNeed.ReadsBedAvailability beds;
    private NightfallNeed.NightRestMoves nightRest;
    private ItemNeeds food;
    /** 夜里自动去睡的开关：夜晚需求读它，睡觉能力改它，两边拿的是同一份。 */
    private final AutoSleep autoSleep = new AutoSleep();

    AutoSleep autoSleep() {
        return autoSleep;
    }

    /** 拿东西的引擎建好后接上：饿了身上没吃的时经它去弄口粮。 */
    void attachFood(ItemNeeds sources) {
        this.food = Objects.requireNonNull(sources, "sources");
    }

    /** 弄口粮用的拿东西引擎；还没进世界为空。 */
    Optional<ItemNeeds> food() {
        return Optional.ofNullable(food);
    }

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
