package org.maiwithu.maicraft.core.tools.work;

import static org.maiwithu.maicraft.task.TaskDispatch.setTask;

import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.core.task.move.FollowTaskRecord;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 接收内部跟随请求，立即核对目标已加载，并把它的 UUID 一起存入请求，供以后恢复时检查身份。
 */
public final class FollowTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();

    /** 默认跟到几米内。3 米大致是"就在旁边"又不至于挤到主人身上。 */
    private static final double DEFAULT_DISTANCE = 3.0;
    private static final double MIN_DISTANCE = 2.0;
    private static final double MAX_DISTANCE = 16.0;

    private record Args(Integer distance, Integer entity_id, String entity_uuid, Boolean may_alter_terrain) {}

    @Override
    public String name() {
        return FollowTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Tag along with an explicitly selected loaded entity. This is a STANDING job: there is nothing "
                + "to finish, so you will keep at it until you are given something else to do. "
                + "Use scan_nearby_entities first and pass the selected runtime id. You go quiet "
                + "while already beside the target. Following ends if it dies or leaves the "
                + "loaded area. TERRAIN: by "
                + "default she never breaks or places a block to keep up. When the only way to "
                + "them would need digging, bridging or pillaring, following ENDS with a failure "
                + "that lists exactly which blocks; if altering them is acceptable, re-send follow "
                + "with may_alter_terrain=true (ask the player when it is not obviously natural "
                + "terrain).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalInteger("distance",
                        "How close to stay, in blocks. Defaults to " + (int) DEFAULT_DISTANCE + ".",
                        (int) MIN_DISTANCE, (int) MAX_DISTANCE)
                .integer("entity_id",
                        "Who to follow, by runtime entity id from scan_nearby_entities.",
                        1, Integer.MAX_VALUE)
                .optionalBool("may_alter_terrain", "Consent to dig through, bridge or pillar to "
                        + "keep up. Omit/false = leave every block untouched (default). Set true "
                        + "after a failed follow listed the blocks in the way and you judge that "
                        + "acceptable, or when the player said so.")
                .build();
    }

    @Override
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer companion,
                             Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        double distance = parsed == null || parsed.distance() == null
                ? DEFAULT_DISTANCE
                : Math.clamp(parsed.distance(), MIN_DISTANCE, MAX_DISTANCE);
        Integer entityId = parsed == null ? null : parsed.entity_id();
        if (entityId == null) {
            reply.accept("entity_id is required — first call scan_nearby_entities and choose the player or mob to follow");
            return;
        }
        var target = ((ClientLevel) companion.level()).getEntity(entityId);
        if (target == null || target.isRemoved() || target == companion) {
            reply.accept("no entity with id " + entityId
                    + " is loaded here — scan_nearby_entities first; runtime ids do not survive reconnects");
            return;
        }
        java.util.UUID targetUuid = target.getUUID();
        if (parsed.entity_uuid() != null
                && !targetUuid.equals(java.util.UUID.fromString(parsed.entity_uuid()))) {
            reply.accept("entity id " + entityId + " now refers to a different entity — scan again");
            return;
        }
        args.addProperty("entity_uuid", targetUuid.toString());
        boolean mayAlterTerrain = parsed != null && Boolean.TRUE.equals(parsed.may_alter_terrain());
        setTask(companion, new FollowTaskRecord(toolCallId, distance, entityId, targetUuid, mayAlterTerrain),
                args, reply);
    }
}
