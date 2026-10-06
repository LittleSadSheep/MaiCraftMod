package org.maiwithu.maicraft.client.actor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.task.fish.FishCompanionTask;
import org.maiwithu.maicraft.core.task.fish.FishTaskRecord;
import org.maiwithu.maicraft.core.tools.perception.BodyEnvironmentObservation;
import org.maiwithu.maicraft.intent.SemanticResultView;
import org.maiwithu.maicraft.task.TaskState;

/** 回放干燥洞穴与水下无岸站位，失败原因及原生身体实况必须一起传给模型。 */
public final class FishingPositionObservationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        shallowWaterIsNotDryGround();
        positioningFailure(false);
        positioningFailure(true);
        positionApproachWideBoundFailsHonestly();
        System.out.println("FishingPositionObservationTest: passed");
    }

    private static void shallowWaterIsNotDryGround() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            // 玩家在浅水中脚踩实底时仍然落地；头露出水面、没有游泳不能被解释为已经上岸。
            bodyWater(world.player, true, false);
            JsonObject wet = BodyEnvironmentObservation.describe(world.player);
            check(wet.get("on_ground").getAsBoolean() && wet.get("in_water").getAsBoolean()
                    && !wet.get("underwater").getAsBoolean() && !wet.get("swimming").getAsBoolean(),
                    "shallow water must remain distinct from dry ground and submerged eyes");
            bodyWater(world.player, false, false);
            check(!BodyEnvironmentObservation.describe(world.player).get("in_water").getAsBoolean()
                    && wet.get("in_water").getAsBoolean(), "leaving water must not rewrite a previous observation");
            check(world.itemUses() == 0 && world.blockUses() == 0, "body observation must not submit game actions");
        }
    }

    private static void positioningFailure(boolean underwater) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            // 干燥洞穴没有水面；淹没洞穴没有干燥站位。两种失败都沿真实钓鱼定位阶段结算，不制造抛竿。
            CaveLevel cave = world.h.allocate(CaveLevel.class); cave.flooded = underwater;
            ActorControlTestHarness.field(Level.class, "dimension").set(cave, Level.OVERWORLD);
            ActorControlTestHarness.field(Entity.class, "level").set(world.player, cave);
            ActorControlTestHarness.field(LocalPlayer.class, "clientLevel").set(world.player, cave);
            // 站到 y=1：干燥时是石面上第一格空气，淹没时泡在水层里；站位搜索与水面扫描都围绕真实脚位。
            world.position(new Vec3(8.5, 1, 8.5));
            bodyWater(world.player, underwater, underwater);
            world.inventory.setItem(0, new ItemStack(Items.FISHING_ROD));
            var task = new FishCompanionTask(world.player, new FishTaskRecord("fish-position", 2000, 1));
            task.start(world.player);
            check(task.tick(world.player) == TaskState.FAILED, "the scene must fail in the positioning phase");
            Vec3 failurePosition = world.player.position();
            // 回执可能稍后才被查询；玩家已经移动且离水时，也必须保留失败发生时的身体证据。
            world.position(new Vec3(8.5, 1, 8.5)); bodyWater(world.player, false, false);
            var result = SemanticResultView.result(task.result(TaskState.FAILED));
            JsonObject data = JsonParser.parseString(result.toJson()).getAsJsonObject().getAsJsonObject("data");
            JsonObject observed = data.getAsJsonObject("positioning_observation");
            System.out.println("DEBUG underwater=" + underwater + " water@3=" + cave.getFluidState(new BlockPos(8, 3, 8)) + " block@1=" + cave.getBlockState(new BlockPos(8, 1, 8)).getBlock() + " feetY=" + world.player.blockPosition());
            check(observed.get("in_water").getAsBoolean() == underwater
                    && observed.get("underwater").getAsBoolean() == underwater
                    && observed.get("current_stance_dry").getAsBoolean() != underwater,
                    "the semantic receipt must preserve the failure-time body and stance facts");
            check(observed.getAsJsonObject("position").get("x").getAsDouble() == failurePosition.x
                    && observed.getAsJsonObject("position").get("z").getAsDouble() == failurePosition.z
                    && observed.get("game_time").getAsLong() == 40, "failure coordinates and time must survive semantic wrapping");
            check(observed.get("reason").getAsString().equals(underwater
                    ? "no_local_stance_with_cast_target" : FishCompanionTask.NO_VISIBLE_WATER_REASON),
                    "dry ground without water must report the water scan, flooded ground must report the stance search, actual=" + observed.get("reason").getAsString() + ", underwater=" + underwater);
            check(observed.get("water_scan_radius").getAsInt() == 12
                    && observed.get("water_scan_vertical_range").getAsInt() == 8,
                    "the water scan must disclose its bounded search range");
            if (underwater) {
                check(observed.has("nearest_visible_water_distance")
                        && observed.get("nearest_visible_water_distance").getAsDouble() > 0,
                        "flooded ground must report the nearest visible water distance");
            } else {
                check(!observed.has("nearest_visible_water_distance"),
                        "a circle with no visible water must not invent a distance");
            }
            check(result.message().contains("in_water=" + underwater)
                    && (underwater || result.message().contains("no fishable water surface is visible")),
                    "the brief failure message must also distinguish a dry body from one in water");
            check(data.get("casts").getAsInt() == 0 && world.itemUses() == 0 && world.blockUses() == 0,
                    "adding failure evidence must not cast a rod or change the world");
        }
    }

    /**
     * 站位接近段的宽上限（fish 静默楔死样本：十分钟零事件零终态）：接近段超限按
     * planning_stall 如实失败并携带阶段名与已等待时长，宽上限内的合法慢接近不误杀；
     * 记分牌带 phase 与 done，接近静默窗靠 planning_seconds 单调心跳可见。
     */
    private static void positionApproachWideBoundFailsHonestly() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var task = new FishCompanionTask(world.player, new FishTaskRecord("fish-bound", 1_000_000, 3));
            task.start(world.player);
            var bound = FishCompanionTask.class.getDeclaredMethod("positionApproachBound",
                    boolean.class, String.class);
            bound.setAccessible(true);
            var progress = task.progress();
            check("position".equals(progress.get("phase")) && ((Number) progress.get("done")).intValue() == 0,
                    "the fishing scoreboard must expose phase and catch count, actual=" + progress);
            check(bound.invoke(task, true, "planning") == null, "接近在飞应起表继续运行");
            for (int i = 0; i < 120; i++) world.nextTick();
            check(bound.invoke(task, true, "planning") == null, "宽上限内的慢接近继续等待，不误杀");
            for (int i = 0; i < 5000; i++) world.nextTick();
            TaskState state = (TaskState) bound.invoke(task, true, "planning");
            check(state == TaskState.FAILED, "接近超宽上限必须诚实失败，实际 " + state);
            var result = SemanticResultView.result(task.result(TaskState.FAILED));
            check(String.valueOf(result.toJson()).contains("planning_stall"),
                    "接近超限是 planning_stall，实际: " + result.toJson());
            check(result.message().contains("approach phase 'position'")
                            && result.message().contains("seconds"),
                    "失败正文应点名接近阶段与已等待时长，实际: " + result.message());
        }
    }

    private static void bodyWater(LocalPlayer player, boolean wet, boolean eyesWet) throws Exception {
        // 仅在回放夹具中注入原版同步后的浸水标志，正式观察直接读取玩家原生字段。
        ActorControlTestHarness.field(Entity.class, "wasTouchingWater").setBoolean(player, wet);
        ActorControlTestHarness.field(Entity.class, "fluidOnEyes").set(player, eyesWet ? Set.of(FluidTags.WATER) : Set.of());
    }

    private static final class CaveLevel extends ClientLevel {
        boolean flooded;
        private CaveLevel() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        // 用连续实底和整片水层覆盖完整搜索范围，避免测试区块边缘冒充“找不到岸”的游戏事实。
        @Override public BlockState getBlockState(BlockPos pos) {
            return (pos.getY() == 0 ? Blocks.STONE : flooded && pos.getY() > 0 && pos.getY() < 4
                    ? Blocks.WATER : Blocks.AIR).defaultBlockState();
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public long getGameTime() { return 40; }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
