package org.maiwithu.maicraft.core.tools.inventory;
import org.maiwithu.maicraft.core.tools.ContainerOps;
import static org.maiwithu.maicraft.task.TaskDispatch.ctx;
import static org.maiwithu.maicraft.task.TaskDispatch.runSync;

import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 内部的按槽号搬运入口。调用方先看当前菜单，再给一组有顺序的来源、目的地和数量。
 * 它不把 Inventory 下标自动理解成菜单槽号，两者必须由调用方正确区分。
 */
public final class TransferTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    private final ContainerOps impl = new ContainerOps();

    private record Args(List<ContainerOps.Move> moves) {}

    @Override
    public String name() {
        return "transfer";
    }

    @Override
    public String description() {
        return "Transfer items between slots in the GUI you have open — reorganize, load a machine, "
                + "deposit or take. Pass `moves` as a LIST; they run in order, so do the whole job in one "
                + "call. inspect_gui first for slot indices.";
    }

    @Override
    // to 和 count 使用 nullable 字段：格式声明要求字段存在但允许 null；文字中的 OMIT 与该声明并不完全一致。
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .objectArray("moves", "Transfers to run in order (one whole job per call).", item -> item
                        .integer("from", "Source slot index (from inspect_gui).")
                        .nullableInteger("to", "Destination slot. OMIT to route the stack to the other section "
                                + "(deposit/take/feed). Give a slot to place exactly there (empty=move, same "
                                + "item=merge, different item=swap).")
                        .nullableInteger("count", "Exact number to move (needs `to`; default = the whole stack). "
                                + "Ignored when `to` is omitted — routing moves the whole stack."))
                .build();
    }

    @Override
    // 解析列表后先做只读检查；无效或无需操作时立即回复，其余交给逐刻搬运任务。
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        ContainerOps.Plan plan = impl.plan(
                a == null ? null : a.moves(), self, ctx(toolCallId, self));
        if (!plan.executable()) {
            reply.accept(plan.immediate().toJson());
            return;
        }
        runSync(self, plan.task(), reply);
    }
}
