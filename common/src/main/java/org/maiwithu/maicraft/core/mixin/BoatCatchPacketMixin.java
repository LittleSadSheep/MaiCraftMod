package org.maiwithu.maicraft.core.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.core.pathing.baritone.landing.BoatCatchWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 玩家发送本次位置包之前，先让 BoatCatchWindow 处理落地前登船时机；这里只接入时序，是否登船由它判断。
@Mixin(LocalPlayer.class)
public abstract class BoatCatchPacketMixin {
    @Inject(method="sendPosition",at=@At("HEAD"))
    private void maicraft$catchBoatBeforeGroundReport(CallbackInfo callback) {
        BoatCatchWindow.beforePositionPacket((LocalPlayer)(Object)this);
    }
}
