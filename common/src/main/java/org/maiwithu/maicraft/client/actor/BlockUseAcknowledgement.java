package org.maiwithu.maicraft.client.actor;

/** Per-client-level native prediction sequence, acknowledged only after server reconciliation. */
public interface BlockUseAcknowledgement {
    int maicraft$currentBlockSequence();
    int maicraft$acknowledgedBlockSequence();
}
