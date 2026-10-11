// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Objects;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.FeedReport;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.inventory.TossesOnto;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 把料扔进机器的投料动作：工作盆、石磨、朝上的锯、粉碎轮中间都只收落到上面的东西，右键放不进去。
 * 扔的过程交给"往一格里扔"（换手 → 走到旁边 → 贴近 → 按抛物线瞄准 → 丢 → 看落点）；
 * 做完交出实情：进去了几件、落在上面还没被收走的、落到别处的各在哪，machine_run 按实情记投料。
 */
public final class TossFeed implements Action, FeedReport {

    private final TossesOnto tossing;

    /**
     * @param cell        收料的那一格
     * @param catchHeight 东西落到离那一格底面多高就被接住：盆沿、锯面、石磨外圈台面的高度
     */
    public TossFeed(PlayerServices services, String itemId, int count, BlockPos cell, double catchHeight,
            Permissions permissions) {
        Objects.requireNonNull(services, "services");
        this.tossing = new TossesOnto(itemId, count, cell, catchHeight, permissions, services.approach(),
                services.toMainhand(), FirstPersonScene::of);
    }

    @Override public ActionStatus tick(TickContext context) {
        return tossing.tick(context);
    }

    @Override public int delivered() {
        return tossing.delivered();
    }

    @Override public List<String> notes() {
        return tossing.notes();
    }

    @Override public void pause() {
        tossing.pause();
    }

    @Override public void close() {
        tossing.close();
    }

    @Override public Interruptibility interruptibility() {
        return tossing.interruptibility();
    }

    @Override public String describe() {
        return tossing.describe();
    }
}
