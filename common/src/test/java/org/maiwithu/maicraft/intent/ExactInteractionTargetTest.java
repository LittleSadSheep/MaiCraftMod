// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.act.FirstPersonInteractionTargeting;
import org.maiwithu.maicraft.core.task.interact.InteractAtCompanionTask;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.core.task.interact.UseItemBatchTaskRecord;
import org.maiwithu.maicraft.core.tools.interact.InteractAtTool;
import org.maiwithu.maicraft.task.TaskDispatch;
import org.maiwithu.maicraft.task.TaskState;
import java.util.concurrent.atomic.AtomicReference;

/** 通过原生任务编译器完成完整语义入口，并使用真实已加载区块索引。 */
public final class ExactInteractionTargetTest {
    private static final BlockPos TARGET = new BlockPos(3, 2, 3), NEIGHBOR = new BlockPos(5, 2, 3);

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var f = new InteractionWorldTestHarness()) {
            f.set(TARGET, Blocks.SPRUCE_BUTTON.defaultBlockState());
            f.set(NEIGHBOR, Blocks.SPRUCE_BUTTON.defaultBlockState());
            for (String blockId : new String[]{null, "minecraft:spruce_button"}) {
                var nativeTask = compile(adapt(goal("coordinates", NEIGHBOR, blockId, null), f), f);
                check(nativeTask.aim.equals(NEIGHBOR), "coordinates must keep the exact button even with a nearer match");
                check(nativeTask.requiredBlock == Blocks.SPRUCE_BUTTON && nativeTask.expectedBlock == null,
                        "the observed input block must survive internal tool parsing as a precondition, not an outcome");
                check(nativeTask.emptyHand, "omitting an item prepares an empty hand through the complete semantic and internal tool path");
            }
            check(f.level.searches == 0, "exact targets must bypass the section index entirely");
            f.set(NEIGHBOR, Blocks.STONE.defaultBlockState());
            var mismatch = adapt(goal("coordinates", NEIGHBOR, "minecraft:spruce_button", "nearest"), f);
            check(mismatch instanceof IntentAction.Decision decision
                            && decision.snapshot().question().contains("does not match"),
                    "a type mismatch must refuse, even when nearest could find a replacement button");
            f.set(NEIGHBOR, Blocks.AIR.defaultBlockState());
            check(adapt(goal("coordinates", NEIGHBOR, null, null), f) instanceof IntentAction.Decision decision
                            && decision.snapshot().question().contains("empty"), "an observed block removed before adaptation must fail");
            int reads = f.level.blockReads;
            check(adapt(goal("coordinates", new BlockPos(32, 2, 3), null, null), f) instanceof IntentAction.Decision decision
                            && decision.snapshot().question().contains("not loaded"), "unloaded coordinates must be reported explicitly");
            check(f.level.blockReads == reads && f.level.searches == 0,
                    "unloaded exact targets must neither read the cell nor load/search neighboring chunks");
            Goal otherDimension = goal("coordinates", TARGET, null, null).withTarget(new Goal.SemanticTarget(
                    "coordinates", null, new Goal.WorldPosition(3, 2, 3, "minecraft:the_nether"), null));
            check(adapt(otherDimension, f) instanceof IntentAction.Decision && f.level.blockReads == reads,
                    "coordinates in a different dimension must not read a same-numbered local cell");
            f.set(NEIGHBOR, Blocks.SPRUCE_BUTTON.defaultBlockState());
            var unique = adapt(goal(null, null, "minecraft:spruce_button", null), f);
            check(unique instanceof IntentAction.Decision decision && decision.snapshot().question().contains("Several"),
                    "ordinary search must still report ambiguous buttons");
            var nearest = compile(adapt(goal("nearest", null, "minecraft:spruce_button", null), f), f);
            check(nearest.aim.equals(TARGET) && f.level.searches > 0,
                    "nearest search must retain actual loaded-index selection");
        }
        changedAfterCompilation(false);
        changedAfterCompilation(true);
        heldItemUseHasItsOwnSemanticEntry();
        foodEffectsAreAnExplicitPlannerChoice();
        starvingLockedBodySeesTheInterlock();
        flintIgnitionTargetsAndHonestFire();
        bucketSourceSelection(false);
        bucketSourceSelection(true);
        signWritePlanContractAndCompile();
        // 公开 use_item 的定点分支同时覆盖空格倒桶与门框嵌眼，避免退回只读契约测试。
        TargetedItemUseTest.main(args);
        System.out.println("ExactInteractionTargetTest: passed");
    }

    /** 洪水超过最近候选窗口和索引饱和阈值时，仍选真源格；同方块水位变化必须立即更新候选。 */
    private static void bucketSourceSelection(boolean dense) throws Exception {
        try (var f = new InteractionWorldTestHarness()) {
            f.inventory.setItem(0, new ItemStack(Items.BUCKET));
            var flow = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 4);
            for (int x = 1; x <= (dense ? 12 : 4); x++) for (int z = 1; z <= 14; z++)
                for (int y = 1; y <= 2; y++) f.set(new BlockPos(x, y, z), flow);
            var source = new BlockPos(14, 2, 14); f.set(source, Blocks.WATER.defaultBlockState());
            var parameters = new JsonObject(); parameters.addProperty("block_id", "minecraft:water");
            parameters.addProperty("item_id", "minecraft:bucket"); parameters.addProperty("selection", "nearest");
            var goal = new Goal(GeneralAbilityAdapter.INTERACT, "fill bucket", null,
                    parameters.toString(), "{}", List.of(), List.of());
            check(compile(adapt(goal, f), f).aim.equals(source), "flowing water cannot crowd the real source out of the nearest window");
            f.set(source, flow);
            var absent = adapt(goal, f);
            check(absent instanceof IntentAction.Report report && !report.result().success()
                    && report.result().data().get("failure_code").equals("no_source_block")
                    && Boolean.FALSE.equals(report.result().data().get("native_action_submitted")),
                    "a flood with no source returns a typed, unsubmitted outcome without clicking or waiting for confirmation");
            var renewed = new BlockPos(1, 2, 3); f.set(renewed, Blocks.WATER.defaultBlockState());
            check(compile(adapt(goal, f), f).aim.equals(renewed), "flow-to-source changes invalidate the same-block cached query");
            // 精确指定的流水不换成旁边水源；原生任务在创建导航或提交点击前说明无法收取。
            var exact = goal.withTarget(new Goal.SemanticTarget("coordinates", null,
                    new Goal.WorldPosition(source.getX(), source.getY(), source.getZ(), "minecraft:overworld"), null));
            var task = new InteractAtCompanionTask(f.player, compile(adapt(exact, f), f));
            task.start(f.player); check(task.tick(f.player) == TaskState.FAILED, "exact flowing target fails before travel");
            var result = task.result(TaskState.FAILED);
            check("not_a_source_block".equals(result.data().get("failure_type")) && f.itemUses() == 0 && f.blockUses() == 0,
                    "native failure preserves the actual source condition and sends no use");
            // 满桶倒入流水仍是可尝试的原生动作，不能套用空桶选源门槛。
            f.inventory.setItem(0, new ItemStack(Items.WATER_BUCKET)); parameters.addProperty("item_id", "minecraft:water_bucket");
            check(adapt(goal.withParameters(parameters), f) instanceof IntentAction.Tool, "filled bucket interaction keeps flowing-water targets");
        }
    }

    /** 默认补食仍避开效果食物；自主任务点名并接受效果后可直接执行，不插入额外人工批准。 */
    private static void foodEffectsAreAnExplicitPlannerChoice() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.getFoodData().setFoodLevel(6); h.inventory.setItem(0, new ItemStack(Items.ROTTEN_FLESH, 3));
            var p = new JsonObject(); p.addProperty("item_id", "minecraft:rotten_flesh");
            var goal = new Goal(GeneralAbilityAdapter.CONSUME, "restore hunger", new Goal.SemanticTarget("current_place", null, null, null), p.toString(), "{}", List.of(), List.of());
            check(AbilityAdapter.adapt(goal, h.player, null) instanceof IntentAction.Decision, "default food selection does not silently accept effects");
            p.addProperty("allow_effects", true); SemanticGoalContract.validate(goal.withParameters(p), GeneralAbilityAdapter.abilities());
            check(AbilityAdapter.adapt(goal.withParameters(p), h.player, null) instanceof IntentAction.Tool && h.itemUses() == 0,
                    "explicit planner choice compiles one native eating action without requesting human approval or eating during planning");
            // 中文契约须点名由 LLM 在已授权任务内决定是否接受食物效果，而不是再向玩家要人工审批。
            check(SemanticAbilityCatalog.describe(GeneralAbilityAdapter.CONSUME).toString().contains("LLM可在已授权游戏任务内作此策略选择"), "the public contract identifies the actual decision maker");
        }
    }

    /** 互锁处境：血量在拒战线下且无安全食物时，缺粮决策点破处境，recover 出路仍指向可授权的死亡重置。 */
    private static void starvingLockedBodySeesTheInterlock() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.player.setHealth(4.0f);
            h.player.getFoodData().setFoodLevel(6);
            var goal = new Goal(GeneralAbilityAdapter.CONSUME, "restore hunger",
                    new Goal.SemanticTarget("current_place", null, null, null), "{}", "{}", List.of(), List.of());
            var locked = (IntentAction.Decision) AbilityAdapter.adapt(goal, h.player, null);
            check(locked.snapshot().question().contains("food-combat recovery loop is interlocked"),
                    "the no-safe-food decision must name the interlock when health and hunger block recovery");
            check(locked.snapshot().options().stream()
                            .anyMatch(o -> o.description().contains("maicraft:suicide death reset")),
                    "the recover option must still offer the authorized death reset");
            // 血量在拒战线上方时常规缺粮即可恢复，互锁长文不得淹没普通缺粮决策。
            h.player.setHealth(20.0f);
            var fed = (IntentAction.Decision) AbilityAdapter.adapt(goal, h.player, null);
            check(!fed.snapshot().question().contains("interlocked"),
                    "a healthy starving body keeps the ordinary no-food decision");
        }
    }

    private static void heldItemUseHasItsOwnSemanticEntry() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            // 有副手加工机制的物品无需虚构实体或方块目标，公开接口也不接收鼠标键和持用刻数。
            h.inventory.setItem(0, new ItemStack(Items.HONEY_BOTTLE));
            var p = new JsonObject(); p.addProperty("item_id", "minecraft:honey_bottle"); p.addProperty("expected_output_item_id", "minecraft:glass_bottle");
            var goal = new Goal(GeneralAbilityAdapter.USE_ITEM, "use carried item", new Goal.SemanticTarget("current_place", null, null, null), p.toString(), "{}", List.of(), List.of());
            SemanticGoalContract.validate(goal, GeneralAbilityAdapter.abilities());
            var action = (IntentAction.Native) AbilityAdapter.adapt(goal, h.player, null);
            var r = (InteractAtTaskRecord) action.record();
            check(r.heldItemUseOnly && r.aim == null && r.expectedOutputItem == Items.GLASS_BOTTLE && h.itemUses() == 0,
                    "semantic adaptation prepares native item use without clicking");
            // 一次语义请求携带产量和原料，编译器交给 Mod 批次执行，不展开成多条模型指令。
            p.addProperty("count", 15); p.addProperty("ingredient_item_id", "minecraft:quartz");
            SemanticGoalContract.validate(goal.withParameters(p), GeneralAbilityAdapter.abilities());
            var batch = (UseItemBatchTaskRecord) ((IntentAction.Native) AbilityAdapter.adapt(goal.withParameters(p), h.player, null)).record();
            check(batch.count == 15 && batch.ingredient == Items.QUARTZ && batch.output == Items.GLASS_BOTTLE && h.itemUses() == 0, "batch adaptation preserves semantic material and count without acting");
            for (String invalid : new String[]{"0", "65", "1.5", "null", "\"3\""}) {
                p.add("count", JsonParser.parseString(invalid));
                try { AbilityAdapter.adapt(goal.withParameters(p), h.player, null); throw new AssertionError("invalid batch count accepted"); }
                catch (IllegalArgumentException expected) { }
            }
            p.addProperty("count", 15);
            p.addProperty("hold_ticks", 32);
            try { SemanticGoalContract.validate(goal.withParameters(p), GeneralAbilityAdapter.abilities()); throw new AssertionError("raw hold ticks accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    private static Goal goal(String kind, BlockPos at, String blockId, String selection) {
        JsonObject parameters = new JsonObject();
        if (blockId != null) parameters.addProperty("block_id", blockId);
        if (selection != null) parameters.addProperty("selection", selection);
        var target = kind == null ? null : new Goal.SemanticTarget(kind, null, at == null ? null
                : new Goal.WorldPosition(at.getX(), at.getY(), at.getZ(), "minecraft:overworld"), null);
        return new Goal(GeneralAbilityAdapter.INTERACT, "use this button", target, parameters.toString(), "{}", List.of(), List.of());
    }

    /**
     * 告示牌写字的计划期校验与编译：text 必填、行数与行长越界在计划期拒绝，
     * 非告示牌目标直接回决策；合法请求经内部 sign_text 桥编译成空手右键。
     */
    private static void signWritePlanContractAndCompile() throws Exception {
        try (var f = new InteractionWorldTestHarness()) {
            BlockPos at = new BlockPos(9, 2, 9);
            f.set(at, Blocks.OAK_SIGN.defaultBlockState());
            var record = compile(adapt(signWriteGoal(at, "仓库 A\n只放铁锭"), f), f);
            check(record.aim.equals(at) && record.signLines.equals(List.of("仓库 A", "只放铁锭")),
                    "purpose=write carries the caller-provided lines through the internal sign_text bridge");
            check(record.emptyHand && record.item == null,
                    "sign writing compiles an empty-hand right-click so the native edit screen opens");
            for (String bad : new String[]{null, "a\nb\nc\nd\ne"}) {
                try { adapt(signWriteGoal(at, bad), f); throw new AssertionError("invalid sign write accepted: " + bad); }
                catch (SemanticContractException expected) {
                    check("invalid_sign_write_contract".equals(expected.violationCode()),
                            "the contract names the sign write violation");
                }
            }
            try { adapt(signWriteGoal(at, "x".repeat(InteractAtTaskRecord.SIGN_LINE_CHAR_LIMIT + 1)), f);
                throw new AssertionError("an over-long sign line was accepted"); }
            catch (SemanticContractException expected) {
                check("invalid_sign_write_contract".equals(expected.violationCode()),
                        "the over-long sign line is refused at plan time");
            }
            f.set(at, Blocks.STONE.defaultBlockState());
            var refused = adapt(signWriteGoal(at, "still valid text"), f);
            check(refused instanceof IntentAction.Decision decision && decision.snapshot().question().contains("sign"),
                    "a non-sign target refuses sign writing before any native click");
        }
    }

    private static Goal signWriteGoal(BlockPos at, String text) {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("purpose", "write");
        if (text != null) parameters.addProperty("text", text);
        var target = new Goal.SemanticTarget("coordinates", null,
                new Goal.WorldPosition(at.getX(), at.getY(), at.getZ(), "minecraft:overworld"), null);
        return new Goal(GeneralAbilityAdapter.INTERACT, "write the sign", target, parameters.toString(), "{}", List.of(), List.of());
    }

    private static IntentAction adapt(Goal goal, InteractionWorldTestHarness f) throws Exception {
        SemanticGoalContract.validate(goal, Set.of(GeneralAbilityAdapter.INTERACT));
        for (int tick = 0; tick < 100; tick++) {
            IntentAction action = AbilityAdapter.adapt(goal, f.player, null);
            if (action != IntentAction.Pending.INSTANCE) return action;
            f.nextTick();
        }
        throw new AssertionError("the real loaded section query never completed");
    }

    private static void changedAfterCompilation(boolean afterAim) throws Exception {
        try (var f = new InteractionWorldTestHarness()) {
            f.set(TARGET, Blocks.SPRUCE_BUTTON.defaultBlockState());
            var record = compile(adapt(goal("coordinates", TARGET, null, null), f), f);
            var task = new InteractAtCompanionTask(f.player, record);
            var act = InteractAtCompanionTask.class.getDeclaredMethod("act"); act.setAccessible(true);
            if (afterAim) check(act.invoke(task) == TaskState.RUNNING, "first look waits for the camera before pressing");
            f.set(TARGET, Blocks.STONE.defaultBlockState());
            f.nextTick();
            check(act.invoke(task) == TaskState.FAILED && f.itemUses() == 0 && f.blockUses() == 0,
                    "a different non-air block at the compiled coordinate must fail before any native use");
        }
    }

    private static InteractAtTaskRecord compile(IntentAction action, InteractionWorldTestHarness f) {
        IntentAction.Tool tool = action instanceof IntentAction.Tool direct ? direct
                : action instanceof IntentAction.Chain chain ? chain.actions().getLast() : null;
        check(tool != null && tool.toolName().equals("interact_at"), "the exact target must compile to native interaction: " + action);
        var result = new AtomicReference<InteractAtTaskRecord>();
        TaskDispatch.captureNext(record -> result.set((InteractAtTaskRecord) record),
                () -> new InteractAtTool().onGameCall("exact-target", tool.arguments(), f.player,
                        ignored -> { throw new AssertionError("captured internal interaction replied directly"); }));
        return result.get();
    }

    /**
     * 打火石的目标格口径与点火诚实性：空气格即火落格（点击编译到相邻实心支撑），
     * 实心格保留原坐标；点击确认后相邻格必须真有火，无火不得以成功收尾。
     */
    private static void flintIgnitionTargetsAndHonestFire() throws Exception {
        try (var f = new InteractionWorldTestHarness()) {
            f.inventory.setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
            f.set(NEIGHBOR, Blocks.STONE.defaultBlockState());
            var record = compile(adaptFlint(flintGoal(NEIGHBOR.above(), f), f), f);
            check(record.aim.equals(NEIGHBOR) && record.expectIgnition && record.requiredBlock == Blocks.STONE,
                    "air target for a flint use compiles to the solid support and requires the honest fire check");
            f.set(NEIGHBOR, Blocks.AIR.defaultBlockState());
            var noSupport = adaptFlint(flintGoal(NEIGHBOR.above(), f), f);
            check(noSupport instanceof IntentAction.Decision decision
                            && decision.snapshot().question().contains("no adjacent solid block"),
                    "an air cell with no solid neighbor must refuse ignition instead of swinging at air");
        }
        igniteWithVisibleFire(true);
        igniteWithVisibleFire(false);
        igniteFireThenNaturalBurnout();
        ignitePortalConversion();
    }

    private static IntentAction adaptFlint(Goal goal, InteractionWorldTestHarness f) throws Exception {
        SemanticGoalContract.validate(goal, GeneralAbilityAdapter.abilities());
        for (int tick = 0; tick < 100; tick++) {
            IntentAction action = AbilityAdapter.adapt(goal, f.player, null);
            if (action != IntentAction.Pending.INSTANCE) return action;
            f.nextTick();
        }
        throw new AssertionError("the flint adaptation never completed");
    }

    private static Goal flintGoal(BlockPos fireCell, InteractionWorldTestHarness f) {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("item_id", "minecraft:flint_and_steel");
        var target = new Goal.SemanticTarget("coordinates", null,
                new Goal.WorldPosition(fireCell.getX(), fireCell.getY(), fireCell.getZ(), "minecraft:overworld"), null);
        return new Goal(GeneralAbilityAdapter.USE_ITEM, "ignite the support beside this cell",
                target, parameters.toString(), "{}", List.of(), List.of());
    }

    /** 真实落火成功并写入回执；点击确认而无火时按失败收尾，不再自评成功。 */
    private static void igniteWithVisibleFire(boolean placeFire) throws Exception {
        try (var f = new InteractionWorldTestHarness()) {
            f.position(new Vec3(3.5, 1, .5));
            f.inventory.setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
            f.set(TARGET, Blocks.STONE.defaultBlockState());
            var record = compile(adaptFlint(flintGoal(TARGET, f), f), f);
            check(record.aim.equals(TARGET) && record.expectIgnition,
                    "a solid flint target keeps the exact aim and the fire check");
            var task = new InteractAtCompanionTask(f.player, record); task.start(f.player);
            TaskState state = TaskState.RUNNING;
            BlockHitResult visible = null;
            boolean clicked = false;
            for (int tick = 0; tick < 160 && state == TaskState.RUNNING; tick++) {
                var hit = FirstPersonInteractionTargeting.visibleBlockHit(
                        f.level, f.player, f.player.getEyePosition(), TARGET, 4.5);
                if (hit != null) aim(f, hit.getLocation());
                f.nextTick(); state = task.tick(f.player);
                if (f.blockUses() == 1 && !clicked) {
                    clicked = true; visible = hit;
                    if (placeFire) {
                        f.set(TARGET.relative(hit.getDirection()), Blocks.FIRE.defaultBlockState());
                        f.level.acknowledgedSequence = f.level.blockSequence;
                    } else {
                        // 用耐久变化模拟“原版接受了右键”的确认线索，世界照旧无火。
                        var damaged = new ItemStack(Items.FLINT_AND_STEEL);
                        damaged.setDamageValue(1);
                        f.inventory.setItem(0, damaged);
                    }
                }
            }
            if (placeFire) {
                check(state == TaskState.SUCCESS && clicked,
                        "confirmed ignition with real fire at the adjacent cell succeeds: " + state);
                var data = task.result(state).data();
                check(Boolean.TRUE.equals(data.get("fire_observed")), "receipt reports the verified fire");
            } else {
                check(state == TaskState.FAILED,
                        "a confirmed click without fire must fail instead of claiming success: " + state);
                check(!(f.level.getBlockState(TARGET.relative(visible == null ? Direction.UP : visible.getDirection())).getBlock() instanceof BaseFireBlock),
                        "the failing replay keeps the world without fire");
            }
        }
    }

    /** 真实复现 174 批六D 现场：火在无门框的黑曜石支撑上落格后自然熄灭——成功收口但如实携带完整证据链。 */
    private static void igniteFireThenNaturalBurnout() throws Exception {
        try (var f = new InteractionWorldTestHarness()) {
            f.position(new Vec3(3.5, 1, .5));
            f.inventory.setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
            f.set(TARGET, Blocks.OBSIDIAN.defaultBlockState());
            var record = compile(adaptFlint(flintGoal(TARGET, f), f), f);
            var task = new InteractAtCompanionTask(f.player, record); task.start(f.player);
            TaskState state = TaskState.RUNNING;
            BlockHitResult hit0 = null;
            boolean clicked = false, burnedOut = false, fireObserved = false;
            var observedField = field(InteractAtCompanionTask.class, "ignitionVerified");
            int ticksSinceObserved = 0;
            for (int tick = 0; tick < 160 && state == TaskState.RUNNING; tick++) {
                var hit = FirstPersonInteractionTargeting.visibleBlockHit(
                        f.level, f.player, f.player.getEyePosition(), TARGET, 4.5);
                if (hit != null) aim(f, hit.getLocation());
                f.nextTick(); state = task.tick(f.player);
                if (f.blockUses() == 1 && !clicked) {
                    clicked = true; hit0 = hit;
                    f.set(TARGET.relative(hit.getDirection()), Blocks.FIRE.defaultBlockState());
                    f.level.acknowledgedSequence = f.level.blockSequence;
                    continue;
                }
                // 不可燃地面上的火因无可燃邻居而自然熄灭：等对账真的观察到火之后再把火撤成空气，
                // 让窗口内的时间线记录 fire → air 的完整变化。
                if (!fireObserved) {
                    fireObserved = Boolean.TRUE.equals(observedField.get(task));
                } else if (++ticksSinceObserved == 3) {
                    f.set(TARGET.relative(hit0.getDirection()), Blocks.AIR.defaultBlockState());
                    burnedOut = true;
                }
            }
            check(state == TaskState.SUCCESS && clicked && burnedOut,
                    "a fire that appears and burns out still completes the ignition: " + state);
            var data = task.result(state).data();
            check(Boolean.TRUE.equals(data.get("fire_observed"))
                            && Boolean.FALSE.equals(data.get("nether_portal_formed"))
                            && "minecraft:fire".equals(data.get("ignition_observed_block_id"))
                            && "minecraft:air".equals(data.get("ignition_final_block_id")),
                    "the burnout receipt separates fire evidence from portal evidence: " + data);
            @SuppressWarnings("unchecked")
            var timeline = (java.util.List<String>) data.get("ignition_timeline");
            check(timeline != null && timeline.stream().anyMatch(s -> s.endsWith(":minecraft:fire"))
                            && timeline.stream().anyMatch(s -> s.endsWith(":minecraft:air")),
                    "the timeline records both the fire sighting and the burnout: " + timeline);
            @SuppressWarnings("unchecked")
            var audit = (java.util.Map<String, Object>) data.get("portal_frame_audit");
            check(audit != null && Boolean.FALSE.equals(audit.get("would_form_portal")),
                    "the attached audit honestly reports that no portal would form here: " + audit);
        }
    }

    /** 有效门框把火直接换成传送门方块：点火对账必须认账并回报传送门证据，不得误判为失败。 */
    private static void ignitePortalConversion() throws Exception {        try (var f = new InteractionWorldTestHarness()) {
            f.position(new Vec3(3.5, 1, .5));
            f.inventory.setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
            f.set(TARGET, Blocks.STONE.defaultBlockState());
            var record = compile(adaptFlint(flintGoal(TARGET, f), f), f);
            check(record.aim.equals(TARGET) && record.expectIgnition,
                    "the portal ignition keeps the exact aim and the fire check");
            var task = new InteractAtCompanionTask(f.player, record); task.start(f.player);
            TaskState state = TaskState.RUNNING;
            boolean clicked = false;
            for (int tick = 0; tick < 160 && state == TaskState.RUNNING; tick++) {
                var hit = FirstPersonInteractionTargeting.visibleBlockHit(
                        f.level, f.player, f.player.getEyePosition(), TARGET, 4.5);
                if (hit != null) aim(f, hit.getLocation());
                f.nextTick(); state = task.tick(f.player);
                if (f.blockUses() == 1 && !clicked) {
                    clicked = true;
                    // 服务端在有效门框中同一刻把火换成传送门：落格只见 nether_portal，从不见 fire。
                    f.set(TARGET.relative(hit.getDirection()), Blocks.NETHER_PORTAL.defaultBlockState());
                    f.level.acknowledgedSequence = f.level.blockSequence;
                }
            }
            check(state == TaskState.SUCCESS && clicked,
                    "a confirmed ignition that becomes a nether portal succeeds: " + state);
            var data = task.result(state).data();
            check(Boolean.TRUE.equals(data.get("fire_observed")) && Boolean.TRUE.equals(data.get("nether_portal_formed"))
                            && "minecraft:nether_portal".equals(data.get("ignition_observed_block_id")),
                    "the portal ignition receipt reports the verified effect, the portal flag and the observed block: " + data);
        }
    }

    /** 夹具显式完成转头，让断言关注真实命中与动作选择。 */
    private static void aim(InteractionWorldTestHarness world, Vec3 point) {
        Vec3 direction = point.subtract(world.player.getEyePosition());
        world.player.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
        world.player.setXRot((float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z))));
    }

    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }

    private static java.lang.reflect.Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                var f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
