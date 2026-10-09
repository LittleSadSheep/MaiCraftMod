// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.mixin;

import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;

import org.maiwithu.maicraft.game.ChatLog;
import org.maiwithu.maicraft.game.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 聊天栏每加一条消息转给记录端：发话的确认方靠它读"这句话出现了没有"。 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMessageMixin {

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;"
            + "Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("TAIL"))
    private void maicraft$recordChatLine(Component message, MessageSignature signature,
            GuiMessageTag tag, CallbackInfo ci) {
        ChatLog log = ClientHooks.chatLog();
        if (log != null && message != null) {
            log.shown(message.getString());
        }
    }
}
