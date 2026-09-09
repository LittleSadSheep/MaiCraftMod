package org.maiwithu.maicraft.core.task.acquire;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.core.PlayerInv;
import org.maiwithu.maicraft.core.inventory.StockEvidence;

/**
 * 为一批采集或使用动作选择工具要求，考虑开局材料、预计工作量和背包／储存中的资源。
 * 这些是本项目的准备策略，不能把所有速度或耐久门槛都解释成游戏完成动作必需的条件。
 */
public final class WorkToolPreparation {
    static final int BATCH_SIZE = 4;
    static final long ABUNDANT_IRON = 64;
    static final long ABUNDANT_DIAMONDS = 192;

    record Choice(SemanticSourceKnowledge.ToolRequirement requirement, boolean stockOnly) {}

    private static List<ItemStack> inventory(LocalPlayer player) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < Math.min(36, player.getInventory().getContainerSize()); slot++)
            result.add(player.getInventory().getItem(slot));
        return result;
    }

    static int bootstrapLimit(LocalPlayer player, Set<Block> blocks) {
        return bootstrapLimit(inventory(player), blocks.stream().map(Block::defaultBlockState).toList());
    }

    // 估算开局最少还需几根木头或几块工具石料，避免为了做第一把工具又无限要求另一把工具。
    static int bootstrapLimit(List<ItemStack> inventory, List<BlockState> sources) {
        int planks = 0, sticks = 0, stone = 0;
        boolean table = false, pick = false;
        for (ItemStack stack : inventory) {
            if (stack.is(ItemTags.LOGS)) planks += stack.getCount() * 4;
            if (stack.is(ItemTags.PLANKS)) planks += stack.getCount();
            if (stack.is(Items.STICK)) sticks += stack.getCount();
            if (stack.is(Items.CRAFTING_TABLE)) table = true;
            if (stack.getItem() instanceof PickaxeItem && (!stack.isDamageableItem()
                    || stack.getMaxDamage() - stack.getDamageValue() >= 3)) pick = true;
            if (stack.is(ItemTags.STONE_TOOL_MATERIALS)) stone += stack.getCount();
        }
        if (sources.stream().anyMatch(state -> state.is(BlockTags.LOGS))) {
            int neededPlanks = (table ? 0 : 4) + (pick ? 0 : 3) + (sticks >= (pick ? 2 : 4) ? 0 : 2);
            return Math.max(0, (neededPlanks - planks + 3) / 4);
        }
        if (pick && sources.stream().anyMatch(state -> state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.DEEPSLATE) || state.is(Blocks.COBBLED_DEEPSLATE) || state.is(Blocks.BLACKSTONE)))
            return Math.max(0, 3 - stone);
        return 0;
    }

    public record UseTool(ResourceLocation itemId, boolean carried, boolean stockOnly) {}

    /** Ordinary tilling shares the same material thresholds, but must select an actual hoe. */
    public static UseTool tillingTool(LocalPlayer player) {
        List<ItemStack> inventory = inventory(player);
        var stock = StockEvidence.latest(player).filter(StockEvidence.Snapshot::supportsToolSupply);
        long iron = PlayerInv.buildableCount(player.getInventory(), Items.IRON_INGOT)
                + stock.map(s -> Math.min(ABUNDANT_IRON, s.storedCount(ResourceLocation.withDefaultNamespace("iron_ingot")))).orElse(0L);
        long diamonds = PlayerInv.buildableCount(player.getInventory(), Items.DIAMOND)
                + stock.map(s -> Math.min(ABUNDANT_DIAMONDS, s.storedCount(ResourceLocation.withDefaultNamespace("diamond")))).orElse(0L);
        return tillingTool(inventory, iron, diamonds);
    }

    // 按铁和钻石的存量选参考锄头，再用挖草捆速度筛已有锄头。
    // 这个速度不是耕地动作速度，富余铁可能反而让已有石锄被判不合格（A66）。
    static UseTool tillingTool(List<ItemStack> inventory, long iron, long diamonds) {
        String tier = diamonds >= ABUNDANT_DIAMONDS ? "diamond" : iron >= ABUNDANT_IRON ? "iron" : "stone";
        ResourceLocation id = ResourceLocation.withDefaultNamespace(tier + "_hoe");
        ItemStack reference = new ItemStack(BuiltInRegistries.ITEM.get(id));
        BlockState crop = Blocks.HAY_BLOCK.defaultBlockState();
        ItemStack carried = inventory.stream().filter(stack -> stack.getItem() instanceof HoeItem
                        && (!stack.isDamageableItem() || stack.getMaxDamage() - stack.getDamageValue() >= 16)
                        && stack.getDestroySpeed(crop) >= reference.getDestroySpeed(crop))
                .max(Comparator.comparingDouble(stack -> stack.getDestroySpeed(crop))).orElse(ItemStack.EMPTY);
        return carried.isEmpty() ? new UseTool(id, false, !tier.equals("stone"))
                : new UseTool(BuiltInRegistries.ITEM.getKey(carried.getItem()), true, false);
    }

    static int batchLimit(LocalPlayer player, Set<Block> blocks, int requested) {
        return batchLimit(inventory(player), blocks.stream().map(Block::defaultBlockState).toList(), requested);
    }

    // 按可用工具耐久给本批留八点余量，最多先做六十四单位；它是批次估计，不是精确计算每个掉落物要挖几块。
    static int batchLimit(List<ItemStack> inventory, List<BlockState> sources, int requested) {
        int durableWork = 0;
        for (ItemStack stack : inventory) {
            if (stack.isEmpty() || sources.stream().noneMatch(state -> stack.getDestroySpeed(state) > 1
                    && (!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state)))) continue;
            int remaining = stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : requested + 8;
            durableWork = Math.max(durableWork, remaining - 8);
        }
        return Math.min(requested, durableWork > 0 ? Math.min(64, durableWork) : requested);
    }

    static Choice missing(LocalPlayer player, Set<Block> blocks, int work,
                          long iron, long diamonds, int preferredTierCap) {
        return missing(inventory(player), blocks.stream().map(Block::defaultBlockState).toList(),
                work, iron, diamonds, preferredTierCap);
    }

    // 小于四单位的开局工作不主动升级；较大工作先选一种来源的工具族和最低等级，再结合存量偏好找参考工具。
    static Choice missing(List<ItemStack> inventory, List<BlockState> sources, int work,
                          long iron, long diamonds, int preferredTierCap) {
        if (work < BATCH_SIZE) return null;
        BlockState source = sources.stream()
                .filter(state -> !family(state).equals("harvesting_tool"))
                .min(Comparator.comparingInt(WorkToolPreparation::requiredTier)
                        .thenComparing(state -> BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()))
                .orElse(null);
        if (source == null) return null;
        int baseline = Math.max(1, requiredTier(source));
        int preferred = diamonds >= ABUNDANT_DIAMONDS ? 3 : iron >= ABUNDANT_IRON ? 2 : 1;
        int tier = Math.max(baseline, Math.min(preferred, preferredTierCap));
        String material = switch (tier) { case 3 -> "diamond"; case 2 -> "iron"; default -> "stone"; };
        ResourceLocation id = ResourceLocation.withDefaultNamespace(material + "_" + family(source));
        ItemStack reference = new ItemStack(BuiltInRegistries.ITEM.get(id));
        if (reference.isEmpty()) return null;
        int remaining = Math.min(64, Math.max(16, work + 8));
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty() && (!stack.isDamageableItem()
                    || stack.getMaxDamage() - stack.getDamageValue() >= remaining)
                    && (!source.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(source))
                    && stack.getDestroySpeed(source) >= reference.getDestroySpeed(source)) {
                return null;
            }
        }
        return new Choice(new SemanticSourceKnowledge.ToolRequirement(
                List.of(id), family(source), material), tier > baseline);
    }

    private static int requiredTier(BlockState state) { return SemanticSourceKnowledge.tierRank(state); }
    private static String family(BlockState state) { return SemanticSourceKnowledge.toolFamily(state); }
}
