package org.maiwithu.maicraft.core.tools;

import net.minecraft.client.player.LocalPlayer;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import org.maiwithu.maicraft.core.data.WorldTimeSemantics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

/**
 * 感知工具的具体实现：承载 {@code InspectBlockTool}、{@code GetOwnerStatusTool} 和 {@code GetWorldInfoTool} 的业务逻辑，
 * 面向大模型的名称、说明和架构由各工具负责，再委托至此。
 */
public final class PerceptionOps {

    /** 玩家原版 {@code block_interaction_range} 为 4.5 格。 */
    private static final double REACH_SQR = 4.5 * 4.5;

    @SuppressWarnings("deprecation")  // Mojang 为 BlockBehaviour.isSolid() 标注“建议覆写”，不代表该方法已废弃。
    public String inspectBlock(int x, int y, int z, LocalPlayer self) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!self.level().isLoaded(pos)) {
            JsonObject root = new JsonObject();
            root.addProperty("x", x);
            root.addProperty("y", y);
            root.addProperty("z", z);
            root.addProperty("loaded", false);
            root.addProperty("message", "that block is outside the local client's loaded world; move closer and inspect again");
            return root.toString();
        }
        BlockState state = self.level().getBlockState(pos);

        JsonObject root = new JsonObject();
        root.addProperty("x", x);
        root.addProperty("y", y);
        root.addProperty("loaded", true);
        root.addProperty("z", z);
        root.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        // 返回方块状态属性，例如末地传送门框的 has_eye/facing，可让模型判断十二个框中哪些仍需末影之眼，或读取楼梯朝向等。
        // 方块没有属性时省略此字段。
        if (!state.getProperties().isEmpty()) {
            JsonObject props = new JsonObject();
            for (Property<?> p : state.getProperties()) {
                props.addProperty(p.getName(), propValue(state, p));
            }
            root.add("properties", props);
        }
        root.addProperty("is_air", state.isAir());
        root.addProperty("is_solid", state.isSolid());
        root.addProperty("is_liquid", !state.getFluidState().isEmpty());

        float hardness = state.getDestroySpeed(self.level(), pos);
        root.addProperty("hardness", hardness);
        root.addProperty("unbreakable", hardness < 0);

        boolean needsTool = state.requiresCorrectToolForDrops();
        root.addProperty("needs_correct_tool", needsTool);
        ItemStack hand = self.getMainHandItem();
        boolean handIsRightTool = hand.isCorrectToolForDrops(state);
        root.addProperty("current_hand_correct_tool", handIsRightTool);

        if (!state.isAir() && hardness >= 0) {
            float toolSpeed = hand.getDestroySpeed(state);
            if (toolSpeed <= 0.0F) toolSpeed = 1.0F;
            // 原版规则：无需正确工具即可采集的方块始终使用快速破坏系数。
            boolean fast = !needsTool || handIsRightTool;
            float divisor = fast ? 30.0F : 100.0F;
            int ticks = hardness == 0.0F
                    ? 1
                    : Math.max(1, (int) Math.ceil(hardness * divisor / toolSpeed));
            root.addProperty("estimated_mining_ticks", ticks);
        }

        Vec3 center = Vec3.atCenterOf(pos);
        double distSqr = self.distanceToSqr(center);
        root.addProperty("distance_to_me", Math.sqrt(distSqr));
        root.addProperty("in_reach", distSqr <= REACH_SQR);

        return root.toString();
    }

    /** 序列化一个方块状态属性的值，例如 "true" 或 "north"。 */
    private static <T extends Comparable<T>> String propValue(BlockState state, Property<T> p) {
        return p.getName(state.getValue(p));
    }

    public String getOwnerStatus(LocalPlayer self) {
        JsonObject root = new JsonObject();
        // 第一人称自动化操控本地玩家的身体。保留此兼容性查询，但绝不臆造服务器侧的所有者关系。
        LocalPlayer player = self;
        root.addProperty("online", true);
        root.addProperty("relation", "local_player");
        root.addProperty("player_uuid", player.getUUID().toString());
        root.addProperty("name", player.getName().getString());
        root.addProperty("hp", player.getHealth());
        root.addProperty("max_hp", player.getMaxHealth());
        root.addProperty("hunger", player.getFoodData().getFoodLevel());
        root.addProperty("saturation", player.getFoodData().getSaturationLevel());

        JsonObject pos = new JsonObject();
        pos.addProperty("x", player.getX());
        pos.addProperty("y", player.getY());
        pos.addProperty("z", player.getZ());
        root.add("position", pos);

        root.addProperty("same_dimension", true);
        root.addProperty("dimension", player.level().dimension().location().toString());
        root.addProperty("distance_to_me", 0.0);
        root.addProperty("main_hand", itemKey(player.getMainHandItem()));
        root.addProperty("off_hand", itemKey(player.getOffhandItem()));

        return root.toString();
    }

    private static String itemKey(ItemStack stack) {
        if (stack.isEmpty()) return "minecraft:air";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public String getWorldInfo(LocalPlayer self) {
        var level = self.level();

        JsonObject root = new JsonObject();
        root.addProperty("dimension", level.dimension().location().toString());
        root.addProperty("game_time", level.getLevelData().getGameTime());
        root.addProperty("day_index", WorldTimeSemantics.dayIndex(level));
        root.addProperty("time_of_day", WorldTimeSemantics.timeOfDay(level));
        root.addProperty("time_phase", WorldTimeSemantics.phase(level).id());
        root.addProperty("is_bright_outside", WorldTimeSemantics.isDaytime(level));
        root.addProperty("is_dark_outside", WorldTimeSemantics.isNighttime(level));

        String weather;
        if (level.isThundering()) weather = "thunder";
        else if (level.isRaining()) weather = "rain";
        else weather = "clear";
        root.addProperty("weather", weather);

        return root.toString();
    }
}
