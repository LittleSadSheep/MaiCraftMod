package org.maiwithu.maicraft.core.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.maiwithu.maicraft.client.actor.BlockUseAcknowledgement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 把原版每个世界的预测编号接给动作确认层。此处只记录编号，不自行改变方块。
@Mixin(ClientLevel.class)
public abstract class BlockUseAcknowledgementMixin implements BlockUseAcknowledgement {
    @Shadow @Final private BlockStatePredictionHandler blockStatePredictionHandler;
    @Unique private int maicraft$acknowledgedSequence = -1;

    @Override public int maicraft$currentBlockSequence() { return blockStatePredictionHandler.currentSequence(); }
    @Override public int maicraft$acknowledgedBlockSequence() { return maicraft$acknowledgedSequence; }

    // 等原版处理完服务器确认、把本地预测校正后才登记；用最大值保持已确认编号不倒退。
    @Inject(method = "handleBlockChangedAck", at = @At("TAIL"))
    private void maicraft$afterReconciliation(int sequence, CallbackInfo ci) {
        maicraft$acknowledgedSequence = Math.max(maicraft$acknowledgedSequence, sequence);
    }
}
