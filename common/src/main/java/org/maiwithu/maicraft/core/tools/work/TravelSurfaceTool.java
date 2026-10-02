// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.tools.work;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.task.move.TravelSurfaceTaskRecord;
import org.maiwithu.maicraft.task.TaskDispatch;

/** 通过现有公开移动能力执行地表发现的内部实现；露天判定与方向无关，因此没有 direction 参数。 */
public final class TravelSurfaceTool implements MaiCraftTool {
    public String name() { return "travel_surface"; }
    public String description() { return "Climb out to open sky: keep moving until the standing column has no solid or liquid cover above (tree foliage is not treated as cover)."; }
    public Map<String,Object> parameterSchema() {
        return Schema.object()
                .optionalInteger("max_distance","Search radius cap around the start; default 64",8,128)
                .optionalEnum("transport_mode","Native travel preference","auto","ground","jetpack")
                .optionalBool("may_alter_terrain","Allow ground pathing to alter terrain; default false").build();
    }
    public void onGameCall(String callId,JsonObject args,LocalPlayer player,Consumer<String> reply) {
        var record=new TravelSurfaceTaskRecord(callId,TaskDispatch.ctx(callId,player).deadline(180*20),
                args.has("max_distance") ? args.get("max_distance").getAsInt() : 64,
                TransportMode.parse(args.has("transport_mode") ? args.get("transport_mode").getAsString() : null),
                args.has("may_alter_terrain") && args.get("may_alter_terrain").getAsBoolean());
        TaskDispatch.setTask(player,record,args,reply);
    }
}
