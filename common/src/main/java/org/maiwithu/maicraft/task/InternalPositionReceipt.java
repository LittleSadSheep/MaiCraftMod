// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.task;

/**
 * Internal semantic provenance carried by a child record, never serialized in TaskResult.
 * This lets a later prior_result resolve a verified place without showing coordinates to the LLM.
 */
public interface InternalPositionReceipt {
    record Position(int x, int y, int z, String dimension) {}

    Position internalVerifiedPosition();
}
