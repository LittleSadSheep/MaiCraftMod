package org.maiwithu.maicraft.core.pathing.moves;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.maiwithu.maicraft.core.pathing.settings.NavSettings;

import net.minecraft.core.Holder;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 用创建时的九格快捷栏估算不同方块的挖掘速度，当前供方块费用判断使用；这里不会实际把工具拿到手上。
 * 按速度、耐久设置和同速时的偏好挑槽位；按方块种类缓存结果，计算时使用该方块默认状态。
 */
public class ToolSet {

    /** 每种方块 → 最优工具挖掘速度的缓存。 */
    private final Map<Block, Double> breakStrengthCache;

    /** 缓存缺失时的计算函数(构造时折入药水修正,避免逐次判断)。 */
    private final Function<Block, Double> backendCalculation;

    /** 构造时逐格 copy 的快捷栏九格。 */
    private final ItemStack[] hotbar;

    /** 构造时的手持槽位(autoTool 关闭的成本口径用)。 */
    private final int selectedSlot;

    /** true 时功能方块保护乘数失效(强制破坏语义)。 */
    private final boolean ignoreBreakingProtection;

    // 构造时取样的设置(计算中途改设置不影响本次搜索)
    private final boolean autoTool;
    private final boolean useSwordToMine;
    private final boolean itemSaver;
    private final int itemSaverThreshold;
    private final List<Block> blocksToAvoidBreaking;
    private final double avoidBreakingMultiplier;

    public ToolSet(LocalPlayer player) {
        this(player, false);
    }

    public ToolSet(LocalPlayer player, boolean ignoreBreakingProtection) {
        this(snapshotHotbar(player), player.getInventory().selected, ignoreBreakingProtection,
                NavSettings.get().considerPotionEffects ? potionAmplifier(player) : 1.0);
    }

    /**
     * 快照直构(线程安全冒烟测试与自备快照的调用方用):调用方给出
     * 快捷栏九格(应为不再被改写的副本)、选中槽位与药水修正倍率。
     */
    public ToolSet(ItemStack[] hotbarSnapshot, int selectedSlot,
                   boolean ignoreBreakingProtection, double potionAmplifier) {
        this.breakStrengthCache = new HashMap<>();
        // 这个构造入口只复制数组；从玩家创建时，前面的 snapshotHotbar 已逐件复制物品。
        this.hotbar = hotbarSnapshot.clone();
        this.selectedSlot = selectedSlot;
        this.ignoreBreakingProtection = ignoreBreakingProtection;

        NavSettings settings = NavSettings.get();
        this.autoTool = settings.autoTool;
        this.useSwordToMine = settings.useSwordToMine;
        this.itemSaver = settings.itemSaver;
        this.itemSaverThreshold = settings.itemSaverThreshold;
        this.blocksToAvoidBreaking = List.copyOf(settings.blocksToAvoidBreaking());
        this.avoidBreakingMultiplier = settings.avoidBreakingMultiplier;

        if (potionAmplifier != 1.0) {
            Function<Double, Double> amplify = x -> potionAmplifier * x;
            backendCalculation = amplify.compose(this::getBestDestructionTime);
        } else {
            backendCalculation = this::getBestDestructionTime;
        }
    }

    /** 快捷栏九格逐格 copy(主线程调用;worker 之后只读副本)。 */
    private static ItemStack[] snapshotHotbar(LocalPlayer player) {
        ItemStack[] hotbar = new ItemStack[9];
        for (int i = 0; i < 9; i++) {
            hotbar[i] = player.getInventory().getItem(i).copy();
        }
        return hotbar;
    }

    /**
     * 用快捷栏最优工具挖该方块的速度(1/挖掘tick数)。
     * 不可破坏时为负值。
     */
    public double getStrVsBlock(BlockState state) {
        return breakStrengthCache.computeIfAbsent(state.getBlock(), backendCalculation);
    }

    /**
     * 材质廉价度序:按材质物品标签(木板/圆石/铁锭…)从廉到贵排序,
     * 命中哪个标签就返回其序号;不命中任何标签返回 -1(工具本体不在
     * 材质标签里,通常恒 -1,平速时先遇到的槽位保持胜出)。
     */
    // 这里比较的是工具物品本身，但这些名称指向修理材料标签；原版石材料标签装的是圆石等材料，并不能据此识别石工具。
    private static final List<TagKey<Item>> MATERIAL_TAGS_PRIORITY = List.of(
            materialTag("wooden_tool_materials"),
            materialTag("stone_tool_materials"),
            materialTag("iron_tool_materials"),
            materialTag("gold_tool_materials"),
            materialTag("diamond_tool_materials"),
            materialTag("netherite_tool_materials")
    );

