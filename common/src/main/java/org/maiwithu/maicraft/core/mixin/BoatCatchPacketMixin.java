package org.maiwithu.maicraft.core.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.pathing.baritone.landing.BoatCatchWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class BoatCatchPacketMixin {
    @Inject(method="sendPosition",at=@At("HEAD"))
    private void maicraft$catchBoatBeforeGroundReport(CallbackInfo callback) {
        BoatCatchWindow.beforePositionPacket((LocalPlayer)(Object)this);
    }
}
