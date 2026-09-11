package org.maiwithu.maicraft.core.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import org.maiwithu.maicraft.core.combat.CombatThreats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 原版完成伤害包的客户端线程处理后，只记录本地玩家的攻击来源。 */
@Mixin(ClientPacketListener.class)
public abstract class ClientDamageEventMixin {
    @Inject(method = "handleDamageEvent", at = @At("TAIL"))
    private void maicraft$observeDamage(ClientboundDamageEventPacket packet, CallbackInfo callback) {
        CombatThreats.damaged(Minecraft.getInstance().player, packet);
    }
}
