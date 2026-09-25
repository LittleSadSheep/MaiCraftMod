package org.maiwithu.maicraft.core.task.build;

import java.nio.file.Files;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.integration.machine.MachinePlacementItems;
import org.maiwithu.maicraft.core.blueprint.BuildProjectStore;
import org.maiwithu.maicraft.core.blueprint.BuildProjectTargets;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 独立进程模拟Create的一种方块两种物品注册关系，覆盖实际项目读写；不启动游戏或伪造放置结果。 */
public final class NativeBuildProjectMaterialsTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // 此测试独占JVM；扩展注册表只为复现安装模组后的物品身份，不污染其他回归组的“未安装Create”前提。
        unfreeze(BuiltInRegistries.BLOCK); unfreeze(BuiltInRegistries.ITEM);
        var block = Registry.register(BuiltInRegistries.BLOCK, ResourceLocation.parse("create:gearbox"),
                new RotatedPillarBlock(BlockBehaviour.Properties.of()));
        var horizontal = Registry.register(BuiltInRegistries.ITEM, ResourceLocation.parse("create:gearbox"),
                new BlockItem(block, new Item.Properties()));
        var vertical = Registry.register(BuiltInRegistries.ITEM, ResourceLocation.parse("create:vertical_gearbox"),
                new BlockItem(block, new Item.Properties()));
        horizontal.registerBlocks(Item.BY_BLOCK, horizontal);
        BuiltInRegistries.BLOCK.freeze(); BuiltInRegistries.ITEM.freeze();
        try (var h = new InteractionWorldTestHarness()) {
            var store = new BuildProjectStore(new StateIdentity("e".repeat(64), Files.createTempDirectory("native-project-items-")));
            UUID actor = UUID.randomUUID();
            for (var axis : List.of(Direction.Axis.X, Direction.Axis.Z)) {
                var state = block.defaultBlockState().setValue(BlockStateProperties.AXIS, axis);
                check(MachinePlacementItems.itemFor(state) == vertical && block.asItem() == horizontal,
                        "fixture reproduces the distinct native material and default block item");
                var target = new BuildTaskRecord.Target(state, vertical, new BlockPos(6, 2, 6), "vertical gearbox", null, null, null);
                var restored = BuildProjectTargets.decode(BuildProjectTargets.encode(List.of(target))).getFirst();
                check(restored.item() == vertical && restored.desiredState().equals(state), "saved native material and axis survive replay");
                var first = new BuildTaskRecord("first", 1000, List.of(target), true);
                store.bindMachineStage(first, h.level, actor);
                var support = new BlockPos(5, 1, 6); h.set(support, Blocks.DIRT.defaultBlockState());
                first.scaffoldLedger().confirmed(support, Blocks.DIRT.defaultBlockState());
                var retry = new BuildTaskRecord("retry", 1000, List.of(target), true);
                store.bindMachineStage(retry, h.level, actor);
                check(first.projectId().equals(retry.projectId()) && retry.scaffoldLedger().owns(support, Blocks.DIRT.defaultBlockState()),
                        "native item variants keep the same persisted machine scaffold ledger");
                var wrong = BuildProjectTargets.encode(List.of(target)); wrong.get(0).getAsJsonObject().addProperty("item_id", "minecraft:dirt");
                rejects(() -> BuildProjectTargets.decode(wrong));
            }
            // 竖直物品不能被偷换到水平齿轮箱状态，未知物品也不能借恢复入口绕过原生映射。
            var wrongAxis = new BuildTaskRecord.Target(block.defaultBlockState(), vertical, BlockPos.ZERO, "wrong axis", null, null, null);
            rejects(() -> BuildProjectTargets.decode(BuildProjectTargets.encode(List.of(wrongAxis))));
            check(h.blockUses() == 0 && h.itemUses() == 0, "project binding does not build or consume items");
        }
        System.out.println("NativeBuildProjectMaterialsTest: alternate native material replay and scaffold continuity passed");
    }
    private static void unfreeze(Object registry) throws Exception {
        var frozen = MappedRegistry.class.getDeclaredField("frozen"); frozen.setAccessible(true); frozen.setBoolean(registry, false);
        var holders = MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders"); holders.setAccessible(true);
        holders.set(registry, new IdentityHashMap<>());
    }
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("invalid native material accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
