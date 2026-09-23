package org.maiwithu.maicraft.core.tools.work;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.core.task.move.BoardStructureTaskRecord;
import org.maiwithu.maicraft.task.TaskDispatch;

/** 内部语义移动操作；公开 MCP 接口仍仅暴露四个意图工具。 */
public final class BoardStructureTool implements MaiCraftTool {
    public String name() { return "board_structure"; }
    public String description() { return "Board an observed physical structure UUID using the equipped Create jetpack and native deck contact."; }
    public Map<String,Object> parameterSchema() { return Schema.object().string("structure_id","Observed physical structure UUID.").build(); }
    public void onGameCall(String callId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        var record = new BoardStructureTaskRecord(callId, TaskDispatch.ctx(callId,player).deadline(180 * 20),
                UUID.fromString(args.get("structure_id").getAsString()));
        TaskDispatch.setTask(player,record,args,reply);
    }
}
