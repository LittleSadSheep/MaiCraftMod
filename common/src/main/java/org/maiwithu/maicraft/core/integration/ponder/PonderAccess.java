// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ponder;

import java.util.List;

/** Optional Ponder integration. Enumeration never compiles or plays storyboards. */
public interface PonderAccess {
    record Entry(String key, String component, String schematic, List<String> tags, Object nativeEntry) {
        public Entry { tags = List.copyOf(tags); }
    }
    record Snapshot(String status, String detail, List<Entry> entries) {
        public Snapshot { entries = List.copyOf(entries); }
    }
    Snapshot snapshot();
    PonderTranscript compile(Entry entry);
    default PonderReplaySession replay(Entry entry) { throw new IllegalStateException("Ponder replay is unsupported by this provider"); }
}
