package org.maiwithu.maicraft.core.task.structure;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** 共用识别器必须同时满足聚集、各组数量与总数；不能把远处散块或重复组计数拼成结构。 */
public final class VisibleStructureEvidenceTest {
    public static void run() {
        var profile = new StructureEvidenceProfiles.Profile("test:visible", Set.of(), 3, 3, "可见石材与装饰",
                List.of(new StructureEvidenceProfiles.Group("stone", 2, List.of("minecraft:stone")),
                        new StructureEvidenceProfiles.Group("fittings", 1, List.of("minecraft:gold_block"))));
        var resolved = new StructureEvidenceProfiles.ResolvedProfile("test:visible", profile,
                List.of(new StructureEvidenceProfiles.ResolvedGroup("stone", 2, Set.of(Blocks.STONE)),
                        new StructureEvidenceProfiles.ResolvedGroup("fittings", 1, Set.of(Blocks.GOLD_BLOCK))),
                Set.of(Blocks.STONE, Blocks.GOLD_BLOCK));
        var a = new BlockPos(0, 80, 0); var b = a.east(); var c = a.south(); var far = a.east(12);
        var blocks = Map.of(a, Blocks.STONE, b, Blocks.STONE, c, Blocks.GOLD_BLOCK, far, Blocks.GOLD_BLOCK);
        var match = VisibleStructureEvidence.match(resolved, List.of(a, b, c), blocks::get, at -> true);
        check(match != null && match.totalBlocks() == 3 && match.groupCounts().get("stone") == 2, "同一可见组合应保留真实计数");
        check(VisibleStructureEvidence.match(resolved, List.of(a, b), blocks::get, at -> true) == null, "缺少装饰组不能当成结构");
        check(VisibleStructureEvidence.match(resolved, List.of(a, b, far), blocks::get, at -> true) == null, "不能跨越聚集半径拼凑证据");
        check(VisibleStructureEvidence.match(resolved, List.of(a, b, c), blocks::get, at -> false) == null, "被扇区或拒绝列表排除的地点不能返回");
        check(VisibleStructureEvidence.match(resolved, List.of(a, b, c),
                at -> at.equals(c) ? Blocks.AIR : blocks.get(at), at -> true) == null, "分刻观察中已经消失的关键方块不能计入证据");
        System.out.println("VisibleStructureEvidenceTest: passed");
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
