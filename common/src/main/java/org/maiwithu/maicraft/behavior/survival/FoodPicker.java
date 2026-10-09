// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.ReadsItemTags;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;

/**
 * 挑哪种吃的判断：点名的只认点名的那种；没点名时像真人翻背包——
 * 无效果的食物优先，按补的多少（营养加饱和度）从高到低排；
 * 身上只剩带效果的食物时，挑对身体最有益的（全是有益效果的优先，
 * 腐肉这类带害的留到真没别的时），吃下去的效果由任务如实写进结果。
 *
 * <p>自己饿了顺手吃（{@link #forHunger}）和 LLM 让吃（{@link #autoPick}、{@link #named}）用同一套事实，
 * 只是顺手吃挑得更省：只吃普通食物，按还差多少挑补得刚好的。
 *
 * <p>纯函数：只看随身带着哪些食物、各自的游戏数值，给结论，不动手也不问游戏。
 */
public final class FoodPicker {

    private FoodPicker() {}

    /**
     * 身上带着的一种能吃的：哪种、有多少、游戏数值是什么。
     * 多少件只给结果说明用，挑哪种不看数量。
     *
     * @param itemId 物品注册 ID
     * @param count  身上共有几件（主背包加副手）
     * @param value  游戏的食物组件数值
     */
    public record Carried(String itemId, int count, ReadsFoodValues.FoodValue value) {}

    /** 把身上的物品堆按游戏的食物组件筛成能吃的；不是食物的跳过。 */
    public static List<Carried> carried(Collection<BackpackStack> stacks, ReadsFoodValues foods) {
        List<Carried> candidates = new ArrayList<>();
        for (BackpackStack stack : stacks) {
            foods.of(stack.itemId()).ifPresent(value ->
                    candidates.add(new Carried(stack.itemId(), stack.count(), value)));
        }
        return candidates;
    }

    /**
     * 普通食物：吃了不带任何效果、饱腹时也不能吃。金苹果、紫颂果这类饱腹也能吃的要么珍贵、要么吃了会传送，
     * 腐肉、毒马铃薯、河豚带害，都不算；饿了顺手吃只吃这一类。
     */
    public static boolean plain(ReadsFoodValues.FoodValue value) {
        return value.nutrition() > 0 && value.effects().isEmpty() && !value.canAlwaysEat();
    }

    /** 吃了不伤身：不带有害效果。打架前数"带了几份口粮"按这个数，金苹果算，腐肉不算。 */
    public static boolean harmless(ReadsFoodValues.FoodValue value) {
        return value.nutrition() > 0 && value.effects().stream().allMatch(ReadsFoodValues.FoodEffect::beneficial);
    }

    /**
     * 自己饿了顺手吃一口：只在普通食物里挑，按饱食度还差多少挑补得刚好的——补得进去的里面挑最多的，
     * 都会溢出就挑溢出最少的，不拿一块牛排去补一格饱食度。没有普通食物时，只有饱食度见底、
     * 已经在掉血（starving）才按 {@link #autoPick} 的顺序什么都吃；否则不吃，交给弄吃的或 LLM。
     */
    public static Optional<String> forHunger(List<Carried> carried, int foodLevel, boolean starving) {
        int missing = Math.max(1, 20 - foodLevel);
        Optional<String> plainPick = carried.stream()
                .filter(candidate -> plain(candidate.value()))
                .min(Comparator.comparingInt(candidate -> waste(candidate.value().nutrition(), missing)))
                .map(Carried::itemId);
        if (plainPick.isPresent() || !starving) return plainPick;
        return autoPick(carried);
    }

    // 补得进去的按还空着多少算（越小越好）；会溢出的一律排在后面，溢出越少越好。
    private static int waste(int nutrition, int missing) {
        return nutrition <= missing ? missing - nutrition : 100 + nutrition - missing;
    }

    /**
     * 点名了要吃哪种：只找点名的那种（具体物品按 ID，# 开头的按标签匹配）。
     * 身上没有时为 empty。
     */
    public static Optional<String> named(List<Carried> carried, String specifier, ReadsItemTags tags) {
        return carried.stream()
                .filter(candidate -> wanted(candidate.itemId(), specifier, tags))
                .map(Carried::itemId)
                .findFirst();
    }

    /**
     * 没点名：在身上的食物里挑一种。顺序是"无效果优先，然后有益效果，最后带害的"，
     * 同档里按营养加饱和度从高到低——真没别的了，腐肉也吃，但效果会如实写进结果。
     */
    public static Optional<String> autoPick(List<Carried> carried) {
        return carried.stream()
                .sorted(FoodPicker::byWholesomeness)
                .map(Carried::itemId)
                .findFirst();
    }

    // 档次：无效果的最先；带效果的全有益的次之；带害的垫底。同档里补得多的在前。
    private static int byWholesomeness(Carried left, Carried right) {
        int byEffect = effectRank(left) - effectRank(right);
        if (byEffect != 0) return byEffect;
        float gainLeft = left.value().nutrition() + left.value().saturation();
        float gainRight = right.value().nutrition() + right.value().saturation();
        return Float.compare(gainRight, gainLeft);
    }

    private static int effectRank(Carried candidate) {
        List<ReadsFoodValues.FoodEffect> effects = candidate.value().effects();
        if (effects.isEmpty()) return 0;
        boolean anyHarmful = effects.stream().anyMatch(effect -> !effect.beneficial());
        return anyHarmful ? 2 : 1;
    }

    // 点名匹配：具体物品按 ID 相等，# 开头的按这个物品挂着的标签。
    private static boolean wanted(String itemId, String specifier, ReadsItemTags tags) {
        if (specifier.startsWith("#")) {
            return tags.tagsOf(itemId).contains(specifier.substring(1));
        }
        return itemId.equals(specifier);
    }
}
