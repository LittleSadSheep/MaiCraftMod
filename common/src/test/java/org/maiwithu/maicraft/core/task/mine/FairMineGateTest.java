// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.mine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 挖掘公平闸门：索引命中只是「已加载区块里有这种方块」，可挖目标只认「即时可见 ∨ 观察记忆里见过」。
 * 埋藏矿不入目标集，空手回执不得泄露被遮挡候选的数量、位置或存在暗示；看一眼即入记忆，
 * 之后重新被遮挡仍可挖。
 */
public final class FairMineGateTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var h = new InteractionWorldTestHarness()) {
            // 真实采矿会按药水效果估算工具效率；这个未走实体构造器的夹具需要明确初始化为空效果表。
            var effects = LivingEntity.class.getDeclaredField("activeEffects"); effects.setAccessible(true);
            effects.set(h.player, new HashMap<>());
            h.inventory.setItem(0, new ItemStack(Items.DIAMOND_SHOVEL));
            IntentRuntime.get().observedSourceMemory().clear();
            var knownOres = field("knownOres");
            var gatePending = field("gatePending");
            var drainGate = MineCompanionTask.class.getDeclaredMethod("drainGate"); drainGate.setAccessible(true);
            @SuppressWarnings("unchecked")
            var memory = IntentRuntime.get().observedSourceMemory();

            // ① 完全埋藏且无记忆的目标：查询命中也停在闸门外，空手失败回执只携带扫描口径，无透视信息。
            BlockPos buried = encase(h, new BlockPos(12, 1, 8));
            var record = new MineBlockTaskRecord("buried-source", 1000, Set.of(Blocks.DIRT), 1, "dirt")
                    .withinRadius(new BlockPos(8, 1, 8), 6);
            var task = new MineCompanionTask(h.player, record); task.start(h.player); finishQuery(h, task);
            for (int i = 0; i < 4; i++) { h.nextTick(); drainGate.invoke(task); }
            check(((List<?>) knownOres.get(task)).isEmpty(), "埋藏且无记忆的目标不能进入可挖目标集");
            check(((Map<?, ?>) gatePending.get(task)).containsKey(buried), "被闸门挡下的候选应留在等待队列而不是被丢弃");
            var noOre = MineCompanionTask.class.getDeclaredMethod("noOreFailure"); noOre.setAccessible(true);
            noOre.invoke(task);
            String receipt = task.result(TaskState.FAILED).message();
            check(receipt.contains("no visible or previously seen dirt sources"),
                    "公平空手回执应声明可见口径：" + receipt);
            check(!receipt.contains("12, 1, 8") && !receipt.contains("buried"),
                    "回执不得泄露被遮挡候选的位置或存在暗示：" + receipt);
            check(receipt.contains("scanned scope") && receipt.contains("chunk radius"),
                    "回执保留扫描口径骨架（中心 + 区块半径）：" + receipt);

            // ② 暴露目标：闸门即时可见即放行，并写入观察记忆，成为可挖目标。
            memory.clear();
            BlockPos exposed = new BlockPos(10, 1, 8); h.set(exposed, Blocks.DIRT.defaultBlockState());
            var visibleTask = new MineCompanionTask(h.player, new MineBlockTaskRecord("exposed-source", 1000,
                    Set.of(Blocks.DIRT), 1, "dirt").withinRadius(new BlockPos(8, 1, 8), 6));
            visibleTask.start(h.player); finishQuery(h, visibleTask);
            h.nextTick(); drainGate.invoke(visibleTask);
            check(((List<?>) knownOres.get(visibleTask)).contains(exposed), "即时可见的目标必须进入可挖目标集");
            check(memory.seen("minecraft:overworld", exposed), "看一眼即写入观察记忆，供之后被遮挡时复用");

