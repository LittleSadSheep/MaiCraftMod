// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 挑容器：在一批候选容器里决定存进哪只、按什么顺序试的纯函数。
 *
 * <p>排序按玩家的习惯：已经放着同种东西的排最前（同类东西放一起），其次还有空位的，
 * 再按走过去的代价就近。别人的箱子不乱塞，除非 LLM 点名了它；界面布局认不出的不点；
 * 盖子被天然方块压住且许可允许时先挖开再存，是别人放的方块就要先问。
 * 上面坐着猫的箱子开不了，换下一只。
 *
 * <p>一只都挑不出来时不把事实揉成一团：只有别人的容器就给需要同意并列出是哪几只，
 * 其余情况给没找到，并把淘汰的原因写清楚。
 */
public final class ContainerChooser {

    /** 容器盖子上方的样子。 */
    public enum Lid {
        /** 上方没有会压住盖子的方块。 */
        CLEAR,
        /** 被树叶这类天然方块压住；许可允许就先挖开。 */
        BLOCKED_BY_NATURAL,
        /** 被别人放的方块压住；动它之前要问。 */
        BLOCKED_BY_FOREIGN
    }

    /**
     * 一只候选容器，事实由调用方读好：世界记忆、现场方块、归属与许可。
     *
     * @param name          给结果看的一句话，例如"家门口的箱子"
     * @param blockTypeId   方块注册 ID；界面布局认不出的类型不点
     * @param at            容器方块的位置
     * @param knownContents 上次打开时看到的内容；null 表示没开过、内容不知道
     * @param hasFreeSpace  现场看还有没有空位；不知道时按 true 传，排到同类后面由现场核实
     * @param walkCost      走过去的代价，越小越近
     * @param ownedByOther  是不是别人的容器；LLM 点名的候选不传这个
     * @param layoutKnown   界面布局认不认得；认不出的界面一格都不点
     * @param lid           盖子上方的样子
     * @param catSitting    上面是不是坐着猫；坐着猫的箱子开不了
     */
    public record Candidate(String name, String blockTypeId, WorldPosition at,
            List<String> knownContents, boolean hasFreeSpace, double walkCost,
            boolean ownedByOther, boolean layoutKnown, Lid lid, boolean catSitting) {
        public Candidate {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(blockTypeId, "blockTypeId");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(lid, "lid");
            knownContents = knownContents == null ? null : List.copyOf(knownContents);
        }
    }

    /** 一只入选的容器：先按它试，挖了盖子再开时挖开标记为真。 */
    public record Entry(Candidate candidate, boolean digLidFirst) {}

    /** 挑选的结论。 */
    public sealed interface Pick {
        /** 有能用的容器，按试的顺序排好。 */
        record Chosen(List<Entry> ordered) implements Pick {
            public Chosen {
                ordered = List.copyOf(ordered);
            }
        }

        /** 只挑得到别人的容器（或被别人放的方块压住的盖子），要 LLM 点名或同意；列出是哪几只。 */
        record NeedApproval(List<String> others, List<String> blockedLids) implements Pick {
            public NeedApproval {
                others = List.copyOf(others);
                blockedLids = List.copyOf(blockedLids);
            }
        }

        /** 一只都没有；写明查过哪些、各自为什么不行。 */
        record NotFound(String searched) implements Pick {}
    }

    private ContainerChooser() {}

    /**
     * 从候选里挑出能用的容器并排好顺序。
     *
     * @param candidates     这片地方radius内找得到的容器，含世界记忆里的（到场还要核对）
     * @param itemTypeId     这次要存的东西的代表物品 ID，用来认"已经放着同种东西"的箱子；不知道传 null
     * @param mayDigNaturalLid 许可是否允许挖开压着盖子的天然方块
     */
    public static Pick choose(List<Candidate> candidates, String itemTypeId, boolean mayDigNaturalLid) {
        List<Entry> usable = new ArrayList<>();
        List<String> others = new ArrayList<>();
        List<String> blockedLids = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.ownedByOther()) {
                others.add(describe(candidate));
                continue;
            }
            if (!candidate.layoutKnown()) {
                rejected.add(describe(candidate) + "（界面认不出，不点）");
                continue;
            }
            if (candidate.catSitting()) {
                rejected.add(describe(candidate) + "（上面坐着猫，开不了）");
                continue;
            }
            if (candidate.lid() == Lid.BLOCKED_BY_FOREIGN) {
                blockedLids.add(describe(candidate) + "（盖子上方是别人放的方块）");
                continue;
            }
            if (candidate.lid() == Lid.BLOCKED_BY_NATURAL && !mayDigNaturalLid) {
                blockedLids.add(describe(candidate) + "（盖子被天然方块压住，许可不允许挖）");
                continue;
            }
            boolean digLidFirst = candidate.lid() == Lid.BLOCKED_BY_NATURAL;
            usable.add(new Entry(candidate, digLidFirst));
        }
        if (!usable.isEmpty()) {
            usable.sort(sortOrder(itemTypeId));
            return new Pick.Chosen(usable);
        }
        // 一只能用的都没有：别人的要问，其余的把淘汰原因摊开说清。
        if (!others.isEmpty() || !blockedLids.isEmpty()) {
            return new Pick.NeedApproval(others, blockedLids);
        }
        String searched = candidates.isEmpty()
                ? "附近没有找到任何容器"
                : "附近有 " + candidates.size() + " 只容器，都不行：" + String.join("；", rejected);
        return new Pick.NotFound(searched);
    }

    // 排序：已经放着同种东西的 > 还有空位的 > 走过去的代价小的；同代价按位置排，保证顺序稳定。
    private static Comparator<Entry> sortOrder(String itemTypeId) {
        Comparator<Entry> bySameItem = Comparator.comparing(entry ->
                itemTypeId != null && entry.candidate().knownContents() != null
                        && entry.candidate().knownContents().contains(itemTypeId) ? 0 : 1);
        Comparator<Entry> byFreeSpace = Comparator.comparing(entry -> entry.candidate().hasFreeSpace() ? 0 : 1);
        Comparator<Entry> byCost = Comparator.comparingDouble((Entry entry) -> entry.candidate().walkCost())
                .thenComparing(entry -> entry.candidate().at().toString());
        return bySameItem.thenComparing(byFreeSpace).thenComparing(byCost);
    }

    private static String describe(Candidate candidate) {
        return candidate.name() + "（" + candidate.at().x() + ", " + candidate.at().y() + ", "
                + candidate.at().z() + "）";
    }
}
