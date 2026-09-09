package org.maiwithu.maicraft.client.actor;

/** 给方块变化检查再加一层服务器确认：客户端先显示出来的预测方块，不能单独证明这次放置成功。 */
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

    // 原版调用结束后记住它实际推进到的操作编号；编号没变表示本地可能就拒绝了，没有发包。
    void submitted() {
        sequence = acknowledgements.maicraft$currentBlockSequence();
        submitted = true;
    }

    // 发包后必须等对应编号被服务器确认并完成本地校正，再让里面的检查比较目标方块。
    @Override public Verdict observe(LocalPlayerContext context) {
        if (!submitted || acknowledgements.maicraft$acknowledgedBlockSequence() < sequence
                && sequence != beforeSequence) return Verdict.PENDING;
        Verdict verdict = evidence.observeAcknowledged(context);
        // 没产生新编号时，现场没变可以证明本次没生效；若反而看到成功变化，不能把它归给一个没发出的操作。
        if (sequence == beforeSequence && verdict != Verdict.NOT_APPLIED && verdict != Verdict.PENDING)
            return Verdict.DIVERGED;
        return verdict;
    }

    @Override public int stableTicksRequired() { return 1; }
}