            // ③ 预写记忆后，当前不可见的同一位置仍可入候选：见过一次不因重新遮挡退回不可挖。
            memory.clear();
            BlockPos remembered = encase(h, new BlockPos(14, 1, 8));
            memory.observe("minecraft:overworld", remembered, "minecraft:dirt");
            var recalled = new MineCompanionTask(h.player, new MineBlockTaskRecord("remembered-source", 1000,
                    Set.of(Blocks.DIRT), 1, "dirt").withinRadius(new BlockPos(8, 1, 8), 8));
            recalled.start(h.player); finishQuery(h, recalled);
            check(((List<?>) knownOres.get(recalled)).contains(remembered),
                    "观察记忆命中让重新被遮挡的目标仍可入可挖目标集");
        }
        transparentCoverIsObservationEvidence();
        System.out.println("FairMineGateTest: passed");
    }

    private static void transparentCoverIsObservationEvidence() throws Exception {
        // 独立回放没有服务器标签同步，先补真实的镐采掘标签，结束后恢复其他场景的注册表视图。
        Map<TagKey<Block>, List<Holder<Block>>> previous = new HashMap<>();
        BuiltInRegistries.BLOCK.getTags().forEach(pair -> previous.put(pair.getFirst(), pair.getSecond().stream().toList()));
        var tags = new HashMap<>(previous);
        tags.put(BlockTags.MINEABLE_WITH_PICKAXE, List.of(Blocks.IRON_ORE.builtInRegistryHolder()));
        BuiltInRegistries.BLOCK.bindTags(tags);
        try (var h = new InteractionWorldTestHarness()) {
            var effects = LivingEntity.class.getDeclaredField("activeEffects"); effects.setAccessible(true);
            effects.set(h.player, new HashMap<>());
            h.inventory.setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            var memory = IntentRuntime.get().observedSourceMemory(); memory.clear();
            BlockPos ore = new BlockPos(4, 2, 3);
            h.set(ore, Blocks.IRON_ORE.defaultBlockState());
            // 角色隔着完整玻璃墙能认出矿石，采矿观察闸门也必须接收同一份视觉证据。
            for (int z = 0; z < 8; z++) for (int y = 1; y < 5; y++)
                h.set(new BlockPos(2, y, z), Blocks.GLASS.defaultBlockState());
            var task = new MineCompanionTask(h.player, new MineBlockTaskRecord("glass-covered-ore", 1000,
                    Set.of(Blocks.IRON_ORE), 1, "iron ore").withinRadius(new BlockPos(0, 1, 3), 8));
            task.start(h.player); finishQuery(h, task);
            var drain = MineCompanionTask.class.getDeclaredMethod("drainGate"); drain.setAccessible(true);
            h.nextTick(); drain.invoke(task);
            check(((List<?>) field("knownOres").get(task)).contains(ore), "玻璃后可见的矿石应进入已观察候选");
            check(memory.seen("minecraft:overworld", ore), "隔玻璃观察到矿石后应写入观察记忆");
            // 观察成立不授权穿墙挖矿；原生准星尚被玻璃截住，直接挖掘候选仍为空。
            var reachable = MineCompanionTask.class.getDeclaredMethod("reachableTarget"); reachable.setAccessible(true);
            check(reachable.invoke(task) == null, "视觉证据不能绕过原生挖掘命中");
            memory.clear();
        } finally { BuiltInRegistries.BLOCK.bindTags(previous); }
    }

    /** 放置目标并用石头完整包住六个面（地面已默认是石头），得到一个索引可见但角色不可见的候选。 */
    private static BlockPos encase(InteractionWorldTestHarness h, BlockPos at) {
        h.set(at, Blocks.DIRT.defaultBlockState());
        for (BlockPos cover : new BlockPos[]{at.above(), at.north(), at.south(), at.east(), at.west()})
            h.set(cover, Blocks.STONE.defaultBlockState());
        return at;
    }

    private static void finishQuery(InteractionWorldTestHarness h, MineCompanionTask task) throws Exception {
        var query = MineCompanionTask.class.getDeclaredMethod("runQuery"); query.setAccessible(true);
        var complete = MineCompanionTask.class.getDeclaredField("lastQueryComplete"); complete.setAccessible(true);
        for (int i = 0; i < 64; i++) { h.nextTick(); query.invoke(task); if (complete.getBoolean(task)) return; }
        throw new AssertionError("bounded local query did not finish");
    }

    private static Field field(String name) throws Exception {
        var f = MineCompanionTask.class.getDeclaredField(name); f.setAccessible(true); return f;
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
