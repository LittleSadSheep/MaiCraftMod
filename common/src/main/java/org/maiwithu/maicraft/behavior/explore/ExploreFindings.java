// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryOrigin;

/**
 * 一次探索途中的发现账：同一条发现重复看见合并成一条，远望到了升级成到访、
 * 不降级、不重写（位置留第一次记下的）。账是一次运行内的，长久的记忆归世界记忆。
 */
public final class ExploreFindings {

    /** 记一笔的结算：新发现（要对外说）、升级成到访（记忆要更新，事件不再发）或早已记过。 */
    public enum Entry { NEW, UPGRADED, KNOWN }

    /** 记一笔的结果：结算，加上账里实际留下的那条（升级时位置留第一次记下的）。 */
    public record Result(Entry entry, ExploreFinding effective) {}

    private final Map<String, ExploreFinding> recorded = new LinkedHashMap<>();

    /** 记一条发现，返回这次记的结算。 */
    public Result record(ExploreFinding finding) {
        ExploreFinding existing = recorded.get(finding.mergeKey());
        if (existing == null) {
            recorded.put(finding.mergeKey(), finding);
            return new Result(Entry.NEW, finding);
        }
        if (existing.origin() == MemoryOrigin.SEEN && finding.origin() == MemoryOrigin.VISITED) {
            // 远望到了之后升级成到访：位置不重写，留第一次看见的地方。
            ExploreFinding upgraded = new ExploreFinding(finding.kind(), finding.targetId(),
                    existing.position(), MemoryOrigin.VISITED);
            recorded.put(finding.mergeKey(), upgraded);
            return new Result(Entry.UPGRADED, upgraded);
        }
        return new Result(Entry.KNOWN, existing);
    }

    /** 全部发现，按第一次记下的先后。 */
    public List<ExploreFinding> all() {
        return List.copyOf(recorded.values());
    }

    /** 有没有过某一种类的发现（定向探索受阻时区分"没见过候选"与"见过没核实"）。 */
    public boolean hasAny(ExploreFinding.Kind kind) {
        return recorded.values().stream().anyMatch(finding -> finding.kind() == kind);
    }
}