    private static TagKey<Item> materialTag(String name) {
        return TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                net.minecraft.resources.ResourceLocation.withDefaultNamespace(name));
    }

    private static int getMaterialCost(ItemStack itemStack) {
        for (int i = 0; i < MATERIAL_TAGS_PRIORITY.size(); i++) {
            if (itemStack.is(MATERIAL_TAGS_PRIORITY.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 是否属于武器类物品(剑/斧/三叉戟/重锤)。 */
    private static boolean isWeapon(ItemStack itemStack) {
        Item item = itemStack.getItem();
        return item instanceof SwordItem || item instanceof AxeItem
                || item instanceof TridentItem || item instanceof MaceItem;
    }

    /** 该物品是否带精准采集附魔。 */
    public boolean hasSilkTouch(ItemStack stack) {
        ItemEnchantments enchantments = stack.getEnchantments();
        for (Holder<Enchantment> enchant : enchantments.keySet()) {
            if (enchant.is(Enchantments.SILK_TOUCH) && enchantments.getLevel(enchant) > 0) {
                return true;
            }
        }
        return false;
    }

    public int getBestSlot(Block b, boolean preferSilkTouch) {
        return getBestSlot(b, preferSilkTouch, false);
    }

    /**
     * 快捷栏 9 格中挖该方块的最优槽位(基于构造时的快照)。速度最高
     * 者胜;平速时优先更廉价材质,{@code preferSilkTouch} 时优先精准
     * 采集。autoTool 关闭且用于成本计算时直接返回构造时的手持槽
     * (让路径成本反映真实会用的那件工具)。
     */
    public int getBestSlot(Block b, boolean preferSilkTouch, boolean pathingCalculation) {
        if (!autoTool && pathingCalculation) {
            return selectedSlot;
        }

        int best = 0;
        double highestSpeed = Double.NEGATIVE_INFINITY;
        int lowestCost = Integer.MIN_VALUE;
        boolean bestSilkTouch = false;
        BlockState blockState = b.defaultBlockState();
        for (int i = 0; i < 9; i++) {
            ItemStack itemStack = hotbar[i];
            // 当前这个叫“用剑挖掘”的开关关闭时，会同时跳过剑、斧、三叉戟和重锤。
            if (!useSwordToMine && isWeapon(itemStack)) {
                continue;
            }
            if (itemSaver
                    && itemStack.getDamageValue() + itemSaverThreshold >= itemStack.getMaxDamage()
                    && itemStack.getMaxDamage() > 1) {
                continue;
            }
            double speed = calculateSpeedVsBlock(itemStack, blockState);
            boolean silkTouch = hasSilkTouch(itemStack);
            if (speed > highestSpeed) {
                highestSpeed = speed;
                best = i;
                lowestCost = getMaterialCost(itemStack);
                bestSilkTouch = silkTouch;
            } else if (speed == highestSpeed) {
                int cost = getMaterialCost(itemStack);
                if ((cost < lowestCost && (silkTouch || !bestSilkTouch))
                        || (preferSilkTouch && !bestSilkTouch && silkTouch)) {
                    highestSpeed = speed;
                    best = i;
                    lowestCost = cost;
                    bestSilkTouch = silkTouch;
                }
            }
        }
        // 这里返回槽位编号；当前没有单独表示“所有槽位都被筛掉”的结果，初始编号仍是零。
        return best;
    }

    /** 用最优槽位工具挖该方块的速度(已乘保护方块修正)。 */
    private double getBestDestructionTime(Block b) {
        ItemStack stack = hotbar[getBestSlot(b, false, true)];
        return calculateSpeedVsBlock(stack, b.defaultBlockState()) * avoidanceMultiplier(b);
    }

    /**
     * 受保护功能方块(工作台/熔炉/箱子等)速度乘 0.1,
     * 即挖掘成本 ×10;强制破坏语义下失效。
     */
    private double avoidanceMultiplier(Block b) {
        if (ignoreBreakingProtection) {
            return 1;
        }
        return blocksToAvoidBreaking.contains(b) ? avoidBreakingMultiplier : 1;
    }

    /**
     * 用指定物品挖指定方块的速度(1/挖掘tick数)。
     * hardness&lt;0(基岩类)返回 -1 表示不可破坏。
     * speed&gt;1 时叠加效率附魔的挖掘效率加成;除以 hardness 后,
     * 正确工具(或不需要正确工具)除以 30,错误工具除以 100。
     */
    public static double calculateSpeedVsBlock(ItemStack item, BlockState state) {
        float hardness;
        try {
            hardness = state.getDestroySpeed(null, null);
        } catch (NullPointerException npe) {
            // 取不到硬度的异类方块按不可破坏处理
            return -1;
        }
        if (hardness < 0) {
            return -1;
        }

        float speed = item.getDestroySpeed(state);
        if (speed > 1) {
            ItemEnchantments itemEnchantments = item.getEnchantments();
            OUTER:
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                List<EnchantmentAttributeEffect> effects =
                        enchant.value().getEffects(EnchantmentEffectComponents.ATTRIBUTES);
                for (EnchantmentAttributeEffect e : effects) {
                    if (e.attribute().is(Attributes.MINING_EFFICIENCY.unwrapKey().orElseThrow())) {
                        speed += e.amount().calculate(itemEnchantments.getLevel(enchant));
                        break OUTER;
                    }
                }
            }
        }

        // 把工具速度换成每次更新能挖掉的比例；工具不适合该方块时使用更慢的除数，而不是自动认定完全不能破坏。
        speed /= hardness;
        if (!state.requiresCorrectToolForDrops() || (!item.isEmpty() && item.isCorrectToolForDrops(state))) {
            return speed / 30;
        } else {
            return speed / 100;
        }
    }

    /** 急迫 / 挖掘疲劳药水对挖掘速度的修正倍率(构造时在主线程取样)。 */
    private static double potionAmplifier(LocalPlayer player) {
        double speed = 1;
        if (player.hasEffect(MobEffects.DIG_SPEED)) {
            speed *= 1 + (player.getEffect(MobEffects.DIG_SPEED).getAmplifier() + 1) * 0.2;
        }
        if (player.hasEffect(MobEffects.DIG_SLOWDOWN)) {
            switch (player.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier()) {
                case 0:
                    speed *= 0.3;
                    break;
                case 1:
                    speed *= 0.09;
                    break;
                case 2:
                    speed *= 0.0027; // 原版就是 0.0027,不是 0.027
                    break;
                default:
                    speed *= 0.00081;
                    break;
            }
        }
        return speed;
    }
}
