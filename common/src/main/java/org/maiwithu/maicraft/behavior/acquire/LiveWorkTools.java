// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 效率工具读端的生产实现：方块的对口工具与等级按挖掘分组、需要等级标签读，身上的工具按真实的挖掘速度和剩余耐久比，
 * 只看主背包与副手。读不到角色或方块时什么都不备。
 */
public final class LiveWorkTools implements ReadsWorkTools {

    private final Supplier<PlayerContext> context;

    public LiveWorkTools(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    // 对口的基准工具（至少石制）；身上有木制工具在干这活时活再少也换，已有同样快、耐久够的就不备。
    @Override public Optional<String> toolFor(String blockTypeId, int work) {
        BlockState state = state(blockTypeId);
        List<ItemStack> carried = carried();
        if (state == null || carried == null) return Optional.empty();
        Optional<String> reference = WorkTools.reference(family(state), tier(state));
        if (reference.isEmpty()) return Optional.empty();
        ItemStack ref = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(reference.get())));
        boolean woodenWorks = false;
        boolean goodEnough = false;
        for (ItemStack stack : carried) {
            if (stack.isEmpty() || !works(stack, state)) continue;
            if (wooden(stack)) woodenWorks = true;
            int left = stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
            if (stack.getDestroySpeed(state) >= ref.getDestroySpeed(state) && left >= WorkTools.durabilityNeeded(work)) {
                goodEnough = true;
            }
        }
        return WorkTools.toPrepare(reference, work, woodenWorks, goodEnough);
    }

    // 开局还缺几件：原木按木板、木棍、工作台、木镐算；石头按有镐时手上的石料算。
    @Override public int bootstrap(String blockTypeId) {
        BlockState state = state(blockTypeId);
        List<ItemStack> carried = carried();
        if (state == null || carried == null) return 0;
        int logs = 0, planks = 0, sticks = 0, stone = 0;
        boolean table = false, pickaxe = false;
        for (ItemStack stack : carried) {
            if (stack.is(ItemTags.LOGS)) logs += stack.getCount();
            if (stack.is(ItemTags.PLANKS)) planks += stack.getCount();
            if (stack.is(Items.STICK)) sticks += stack.getCount();
            if (stack.is(Items.CRAFTING_TABLE)) table = true;
            if (stack.getItem() instanceof PickaxeItem
                    && (!stack.isDamageableItem() || stack.getMaxDamage() - stack.getDamageValue() >= 3)) pickaxe = true;
            if (stack.is(ItemTags.STONE_TOOL_MATERIALS)) stone += stack.getCount();
        }
        boolean stoneLike = state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(Blocks.COBBLESTONE);
        return WorkTools.bootstrap(state.is(BlockTags.LOGS), stoneLike, logs, planks, sticks, table, pickaxe, stone);
    }

    // 主背包 36 格加副手；角色不在世界里给 null。
    private List<ItemStack> carried() {
        PlayerContext player = context.get();
        if (player == null || player.localPlayer() == null) return null;
        var inventory = player.localPlayer().getInventory();
        List<ItemStack> stacks = new ArrayList<>(inventory.items);
        stacks.add(player.localPlayer().getOffhandItem());
        return stacks;
    }

    private static BlockState state(String blockTypeId) {
        ResourceLocation id = ResourceLocation.tryParse(blockTypeId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return null;
        return BuiltInRegistries.BLOCK.get(id).defaultBlockState();
    }

    private static String family(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return "pickaxe";
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) return "axe";
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) return "shovel";
        return "";
    }

    private static int tier(BlockState state) {
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) return 3;
        if (state.is(BlockTags.NEEDS_IRON_TOOL)) return 2;
        if (state.is(BlockTags.NEEDS_STONE_TOOL)) return 1;
        return 0;
    }

    // 这件东西能干这活：比徒手快，要求正确工具的方块还得够格掉落。
    private static boolean works(ItemStack stack, BlockState state) {
        return stack.getDestroySpeed(state) > 1 && (!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state));
    }

    private static boolean wooden(ItemStack stack) {
        return stack.getItem() instanceof TieredItem tool
                && tool.getTier().getIncorrectBlocksForDrops().equals(BlockTags.INCORRECT_FOR_WOODEN_TOOL);
    }
}
