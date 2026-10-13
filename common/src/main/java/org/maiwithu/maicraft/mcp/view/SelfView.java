// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.behavior.perception.SceneSelf;
import org.maiwithu.maicraft.behavior.perception.SelfSight;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.goal.GoalRun;
import org.maiwithu.maicraft.kernel.goal.Permissions;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * observe(self) 的样子：角色自己的状态、背包、手上在做的事和这件事的许可。
 *
 * <p>背包按物品合并（"圆石 130"），不按格子列出：LLM 关心有多少，不关心放在第几格；还剩几格空位单独给。
 */
public final class SelfView {
    private SelfView() {}

    /**
     * @param dimension 所在维度；读不到时为 null
     * @param backpack  背包视图；读不到时为 null
     * @param mainGoal  手上的主任务；没有时为 null
     * @param doing     主任务此刻在做什么的一句话；没有时为 null
     * @param automationControls 自动化此刻是否拿着角色；读不到时为 null
     * @param playerTookOver     玩家按 F8 收回了角色、还没交回
     */
    public static JsonObject of(SceneSelf self, String dimension, BackpackView backpack, GoalRun mainGoal, String doing,
                                Boolean automationControls, boolean playerTookOver) {
        return of(self, dimension, backpack, null, mainGoal, doing, automationControls, playerTookOver);
    }

    /** @param foods 读食物数值；读不到为 null，背包里的吃的就不写补多少 */
    public static JsonObject of(SceneSelf self, String dimension, BackpackView backpack, ReadsFoodValues foods,
                                GoalRun mainGoal, String doing, Boolean automationControls, boolean playerTookOver) {
        JsonObject json = new JsonObject();
        JsonObject position = new JsonObject();
        position.addProperty("x", Math.round(self.x() * 10) / 10.0);
        position.addProperty("y", Math.round(self.y() * 10) / 10.0);
        position.addProperty("z", Math.round(self.z() * 10) / 10.0);
        if (dimension != null) position.addProperty("dimension", dimension);
        json.add("position", position);
        json.addProperty("facing", self.facingWord());
        json.addProperty("health", self.health());
        json.addProperty("food", self.food());
        json.addProperty("air", self.air());
        json.addProperty("max_air", self.maxAir());
        json.addProperty("on_ground", self.onGround());
        json.addProperty("in_water", self.inWater());
        if (self.heldItem() != null) json.addProperty("held", self.heldItem());
        // 副手拿着的单列：盾、火把这类常备的，或者顺手攥着的零碎，整理背包时一眼看得出来。
        if (self.offhandItem() != null) json.addProperty("offhand", self.offhandItem());
        JsonArray armor = new JsonArray();
        self.armor().forEach(armor::add);
        json.add("armor", armor);
        JsonArray effects = new JsonArray();
        self.effects().forEach(effects::add);
        json.add("effects", effects);
        // 装备与手里东西自己画的那条：耐久、背罐的气、电量，物品不画条的不列。
        if (!self.gearBars().isEmpty()) {
            JsonArray bars = new JsonArray();
            for (SelfSight.GearBar bar : self.gearBars()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("slot", bar.slot());
                entry.addProperty("item", bar.item());
                entry.addProperty("bar_percent", bar.percent());
                bars.add(entry);
            }
            json.add("gear_bars", bars);
        }
        if (backpack != null) {
            json.add("inventory", inventory(backpack, foods));
            json.addProperty("free_slots", backpack.freeSlots());
        }
        // 谁在操作角色：在玩家手上时主任务不推进。玩家按 F8 收回的，要等玩家再按 F8 交回，
        // 重新下达也抢不回来，单独写明，免得 LLM 反复下达。
        if (automationControls != null) {
            json.addProperty("control", automationControls ? "automation" : "player");
            if (!automationControls && playerTookOver) {
                json.addProperty("player_took_over", true);
            }
        }
        json.add("goal", goal(mainGoal, doing));
        json.add("permissions", permissions(mainGoal == null ? Permissions.DEFAULT : mainGoal.goal().permissions()));
        return json;
    }

    private static JsonArray inventory(BackpackView backpack, ReadsFoodValues foods) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (BackpackStack stack : backpack.stacks()) {
            merged.merge(stack.itemId(), stack.count(), Integer::sum);
        }
        JsonArray list = new JsonArray();
        merged.forEach((item, count) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("item", item);
            entry.addProperty("count", count);
            // 吃的写上吃一个补多少、可能带什么效果：模组食物认不全，挑哪样吃、要不要先烤看这一项。
            if (foods != null) foods.of(item).ifPresent(food -> entry.add("food", food(food)));
            list.add(entry);
        });
        return list;
    }

    private static JsonObject food(ReadsFoodValues.FoodValue value) {
        JsonObject food = new JsonObject();
        food.addProperty("nutrition", value.nutrition());
        food.addProperty("saturation", Math.round(value.saturation() * 10) / 10.0);
        food.addProperty("eat_seconds", Math.round(value.eatSeconds() * 10) / 10.0);
        if (value.canAlwaysEat()) food.addProperty("edible_when_full", true);
        if (!value.effects().isEmpty()) {
            JsonArray effects = new JsonArray();
            for (ReadsFoodValues.FoodEffect effect : value.effects()) {
                effects.add(effect.name() + (effect.beneficial() ? "（有益）" : "（有害）"));
            }
            food.add("effects", effects);
        }
        return food;
    }

    /** 手上在做的目标（主任务）一句话；没有时说"空闲"，免得 LLM 以为没读到。 */
    private static JsonObject goal(GoalRun mainGoal, String doing) {
        JsonObject json = new JsonObject();
        if (mainGoal == null) {
            json.addProperty("doing", "空闲，没有在做的目标");
            return json;
        }
        json.addProperty("goal_id", mainGoal.id());
        json.addProperty("ability", mainGoal.goal().ability());
        json.addProperty("state", SceneView.lower(mainGoal.state()));
        if (doing != null) json.addProperty("doing", doing);
        return json;
    }

    /** 当前许可：主任务带的，没有主任务时是默认的"像正常玩家一样"。不是游戏或服务器的 OP 权限。 */
    private static JsonObject permissions(Permissions permissions) {
        JsonObject json = new JsonObject();
        json.addProperty("change_blocks", SceneView.lower(permissions.changeBlocks()));
        json.addProperty("fight", SceneView.lower(permissions.fight()));
        json.addProperty("use_rare_items", permissions.useRareItems());
        json.addProperty("kill_animals", SceneView.lower(permissions.killAnimals()));
        json.addProperty("survival_needs", SceneView.lower(permissions.survivalNeeds()));
        JsonArray landmarks = new JsonArray();
        permissions.protectedLandmarks().stream().sorted().forEach(landmarks::add);
        json.add("protected_landmarks", landmarks);
        return json;
    }
}
