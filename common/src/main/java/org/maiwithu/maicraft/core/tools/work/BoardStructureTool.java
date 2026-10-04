package org.maiwithu.maicraft.core.tools.work;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import org.maiwithu.maicraft.core.task.move.BoardStructureTaskRecord;
import org.maiwithu.maicraft.task.TaskDispatch;

/** 内部语义移动操作；公开 MCP 接口仍仅暴露四个意图工具。 */
public final class BoardStructureTool implements MaiCraftTool {
    public String name() { return "board_structure"; }
    public String description() { return "Board an observed physical structure UUID using the equipped Create jetpack and native deck contact."; }
    public Map<String,Object> parameterSchema() {
        // 模型只提供座位的船内位置；瞄准、右键与乘坐确认仍由执行器完成。
        return Map.of("type","object","properties",Map.of("structure_id",Map.of("type","string"),
                "seat_position",Map.of("type","object","properties",Map.of("x",Map.of("type","integer"),"y",Map.of("type","integer"),"z",Map.of("type","integer")),"required",List.of("x","y","z"))),"required",List.of("structure_id"));
    }
    public void onGameCall(String callId, JsonObject args, LocalPlayer player, Consumer<String> reply) {
        var record = new BoardStructureTaskRecord(callId, TaskDispatch.ctx(callId,player).deadline(180 * 20),
                UUID.fromString(args.get("structure_id").getAsString()),null,BoardStructureTaskRecord.seatPosition(args));
        TaskDispatch.setTask(player,record,args,reply);
    }
}
