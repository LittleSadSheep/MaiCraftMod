// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ponder;

import java.util.List;

/**
 * 提供教程目录、旁白编译和可选结构回放三种访问方式；列目录不表示已经运行所有教程。
 */
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
