package org.maiwithu.maicraft.core.task.build;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.pathing.settings.ScaffoldMaterials;

/** 整理明确的普通挖掘土石；续建时不把历史土石全部当珍藏，仍保留建材、支撑和有自定义数据的物品。 */
public final class BuildExcavationCargo {
    private static final Set<Item> ORDINARY = Set.of(Items.DIRT, Items.COARSE_DIRT, Items.ROOTED_DIRT,
            Items.COBBLESTONE, Items.STONE, Items.DEEPSLATE, Items.COBBLED_DEEPSLATE, Items.ANDESITE,
            Items.DIORITE, Items.GRANITE, Items.TUFF, Items.CALCITE, Items.SAND, Items.RED_SAND,
            Items.GRAVEL, Items.NETHERRACK, Items.BLACKSTONE, Items.END_STONE);
    private final Set<Item> candidates = new LinkedHashSet<>();
    void begin(LocalPlayer player) {
        // 恢复旧项目时，背包中已有的普通土石也参与整理，而非永远保留“本轮起始数量”。
        inventory(player).keySet().stream().filter(ORDINARY::contains).forEach(candidates::add);
    }

    void observedTerrain(BlockState state) {
        // 根据实际挖到的地层记住可能拾起的材料，例如石头掉圆石、草方块掉泥土。
        Item item = state.getBlock().asItem();
        if (ORDINARY.contains(item)) candidates.add(item);
        if (state.is(Blocks.STONE)) candidates.add(Items.COBBLESTONE);
        if (state.is(Blocks.DEEPSLATE)) candidates.add(Items.COBBLED_DEEPSLATE);
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND)) candidates.add(Items.DIRT);
    }

    boolean capacityLow(LocalPlayer player) {
        int empty = 0;
        for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).isEmpty()) empty++;
        return empty < 4;
    }

    Map<ResourceLocation, Integer> unloadable(LocalPlayer player, Map<Item, Integer> materialNeeds) {
        return surplus(player, materialNeeds, Map.of(), candidates);
    }

    /** 出坑和每轮补料前重新观察背包；没有额外支撑计划时只预留一组配置允许的垫脚材料。 */
    public static Map<ResourceLocation, Integer> surplus(LocalPlayer player, Map<Item, Integer> materialNeeds) {
        return surplus(player, materialNeeds, Map.of(), ORDINARY);
    }

    /** 已明确需要的支撑数量不能被默认一组上限截断；这里仅记保留账，不放置或移动任何物品。 */
    public static Map<ResourceLocation, Integer> surplus(LocalPlayer player, Map<Item, Integer> materialNeeds,
            Map<Item, Integer> supportNeeds) {
        return surplus(player, materialNeeds, supportNeeds, ORDINARY);
    }

    private static Map<ResourceLocation, Integer> surplus(LocalPlayer player, Map<Item, Integer> materialNeeds,
            Map<Item, Integer> supportNeeds, Set<Item> selected) {
        Map<Item, Integer> current = inventory(player);
        Map<Item, Integer> keep = new LinkedHashMap<>(materialNeeds);
        supportNeeds.forEach((item, count) -> keep.merge(item, Math.max(0, count), Math::addExact));
        if (supportNeeds.isEmpty()) {
            // 与施工支撑选择使用同一配置和完整材料账；只选择一种有结余的安全实心方块。
            List<Item> allowed = ScaffoldMaterials.of(player);
            Item reserve = BuildTemporarySupportMaterials.choose(allowed, keep,
                    item -> plain(player, item) ? current.getOrDefault(item, 0) : 0, 64, false);
            if (reserve == null) reserve = BuildTemporarySupportMaterials.choose(allowed, keep,
                    item -> plain(player, item) ? current.getOrDefault(item, 0) : 0, 1, false);
            if (reserve != null) keep.merge(reserve,
                    Math.min(64, current.get(reserve) - keep.getOrDefault(reserve, 0)), Math::addExact);
        }
        Map<ResourceLocation, Integer> result = new LinkedHashMap<>();
        for (Item item : selected) {
            // 当前存入语义按物品编号选择；同类中只要有命名或自定义组件，整类留下避免选错那一叠。
            if (!plain(player, item)) continue;
            int surplus = Math.max(0, current.getOrDefault(item, 0) - keep.getOrDefault(item, 0));
            if (surplus > 0) result.put(BuiltInRegistries.ITEM.getKey(item), surplus);
        }
        return Map.copyOf(result);
    }

    static boolean ordinary(Item item) { return ORDINARY.contains(item); }
    static boolean plain(LocalPlayer player, Item item) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item) && !stack.getComponentsPatch().isEmpty()) return false;
        }
        return true;
    }

    private static Map<Item, Integer> inventory(LocalPlayer player) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return counts;
    }
}
