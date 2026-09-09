package org.maiwithu.maicraft.core.build;

import org.maiwithu.maicraft.agent.tool.ToolArgs;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * 把材料字符串变成选料规则，例如 stone_bricks*8,mossy_stone_bricks*2 表示大约八成石砖、两成苔石砖。
 * 每个坐标用固定计算结果选材料，因此同一份规则重新预览或继续施工时不会重新洗牌。
 * 比例用于分配每格的选择机会，不保证一面十格的墙恰好分成八格和两格。
 */
public final class BuildPalette {

    /** 单项:方块 + 对应物品 + 权重。 */
    public record Entry(Block block, Item item, String label, int weight) {}

    private final List<Entry> entries;
    private final int totalWeight;

    private BuildPalette(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        // 总权重用 int 相加，没有溢出检查；超大权重可能把总数算小，Math.max 只能保证最后至少为 1。
        int sum = 0;
        for (Entry e : entries) {
            sum += e.weight();
        }
        this.totalWeight = Math.max(1, sum);
    }

    /**
     * 解析方块参数。
     *
     * @param spec {@code "oak_planks"} 或 {@code "stone*8, mossy_cobblestone*2, cobblestone"}
     */
    public static BuildPalette parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("block_id must not be empty");
        }
        List<Entry> entries = new ArrayList<>();
        for (String part : spec.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            int weight = 1;
            int star = token.lastIndexOf('*');
            if (star > 0) {
                String tail = token.substring(star + 1).trim();
                try {
                    // 省略权重默认为 1；写成 0 或负数也会改成 1，并不表示禁用这种材料。
                    weight = Math.max(1, Integer.parseInt(tail));
                    token = token.substring(0, star).trim();
                } catch (NumberFormatException ignored) {
                    // 不是权重后缀(方块名里本来就带星号的情形),整段当方块名
                }
            }
            entries.add(toEntry(token, weight));
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("block_id must name at least one block");
        }
        return new BuildPalette(entries);
    }

    private static Entry toEntry(String id, int weight) {
        return resolve(id, weight);
    }

    /**
     * 按方块名字查注册表，并找施工需要携带的物品。空气允许作为“清空这一格”的目标。
     * 未知名字、把状态写进名字的写法，以及建造功能不支持的方块都会在这里被拒绝。
     * 这条入口没有世界信息，只用 materialItem(block) 查材料；蓝图导入可以另用带世界参数的方法。
     */
    public static Entry resolve(String id, int weight) {
        String trimmed = id.trim();
        if (trimmed.indexOf('[') >= 0) {
            throw new IllegalArgumentException(trimmed
                    + " — block_id takes a plain block id; put the state in `properties`"
                    + " (e.g. block_id \"spruce_stairs\" with properties {facing: south})");
        }
        var rl = net.minecraft.resources.ResourceLocation.tryParse(trimmed);
        if (rl == null) {
            throw new IllegalArgumentException("not a valid block id: " + trimmed);
        }
        if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(rl)) {
            throw new IllegalArgumentException("unknown block: " + trimmed);
        }
        Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(rl);
        // 能不能建走同一个判据(图纸入口那边拿它当跳过条件,这边拿它当拒绝理由)
        String no = org.maiwithu.maicraft.core.build.BuildStates
                .unbuildableReason(block.defaultBlockState());
        if (no != null) {
            throw new IllegalArgumentException(trimmed + " — " + no);
        }
        Item item = org.maiwithu.maicraft.core.build.BuildStates.materialItem(block);
        if (item == Items.AIR && block != Blocks.AIR) {
            throw new IllegalArgumentException(trimmed + " is not a placeable block");
        }
        String label = trimmed.contains(":") ? trimmed.split(":", 2)[1] : trimmed;
        return new Entry(block, item, label, weight);
    }

    /** 只有一种方块吗——单色时可以跳过逐格取样。 */
    public boolean isSingle() {
        return entries.size() == 1;
    }

    public Entry first() {
        return entries.get(0);
    }

    /**
     * 按坐标算出一个落在总权重内的数，再依次减去各材料的权重；先减到负数的材料被选中。
     * 只有一种材料时直接返回它，不做坐标计算。
     */
    public Entry pick(BlockPos pos) {
        if (entries.size() == 1) {
            return entries.get(0);
        }
        long h = positionHash(pos.getX(), pos.getY(), pos.getZ());
        int roll = (int) Math.floorMod(h, totalWeight);
        for (Entry e : entries) {
            roll -= e.weight();
            if (roll < 0) {
                return e;
            }
        }
        return entries.get(entries.size() - 1);
    }

    /**
     * 把三个坐标混合成一个固定数字，供混合选料和撒点共同使用。
     * 这里不保存随机种子；相同坐标每次得到相同数字。
     */
    public static long positionHash(int x, int y, int z) {
        long h = x * 341873128712L
                + y * 1971648029L
                + z * 132897987541L;
        h ^= h >>> 29;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 32;
        return h;
    }
}
