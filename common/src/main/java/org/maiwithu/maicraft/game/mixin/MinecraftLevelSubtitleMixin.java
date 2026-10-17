// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientLevel;

import org.maiwithu.maicraft.game.ClientHooks;
import org.maiwithu.maicraft.game.SubtitleFeed;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 进世界与退世界时挂接、解除字幕事件接收端：原版只在监听登记后才会把声音事件转过来。 */
@Mixin(Minecraft.class)
public abstract class MinecraftLevelSubtitleMixin {

    @Inject(method = "setLevel", at = @At("TAIL"))
    private void maicraft$attachSubtitleFeed(ClientLevel level, ReceivingLevelScreen.Reason reason, CallbackInfo ci) {
        SubtitleFeed feed = ClientHooks.subtitleFeed();
        if (feed == null) {
            return;
        }
        Minecraft minecraft = (Minecraft) (Object) this;
        // 世界换成 null 是退世界：先解除，进下一个世界再重新挂。
        if (level != null) {
            feed.attach(minecraft);
        } else {
            feed.detach(minecraft);
        }
    }
}
