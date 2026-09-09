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

@Mixin(ClientLevel.class)
public abstract class BlockUseAcknowledgementMixin implements BlockUseAcknowledgement {
    @Shadow @Final private BlockStatePredictionHandler blockStatePredictionHandler;
    @Unique private int maicraft$acknowledgedSequence = -1;

    @Override public int maicraft$currentBlockSequence() { return blockStatePredictionHandler.currentSequence(); }
    @Override public int maicraft$acknowledgedBlockSequence() { return maicraft$acknowledgedSequence; }

    @Inject(method = "handleBlockChangedAck", at = @At("TAIL"))
    private void maicraft$afterReconciliation(int sequence, CallbackInfo ci) {
        maicraft$acknowledgedSequence = Math.max(maicraft$acknowledgedSequence, sequence);
    }
}
