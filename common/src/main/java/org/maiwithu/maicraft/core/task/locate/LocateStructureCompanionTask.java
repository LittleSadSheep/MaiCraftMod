package org.maiwithu.maicraft.core.task.locate;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 旧的结构定位入口：检查输入名字后，固定返回客户端没有权威结构位置，当前不会真正扫描或找到结构。
 * 按可见方块和生物寻找线索的工作在 PhysicalStructureSearchCompanionTask 中；这两条入口目前没有接起来。
 */
public final class LocateStructureCompanionTask
        extends AbstractCompanionTask<LocateStructureTaskRecord> {

    public LocateStructureCompanionTask(LocalPlayer player, LocateStructureTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        String argument = r.structure.trim();
        var registry = player.clientLevel.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        boolean known;
        if (argument.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(argument.substring(1));
            known = id != null && registry.get(TagKey.create(Registries.STRUCTURE, id)).isPresent();
        } else {
            ResourceLocation id = ResourceLocation.tryParse(argument);
            known = id != null && registry.get(ResourceKey.create(Registries.STRUCTURE, id)).isPresent();
        }
        if (!known) fail("unknown structure in the client registry: " + argument, FailureType.UNKNOWN);
    }

    @Override
    protected TaskState onTick() {
        fail("the server does not expose authoritative structure starts to this client; "
                        + "travel to load candidate terrain and identify it with visible block/entity scans",
                FailureType.TARGET_LOST);
        return TaskState.FAILED;
    }

    @Override protected void cleanup() {}
    @Override protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("structure", r.structure);
        data.put("found", false);
        data.put("scope", "loaded_client_facts_only");
        return data;
    }
    @Override protected String successMessage() { return "structure observed"; }
    @Override protected String timeoutMessage() { return "structure observation timed out"; }
    @Override protected String cancelledMessage() { return "locate_structure interrupted"; }
}
