// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.explore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryOrigin;

/**
 * 一次探索途中的发现账：同一条发现重复看见合并成一条，远望到了升级成到访、
 * 不降级、不重写（位置留第一次记下的）。账是一次运行内的，长久的记忆归世界记忆。
 *
 * <p>群系按 ID 合并（一片就是一条）；其余按"什么 + 在哪一带"合并：抽样网格每 8 格一列，
 * 一片岩浆湖、一片留意的方块会命中好几列，离已记的同种发现 24 格以内就算同一处，
 * 不然一片岩浆湖就是十几条发现、十几条事件，关注流刷屏。
 */
public final class ExploreFindings {

    /** 同种发现离已记的那条这么近（水平格数）就算同一处；24 格是抽样网格的三步。 */
    static final int SAME_PLACE_BLOCKS = 24;

    /** 记一笔的结算：新发现（要对外说）、升级成到访（记忆要更新，事件不再发）或早已记过。 */
    public enum Entry { NEW, UPGRADED, KNOWN }

    /** 记一笔的结果：结算，加上账里实际留下的那条（升级时位置留第一次记下的）。 */
    public record Result(Entry entry, ExploreFinding effective) {}

    private final Map<String, ExploreFinding> recorded = new LinkedHashMap<>();

    /** 记一条发现，返回这次记的结算。 */
    public Result record(ExploreFinding finding) {
        ExploreFinding existing = finding.kind() == ExploreFinding.Kind.BIOME
                ? recorded.get(finding.mergeKey()) : samePlace(finding);
        if (existing == null) {
            recorded.put(finding.mergeKey(), finding);
            return new Result(Entry.NEW, finding);
        }
        if (existing.origin() == MemoryOrigin.SEEN && finding.origin() == MemoryOrigin.VISITED) {
            // 远望到了之后升级成到访：位置不重写，留第一次看见的地方；账里还按第一次的那条记。
            ExploreFinding upgraded = new ExploreFinding(finding.kind(), finding.targetId(),
                    existing.position(), MemoryOrigin.VISITED);
            recorded.put(existing.mergeKey(), upgraded);
            return new Result(Entry.UPGRADED, upgraded);
        }
        return new Result(Entry.KNOWN, existing);
    }

    /** 已记的同种同 ID、同一维度、水平 24 格以内的那条；没有为空。 */
    private ExploreFinding samePlace(ExploreFinding finding) {
        for (ExploreFinding known : recorded.values()) {
            if (known.kind() != finding.kind() || !known.targetId().equals(finding.targetId())
                    || !known.position().dimension().equals(finding.position().dimension())) {
                continue;
            }
            double dx = known.position().x() - finding.position().x();
            double dz = known.position().z() - finding.position().z();
            if (dx * dx + dz * dz <= (double) SAME_PLACE_BLOCKS * SAME_PLACE_BLOCKS) {
                return known;
            }
        }
        return null;
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
