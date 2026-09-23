// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.container;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnchantingTableBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import java.util.Locale;

/** 原生箱桶与非普通命名方块实体共用访问保护；测试只查询夹具，不能开 GUI、改库存或混淆普通容器类型。 */
public final class ContainerAccessPolicyTest {
    private static final String PRIVATE = "access_policy_private_fixture", MISSING = "access_policy_missing_fixture";
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        ordinarySourcesAndDoubleChestProtectionRemainUnchanged();
        genericNamedAccessRespectsEveryProtectionSource();
        System.out.println("ContainerAccessPolicyTest: passed");
    }

    private static void ordinarySourcesAndDoubleChestProtectionRemainUnchanged() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var entities = ContainerSupplySourcesTest.worldEntities(h);
            BlockPos barrel = new BlockPos(3, 1, 3); ContainerSupplySourcesTest.addBarrel(h, entities, barrel);
            // 访问保护测试的仓库已有石料线索，附魔台仍不能混入普通库存候选。
            ContainerSupplySourcesTest.rememberContents(h, barrel, ResourceLocation.parse("minecraft:stone"), 1);
            BlockPos table = new BlockPos(6, 1, 3); var tableState = Blocks.ENCHANTING_TABLE.defaultBlockState();
            h.set(table, tableState); var named = new EnchantingTableBlockEntity(table, tableState); named.setLevel(h.level); entities.put(table, named);
            check(ContainerSupplySources.accessAllowed(h.player, barrel, List.of()) && ContainerSupplySources.allowed(h.player, barrel, List.of()),
                    "an ordinary unprotected barrel retains both access and ordinary-stock eligibility");
            check(ContainerSupplySources.accessAllowed(h.player, table, List.of())
                    && !ContainerSupplySources.allowed(h.player, table, List.of()) && ContainerSupplySources.footprint(h.level, table).isEmpty(),
                    "a non-container block entity can check access without becoming an ordinary inventory source");
            var sources = ContainerSupplySources.candidates(h.player, h.player.blockPosition(), 16,
                    List.of(ResourceLocation.parse("minecraft:stone")), Set.of(), List.of());
            check(sources.size() == 1 && sources.getFirst().position().equals(barrel), "generic access must not admit machine or enchanting slots into ordinary container scanning");
            BlockPos left = new BlockPos(9, 1, 4); var state = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT);
            BlockPos right = left.relative(ChestBlock.getConnectedDirection(state));
            h.set(left, state); h.set(right, state.setValue(ChestBlock.TYPE, ChestType.RIGHT));
            var first = new ChestBlockEntity(left, h.level.getBlockState(left)); first.setLevel(h.level); entities.put(left, first);
            var second = new ChestBlockEntity(right, h.level.getBlockState(right)); second.setLevel(h.level); entities.put(right, second);
            check(ContainerSupplySources.footprint(h.level, left).size() == 2 && ContainerSupplySources.allowed(h.player, left, List.of()),
                    "a native double chest is still one usable ordinary source");
            NavigationSafetyContext.withProtectedArea(List.of(right), List.of(), () -> {
                check(ContainerSupplySources.accessAllowed(h.player, left, List.of()) && !ContainerSupplySources.accessAllowed(h.player, right, List.of()),
                        "generic access applies to the exact requested position");
                check(!ContainerSupplySources.allowed(h.player, left, List.of()) && !ContainerSupplySources.allowed(h.player, right, List.of()),
                        "protecting either half still excludes withdrawal through both halves");
                return null;
            });
            check(ContainerSupplySources.allowed(h.player, left, List.of()), "a completed temporary protection scope is not cached as permanent denial");
            check(h.blockUses() == 0 && h.itemUses() == 0 && h.inventory.isEmpty(), "all access checks and ordinary scans remain read-only");
        } finally { ContainerSupplySources.reset(); }
    }

    private static void genericNamedAccessRespectsEveryProtectionSource() throws Exception {
        Map<String, IntentRuntime.Landmark> landmarks = landmarks(); var before = new LinkedHashMap<>(landmarks);
        try (var h = new InteractionWorldTestHarness()) {
            var entities = ContainerSupplySourcesTest.worldEntities(h); BlockPos table = new BlockPos(6, 1, 3), barrel = new BlockPos(3, 1, 3);
            ContainerSupplySourcesTest.addBarrel(h, entities, barrel);
            var state = Blocks.ENCHANTING_TABLE.defaultBlockState(); h.set(table, state);
            var named = new EnchantingTableBlockEntity(table, state); named.setLevel(h.level); entities.put(table, named);
            var originalBody = h.player.position(); var originalMenu = h.player.containerMenu;
            // 夹具直接提供地标观察并在 finally 恢复，不调用持久化记忆接口，更不写入真实存档。
            landmarks.remove(MISSING);
            check(!ContainerSupplySources.accessAllowed(h.player, table, List.of(MISSING))
                    && !ContainerSupplySources.allowed(h.player, barrel, List.of(MISSING)), "a missing protected landmark denies both general and ordinary access");
            landmarks.put(PRIVATE, new IntentRuntime.Landmark(PRIVATE, null));
            check(!ContainerSupplySources.accessAllowed(h.player, table, List.of(PRIVATE)), "a landmark without a position cannot grant permission");
            remember(landmarks, table.east(12), "minecraft:overworld");
            check(!ContainerSupplySources.accessAllowed(h.player, table, List.of(PRIVATE)), "the protected twelve-block boundary is inclusive");
            remember(landmarks, table.east(13), "minecraft:overworld");
            check(ContainerSupplySources.accessAllowed(h.player, table, List.of(PRIVATE)), "outside the protected radius remains eligible");
            remember(landmarks, table, "minecraft:the_nether");
            check(ContainerSupplySources.accessAllowed(h.player, table, List.of(PRIVATE)), "coordinates in another explicit dimension do not protect this position");
            remember(landmarks, table, null);
            check(!ContainerSupplySources.accessAllowed(h.player, table, List.of(PRIVATE)), "a dimension-unspecified protected location retains the existing local-radius rule");
            remember(landmarks, new BlockPos(100, 1, 100), "minecraft:overworld");
            named.setCustomName(Component.literal(PRIVATE.toUpperCase(Locale.ROOT)));
            check(!ContainerSupplySources.accessAllowed(h.player, table, List.of(PRIVATE)),
                    "native Nameable custom names protect non-container entities even when the landmark itself is far away");
            // 当前原生箱桶仅公开读名字；夹具通过已核实的 name 字段设置初始名称，不伪造不存在的游戏命名操作。
            Field barrelName = BaseContainerBlockEntity.class.getDeclaredField("name"); barrelName.setAccessible(true);
            barrelName.set(entities.get(barrel), Component.literal(PRIVATE));
            check(!ContainerSupplySources.allowed(h.player, barrel, List.of(PRIVATE)), "the former ordinary-container name protection is preserved");
            named.setCustomName(null);
            check(ContainerSupplySources.accessAllowed(h.player, table, List.of(PRIVATE)), "removing a matching custom name is observed on the next check");
            check(!NavigationSafetyContext.withProtectedArea(List.of(table), List.of(),
                    () -> ContainerSupplySources.accessAllowed(h.player, table, List.of())), "a live explicit use-protection cell denies generic machine access");
            check(NavigationSafetyContext.withPreservedStructures(List.of(table),
                    () -> ContainerSupplySources.accessAllowed(h.player, table, List.of())), "preserving structure from mining does not revoke authorized GUI use");
            int reads = h.level.blockReads;
            check(!ContainerSupplySources.accessAllowed(h.player, new BlockPos(32, 1, 3), List.of()) && h.level.blockReads == reads,
                    "unloaded positions are rejected before looking up their block or entity");
            check(h.blockUses() == 0 && h.itemUses() == 0 && h.inventory.isEmpty()
                    && h.player.position().equals(originalBody) && h.player.containerMenu == originalMenu,
                    "protection checks never move the body, open a menu, use an item or transfer contents");
        } finally { landmarks.clear(); landmarks.putAll(before); ContainerSupplySources.reset(); }
    }
    private static void remember(Map<String, IntentRuntime.Landmark> landmarks, BlockPos at, String dimension) {
        landmarks.put(PRIVATE, new IntentRuntime.Landmark(PRIVATE, new Goal.WorldPosition(at.getX(), at.getY(), at.getZ(), dimension)));
    }
    @SuppressWarnings("unchecked") private static Map<String, IntentRuntime.Landmark> landmarks() throws Exception {
        Field field = IntentRuntime.class.getDeclaredField("landmarks"); field.setAccessible(true); return (Map<String, IntentRuntime.Landmark>) field.get(IntentRuntime.get());
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
