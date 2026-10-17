// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.createjetpack;

import java.util.Objects;

import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 把喷气背包的飞行、悬停两个开关拨到要的样子，读回一致才算好；按满三次还不对如实交回。
 * 走路前关掉误开的、飞上高处前打开，都是这一个动作。
 */
final class SwitchesTo implements Action {

    private final JetpackSwitches switches;
    private final String what;

    SwitchesTo(CreateJetpackCompat compat, boolean active, boolean hover, String what) {
        this.switches = new JetpackSwitches(Objects.requireNonNull(compat, "compat"));
        this.what = Objects.requireNonNull(what, "what");
        switches.want(active, hover);
    }

    @Override public ActionStatus tick(TickContext context) {
        LocalPlayer player = context.player().localPlayer();
        if (player == null) return ActionStatus.failed(Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在，" + what + "没做成"));
        return switch (switches.tick(player, context.player().clientTick())) {
            case DONE -> ActionStatus.done();
            case WORKING -> ActionStatus.running();
            case UNCONFIRMED -> ActionStatus.failed(Problem.of(Problem.Kind.STUCK, what + "：" + switches.unclear()));
        };
    }

    @Override public String describe() {
        return what;
    }
}
