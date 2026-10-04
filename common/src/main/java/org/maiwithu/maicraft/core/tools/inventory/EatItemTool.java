package org.maiwithu.maicraft.core.tools.inventory;
import org.maiwithu.maicraft.core.tools.InventoryOps;

import static org.maiwithu.maicraft.task.TaskDispatch.*;

import org.maiwithu.maicraft.agent.tool.Schema;
import org.maiwithu.maicraft.agent.tool.MaiCraftTool;
import net.minecraft.client.player.LocalPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 内部进食入口，只负责选定物品种类并建立任务。
 * 自动挑选食物、是否允许特殊效果等判断在语义适配器；真实使用与完成检查在 EatCompanionTask。
 */
public final class EatItemTool implements MaiCraftTool {

    private static final Gson GSON = new Gson();
    private final InventoryOps impl = new InventoryOps();

    private record Args(String item_id) {}

    @Override
    public String name() {
        return "eat";
    }

    @Override
    // 这里只执行已选定食物的持续使用；食物效果许可由外层语义选择负责，原生消耗仍看实际数量变化。
    public String description() {
        return "把指定随身食物拿到主手，原生持续使用至动画结束，再以同类物品总量减少确认进食。"
                + "当前执行器要求有饥饿机制且物品默认带 FOOD 组件，不覆盖药水、牛奶等所有饮用品。"
                + "生命、饥饿、饱和度和效果由游戏处理，不保证一口吃饱或立即满血。"
                + "食物前后数量、消耗净值和身体状态随回执交付；同期拾取或丢失同类物品会干扰数量证据，取消不代表没有吃掉。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced id of the food to eat, e.g. minecraft:cooked_beef.")
                .build();
    }

    @Override
    // 创建持续进食任务并交给任务槽，等游戏里的使用过程结束后回复。
    public void onGameCall(String toolCallId, JsonObject args, LocalPlayer companion, Consumer<String> reply) {
        // 参数转成进食任务单；被总任务调用时由总任务接住，直接调用时则替换当前任务。
        Args a = GSON.fromJson(args, Args.class);
        setTask(companion, impl.eatItem(a.item_id(), ctx(toolCallId, companion)), args, reply);
    }
}
