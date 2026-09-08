package org.maiwithu.maicraft.core.tools.work;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.task.move.RegionalTravelTaskRecord;
import org.maiwithu.maicraft.task.TaskDispatch;

/** Internal implementation of platform discovery through the existing public travel ability. */
public final class RegionalTravelTool implements MaiCraftTool {
    public String name() { return "travel_region"; }
    public String description() { return "Discover and reach a platform in a direction using incremental observation and native travel."; }
    public Map<String,Object> parameterSchema() {
        return Schema.object().string("direction","down, up, forward, backward, left, right or a cardinal direction")
                .optionalInteger("max_distance","Discovery radius; default 64",8,128)
                .optionalEnum("transport_mode","Native travel preference","auto","ground","jetpack")
                .optionalBool("may_alter_terrain","Allow ground pathing to alter terrain; default false").build();
    }
    public void onGameCall(String callId,JsonObject args,LocalPlayer player,Consumer<String> reply) {
        var record=new RegionalTravelTaskRecord(callId,TaskDispatch.ctx(callId,player).deadline(180*20),
                args.get("direction").getAsString(),args.has("max_distance") ? args.get("max_distance").getAsInt() : 64,
                TransportMode.parse(args.has("transport_mode") ? args.get("transport_mode").getAsString() : null),
                args.has("may_alter_terrain") && args.get("may_alter_terrain").getAsBoolean());
        TaskDispatch.setTask(player,record,args,reply);
    }
}
