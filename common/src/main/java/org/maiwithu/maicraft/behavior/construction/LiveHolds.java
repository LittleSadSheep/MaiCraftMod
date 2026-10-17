// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import org.maiwithu.maicraft.behavior.inventory.ClientMovesToMainhand;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 备手的生产实现：要放的方块已经在主手就直接用；在身上就经换手读端分刻换到主手（像真人先搬进快捷栏再选中）；
 * 身上没有就如实说没有，缺的去拿是备料阶段的事。
 */
public final class LiveHolds implements ConstructionSeams.HoldsItem {

    private final ClientMovesToMainhand toMainhand;
    private final Supplier<PlayerContext> context;

    public LiveHolds(ClientMovesToMainhand toMainhand, Supplier<PlayerContext> context) {
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override public ConstructionSeams.HoldPlan hold(String itemId) {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null) {
            return new ConstructionSeams.HoldPlan.Cannot(Problem.of(Problem.Kind.WRONG_TIME, "这一刻还掌握不到角色，备不了手", null));
        }
        var held = current.localPlayer().getMainHandItem();
        if (!held.isEmpty() && BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals(itemId)) {
            return new ConstructionSeams.HoldPlan.Ready();
        }
        if (!toMainhand.carried(itemId)) return new ConstructionSeams.HoldPlan.NotCarried();
        return toMainhand.actionToMainhand(itemId)
                .<ConstructionSeams.HoldPlan>map(ConstructionSeams.HoldPlan.Move::new)
                .orElseGet(() -> new ConstructionSeams.HoldPlan.Cannot(Problem.of(Problem.Kind.STUCK, itemId + " 在身上却换不到主手", null)));
    }
}
