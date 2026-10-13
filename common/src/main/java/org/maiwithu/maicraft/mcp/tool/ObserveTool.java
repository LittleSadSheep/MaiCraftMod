// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonObject;
import com.google.gson.GsonBuilder;
import com.google.gson.Gson;
import com.google.gson.FieldNamingPolicy;
import org.maiwithu.maicraft.behavior.reminder.Reminder;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.ClientFoodValues;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.GoalRun;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.mcp.view.DetailView;
import org.maiwithu.maicraft.mcp.view.SceneView;
import org.maiwithu.maicraft.mcp.view.SelfView;
import org.maiwithu.maicraft.mcp.view.WorldMemoryView;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * observe：看世界和自己，只看不做。what 选看什么：self（自己）、scene（周围，默认）、
 * detail（一个看到的东西或记住的地点的细节）、world_memory（角色记得的地点与容器）。
 *
 * <p>只给"玩家此刻能看见、记得的"；需要分析、计算的（例如施工场地勘测）是能力，用 execute。
 * 场景与记忆按世界创建，角色不在世界里时回答不在世界里。
 */
public final class ObserveTool implements McpTool {
    private static final List<String> VIEWS = List.of("self", "scene", "detail", "world_memory");

    /** 提醒转成 JSON：字段名照记录的写法换成下划线小写。 */
    private static final Gson REMINDER_JSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();

    private final Supplier<Scene> scene;
    private final Supplier<WorldMemory> memory;
    private final GoalRunTable table;
    private final ClientThread clientThread;
    /** 角色身上还成立的提醒：observe(self) 每次都完整交出去，事件只负责叫醒。 */
    private final Supplier<List<Reminder>> reminders;

    /**
     * @param scene  当前世界的感知场景；不在世界里时给 null
     * @param memory 当前世界的世界记忆；不在世界里时给 null
     */
    public ObserveTool(Supplier<Scene> scene, Supplier<WorldMemory> memory, GoalRunTable table,
                       ClientThread clientThread, Supplier<List<Reminder>> reminders) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.table = Objects.requireNonNull(table, "table");
        this.clientThread = Objects.requireNonNull(clientThread, "clientThread");
        this.reminders = Objects.requireNonNull(reminders, "reminders");
    }

    @Override public String name() {
        return ToolCatalog.OBSERVE;
    }

    @Override public JsonObject call(JsonObject arguments) {
        RequestCheck check = new RequestCheck();
        check.rejectUnknownFields(arguments, "", List.of("what", "id", "grid"));
        String chosen = check.choice(arguments, "what", "what", VIEWS);
        String what = chosen == null ? "scene" : chosen;
        String id = check.text(arguments, "id", "id", false);
        Boolean grid = check.bool(arguments, "grid", "grid");
        if (what.equals("detail") && id == null && !arguments.has("id")) {
            check.error("id", "what=detail 时要给 id", "观察编号（e12、f3、b5、s2）或地标名");
        }
        if (!what.equals("detail") && id != null) check.error("id", "id 只在 what=detail 时用", null);
        if (!what.equals("scene") && grid != null) check.error("grid", "grid 只在 what=scene 时用", null);
        if (!check.ok()) {
            return ToolReply.error(ErrorCode.INVALID_PARAMETER, "参数有 " + check.errors().size() + " 处问题",
                    check.errors(), null);
        }
        JsonObject data = clientThread.call(context -> view(what, id, Boolean.TRUE.equals(grid), context));
        if (data == null) {
            return ToolReply.error(ErrorCode.UNKNOWN_ID, "没有观察编号或地标叫 " + id
                    + "；观察编号只在短时间内有效，重新 observe(scene) 拿新的编号");
        }
        return ToolReply.ok(data, check.notes(), null);
    }

    /** 在客户端线程上读场景与记忆；找不到 detail 的对象时返回 null。 */
    private JsonObject view(String what, String id, boolean grid, TickContext context) {
        Scene currentScene = scene.get();
        WorldMemory currentMemory = memory.get();
        if (currentScene == null || currentMemory == null) {
            throw new ClientThread.NotInWorld();
        }
        return switch (what) {
            case "self" -> self(currentScene, context);
            case "detail" -> DetailView.of(currentScene, currentMemory, id).orElse(null);
            case "world_memory" -> WorldMemoryView.of(currentMemory, currentScene.self());
            default -> SceneView.scene(currentScene, grid);
        };
    }

    private JsonObject self(Scene currentScene, TickContext context) {
        if (currentScene.self() == null) {
            throw new ClientThread.Busy("刚进世界，还没看清自己，下一刻再看");
        }
        PlayerContext player = context.player();
        String dimension = player == null || player.level() == null
                ? null : player.level().dimension().location().toString();
        GoalRun main = table.mainGoal().orElse(null);
        String doing = main == null ? null : table.doing(main.id()).orElse(null);
        Boolean automation = player == null || player.input() == null ? null : player.input().automationOwnsControls();
        boolean tookOver = player != null && player.input() != null && player.input().humanTookOver();
        // 背包里的吃的按游戏的食物组件写上补多少、有什么效果。
        ReadsFoodValues foods = player == null || player.localPlayer() == null ? null : new ClientFoodValues(player.localPlayer());
        JsonObject view = SelfView.of(currentScene.self(), dimension, player == null ? null : player.backpack(), foods,
                main, doing, automation, tookOver);
        // 还成立的提醒原样交出：一句话、证据数值与阈值、可以考虑的做法；没有提醒时是空数组。
        view.add("reminders", REMINDER_JSON.toJsonTree(reminders.get()));
        return view;
    }
}
