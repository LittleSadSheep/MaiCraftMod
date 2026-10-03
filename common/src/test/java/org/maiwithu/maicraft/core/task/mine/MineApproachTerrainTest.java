// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.mine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.task.acquire.SemanticAcquireTaskRecord;
import org.maiwithu.maicraft.core.task.move.MoveToTaskRecord;
import org.maiwithu.maicraft.core.pathing.goal.GoalCompiler;
import org.maiwithu.maicraft.core.pathing.transport.TransportMode;
import org.maiwithu.maicraft.core.tools.work.SemanticAcquireApi;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 接近性动土与失败口径（042）：mine 认领接近源的挖阶梯/隧道/垫高许可，天然树源不再因
 * "只认天然树"被同时禁掉接近通道；无路失败的回执区分"验证目标不可达"与"另有候选未通过
 * 树形验证"；带高度提示的 travel 目标语义保持 BLOCK 目标，供到达回执交付站位偏差。
 */
public final class MineApproachTerrainTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // 测试环境没有数据包标签；naturalLogSource 依赖 LOGS 判定，先按 NaturalTreeSourceTest 先例手工绑定再恢复。
        Map<TagKey<Block>, List<Holder<Block>>> tags = new HashMap<>();
        BuiltInRegistries.BLOCK.getTags().forEach(pair -> tags.put(pair.getFirst(), pair.getSecond().stream().toList()));
        Map<TagKey<Block>, List<Holder<Block>>> originalTags = new HashMap<>(tags);
        tags.put(BlockTags.LOGS, List.of(Blocks.OAK_LOG.builtInRegistryHolder()));
        BuiltInRegistries.BLOCK.bindTags(tags);
        try {
            approachTerrainAlterDefaults();
            travelContextFollowsTheRecord();
            exhaustedPathNamesTheGatedCandidates();
            moveToHeightHintSemantics();
            acquireContractAcceptsMayAlterTerrain();
        } finally {
            BuiltInRegistries.BLOCK.bindTags(originalTags);
        }
        System.out.println("MineApproachTerrainTest: passed");
    }

    /** 许可决策落在任务单上：默认随采矿语义开启，显式 false 收窄，单格采收恒不走动土通道。 */
    private static void approachTerrainAlterDefaults() {
        var plain = new MineBlockTaskRecord("plain", 1000, Set.of(Blocks.STONE), 4, "stone");
        check(plain.approachTerrainAlter(), "普通采矿默认允许接近性动土");
        var natural = naturalLogRecord("tree");
        check(natural.approachTerrainAlter(), "天然树源默认同样允许接近性动土：目标筛选与通道是两回事");
        var narrowed = naturalLogRecord("narrow").withApproachTerrainAlter(false);
        check(!narrowed.approachTerrainAlter(), "显式 false 收窄为普通行走路线");
        var exact = new MineBlockTaskRecord("exact", 1000, Set.of(Blocks.IRON_ORE), 1, "iron_ore",
                Set.of(Items.RAW_IRON)).onlyAt(new BlockPos(1, 2, 3), Blocks.IRON_ORE.defaultBlockState());
        check(!exact.approachTerrainAlter(), "单格采收的授权不覆盖通道和周围机架");
        exact.withApproachTerrainAlter(true);
        check(!exact.approachTerrainAlter(), "单格采收不受该字段重新开启");
    }

    private static MineBlockTaskRecord naturalLogRecord(String id) {
        return new MineBlockTaskRecord(id, 1000, Set.of(Blocks.OAK_LOG), 4, "oak_log",
                Set.of(), false, true);
    }

    /** travelContext 只看任务单许可：普通矿石与天然树源同为 TERRAFORM，收窄与单格采收走 DEFAULT。 */
    private static void travelContextFollowsTheRecord() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            Method travelContext = MineCompanionTask.class.getDeclaredMethod("travelContext");
            travelContext.setAccessible(true);
            var plain = new MineBlockTaskRecord("ctx-plain", 1000, Set.of(Blocks.STONE), 1, "stone");
            check(travelContext.invoke(new MineCompanionTask(h.player, plain)) == PlayerNav.ContextProvider.TERRAFORM,
                    "普通矿石接近阶段保持允许动土");
            var natural = new MineCompanionTask(h.player, naturalLogRecord("ctx-tree"));
            check(travelContext.invoke(natural) == PlayerNav.ContextProvider.TERRAFORM,
                    "天然树源接近阶段不再被无条件禁动土（042 场景）");
            var narrowed = new MineCompanionTask(h.player, naturalLogRecord("ctx-narrow").withApproachTerrainAlter(false));
            check(travelContext.invoke(narrowed) == PlayerNav.ContextProvider.DEFAULT,
                    "收窄后天然树源回到普通行走路线");
            var exact = new MineBlockTaskRecord("ctx-exact", 1000, Set.of(Blocks.IRON_ORE), 1, "iron_ore",
                    Set.of(Items.RAW_IRON)).onlyAt(new BlockPos(1, 2, 3), Blocks.IRON_ORE.defaultBlockState());
            check(travelContext.invoke(new MineCompanionTask(h.player, exact)) == PlayerNav.ContextProvider.DEFAULT,
                    "单格采收始终只走现有安全路线");
        }
    }

    /** 无路失败文本必须点名"另有候选未通过树形验证"，不可达与不可见在回执里分得开。 */
    private static void exhaustedPathNamesTheGatedCandidates() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var effects = LivingEntity.class.getDeclaredField("activeEffects"); effects.setAccessible(true);
            effects.set(h.player, new HashMap<>());
            Field knownOres = MineCompanionTask.class.getDeclaredField("knownOres"); knownOres.setAccessible(true);
            Field failedPathField = MineCompanionTask.class.getDeclaredField("failedPath"); failedPathField.setAccessible(true);
            Field naturalTreesField = MineCompanionTask.class.getDeclaredField("naturalTrees"); naturalTreesField.setAccessible(true);
            Method exhausted = MineCompanionTask.class.getDeclaredMethod("exhaustedPath"); exhausted.setAccessible(true);

            var record = naturalLogRecord("gated").withinRadius(new BlockPos(0, 1, 0), 16);
            var task = new MineCompanionTask(h.player, record); task.start(h.player);
            List<BlockPos> targets = (List<BlockPos>) knownOres.get(task);
            for (int i = 0; i < 7; i++) targets.add(new BlockPos(i, 61, 4));
            NaturalTreeSource naturalTrees = (NaturalTreeSource) naturalTreesField.get(task);
            for (int i = 0; i < 65; i++) naturalTrees.rejected.add(new BlockPos(-i, 62, 8));
            failedPathField.set(task, new NoPathVerdict(new Vec3(0, 1, 0),
                    GoalCompiler.mineField(List.copyOf(targets), List.of()).semanticFingerprint(),
                    "planning exhausted its budget", 0));
            exhausted.invoke(task);
            String receipt = task.result(TaskState.FAILED).message();
            check(receipt.contains("no route from the current stance to any of the 7 verified targets"),
                    "无路失败先说清验证目标口径：" + receipt);
            check(receipt.contains("failed the natural-tree check") && receipt.contains("65"),
                    "天然树源失败要点名未验证候选的数量：" + receipt);
            check(receipt.contains("not evidence they do not exist"),
                    "缺席声明：未过闸不构成世界不存在的证据：" + receipt);

            var plainRecord = new MineBlockTaskRecord("ungated", 1000, Set.of(Blocks.STONE), 2, "stone");
            var plainTask = new MineCompanionTask(h.player, plainRecord); plainTask.start(h.player);
            List<BlockPos> plainTargets = (List<BlockPos>) knownOres.get(plainTask);
            plainTargets.add(new BlockPos(2, 60, 2));
            failedPathField.set(plainTask, new NoPathVerdict(new Vec3(0, 1, 0),
                    GoalCompiler.mineField(List.copyOf(plainTargets), List.of()).semanticFingerprint(),
                    "planning exhausted its budget", 0));
            exhausted.invoke(plainTask);
            String plainReceipt = plainTask.result(TaskState.FAILED).message();
            check(!plainReceipt.contains("natural-tree check"),
                    "非天然树源的无路失败不携带树形闸门口径：" + plainReceipt);
        }
    }

    /** 带高度提示的 travel 是 BLOCK 目标（到达回执据此交付站位偏差），只给 x/z 的高度不设限。 */
    private static void moveToHeightHintSemantics() {
        // 公开 travel 的默认容差入口：非精确到达按 horizontal_radius 与 vertical_tolerance 判定。
        var withHint = new MoveToTaskRecord("hint", 1000, 3.0, 63.0, 5.0, null, false,
                false, TransportMode.AUTO, false, false, 3, 2);
        check(withHint.kind == MoveToTaskRecord.Kind.BLOCK && withHint.y != null,
                "x+y+z 目标携带高度提示");
        check(!withHint.exact && withHint.verticalTolerance > 0,
                "非精确到达按垂直容差判定，回执据此交付站位与提示的偏差");
        var exactStance = new MoveToTaskRecord("exact", 1000, 3.0, 63.0, 5.0, null, false,
                false, TransportMode.AUTO, false, true, 0, 0);
        check(exactStance.exact && exactStance.kind == MoveToTaskRecord.Kind.BLOCK,
                "精确站位按原目标格判定，不使用提示对照分支");
        var columnOnly = new MoveToTaskRecord("column", 1000, 3.0, null, 5.0, null, false,
                false, TransportMode.AUTO, false, false, 3, 2);
        check(columnOnly.kind == MoveToTaskRecord.Kind.COLUMN && columnOnly.y == null,
                "只给 x/z 时高度不设限，回执不报 target_y_hint");
    }

    /** 契约参数面接受 may_alter_terrain（默认 true），拼错参数名仍被拒绝。 */
    private static void acquireContractAcceptsMayAlterTerrain() {
        var args = new JsonObject();
        args.addProperty("item_id", "minecraft:stone");
        args.addProperty("may_alter_terrain", false);
        SemanticAcquireApi.validateArguments(args);
        var record = new SemanticAcquireTaskRecord("acquire", 1000,
                List.of(ResourceLocation.parse("minecraft:stone")), 1,
                List.of(), false, null, List.of(), 16);
        check(record.approachTerrainAlter, "acquire 任务单默认开启接近性动土");
        record.withApproachTerrainAlter(false);
        check(!record.approachTerrainAlter, "acquire 任务单可显式收窄");
        var misspelled = new JsonObject();
        misspelled.addProperty("may_alter_terain", true);
        try {
            SemanticAcquireApi.validateArguments(misspelled);
            throw new AssertionError("拼错的参数名应被拒绝");
        } catch (IllegalArgumentException expected) { }
        check(BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse("minecraft:stone")),
                "参数校验前注册表已就绪");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
