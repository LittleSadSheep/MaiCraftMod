package org.maiwithu.maicraft.client.actor;

/** A predicted block state cannot finish a placement before its own server acknowledgement. */
final class BlockUseConfirmation implements NativeConfirmation {
    private final NativeConfirmation evidence;
    private final BlockUseAcknowledgement acknowledgements;
    private final int beforeSequence;
    private int sequence;
    private boolean submitted;

    BlockUseConfirmation(NativeConfirmation evidence, BlockUseAcknowledgement acknowledgements) {
        this.evidence = evidence;
        this.acknowledgements = acknowledgements;
        beforeSequence = acknowledgements.maicraft$currentBlockSequence();
    }

    void submitted() {
        sequence = acknowledgements.maicraft$currentBlockSequence();
        submitted = true;
    }

    @Override public Verdict observe(LocalPlayerContext context) {
        if (!submitted || acknowledgements.maicraft$acknowledgedBlockSequence() < sequence
                && sequence != beforeSequence) return Verdict.PENDING;
        Verdict verdict = evidence.observeAcknowledged(context);
        // A locally blocked use may not send a packet. Only unchanged observed cells prove refusal.
        if (sequence == beforeSequence && verdict != Verdict.NOT_APPLIED && verdict != Verdict.PENDING)
            return Verdict.DIVERGED;
        return verdict;
    }

    @Override public int stableTicksRequired() { return 1; }
}
