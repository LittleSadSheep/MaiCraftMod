package org.maiwithu.maicraft.core.integration.physics.flight;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.Block;
import org.maiwithu.maicraft.core.scan.TargetIndex;
import org.maiwithu.maicraft.core.task.explore.ClientExplorationMemory;
import org.maiwithu.maicraft.core.task.structure.StructureEvidenceProfiles;
import org.maiwithu.maicraft.core.task.structure.StructureProfileResources;
import org.maiwithu.maicraft.core.task.structure.VisibleStructureEvidence;

/** 巡航按当前维度轮流查可见结构特征，使用已有跑图证据规则，既不读种子也不加载远方区块。 */
final class FlightStructureRecorder implements AutoCloseable {
    private final ClientLevel level;
    private final List<StructureEvidenceProfiles.ResolvedProfile> profiles;
    private final Set<Block> blocks;
    private int next;
    private VisibleStructureEvidence.Incremental survey;
    private boolean closed;
    FlightStructureRecorder(LocalPlayer player) {
        level = player.clientLevel;
        StructureProfileResources.refresh();
        String dimension = level.dimension().location().toString();
        profiles = StructureEvidenceProfiles.registeredIds().stream().sorted().map(StructureEvidenceProfiles::resolve)
                .filter(profile -> profile != null && (profile.profile().dimensions().isEmpty()
                        || profile.profile().dimensions().contains(dimension))).toList();
        blocks = profiles.stream().flatMap(profile -> profile.targetBlocks().stream()).collect(Collectors.toSet());
        TargetIndex.register(level, blocks);
    }
    void tick(LocalPlayer player, ClientExplorationMemory memory) {
        if (closed || player.clientLevel != level || profiles.isEmpty()) return;
        var profile = profiles.get(next % profiles.size());
        // 索引查询共用每刻预算；只匹配眼睛能看到的当前方块，部分扫描不能声称区域内没有结构。
        if (survey == null) survey = new VisibleStructureEvidence.Incremental(profile);
        var scan = survey.tick(player, 128);
        if (scan == null) return;
        survey = null; next++;
        var found = scan.match();
        if (found != null) memory.observeStructure(profile.requestedId(), found.position(), false,
                Map.of("description", profile.profile().evidenceDescription(), "group_counts", found.groupCounts(),
                        "block_counts", found.blockCounts(), "recognition", "visible_block_pattern; natural_generation_not_proven",
                        "observation_mode", "aboard_aircraft"));
    }
    @Override public void close() {
        // 同一次飞行只注销自己登记的引用，不能关闭其他施工或跑图任务仍使用的共享方块索引。
        if (!closed) { closed = true; TargetIndex.unregister(level, blocks); }
    }
}
