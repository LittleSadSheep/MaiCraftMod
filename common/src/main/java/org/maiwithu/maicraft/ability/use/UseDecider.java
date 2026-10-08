// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.interaction.InteractionVerdict;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 用东西的手势判定：参数组合行不行、目标会不会炸、交互的结论怎么变成任务结果。
 *
 * <p>纯判断，不碰游戏：参数组合在计划阶段一次报全；会爆炸的组合（下界与末地的床、
 * 主世界与末地的重生锚）直接按危险结束，一次都不点；交互结论按生效、没生效、
 * 出乎预料、没能确认四种走向结算，会用掉东西的交互没能确认时绝不再试。
 */
final class UseDecider {

    /** 参数组合的问题：全部一次报全，不挤牙膏。 */
    static List<String> invalidCombinations(boolean hasTarget, String item, String block, String entity,
            List<String> textLines) {
        List<String> problems = new ArrayList<>();
        if (block != null && entity != null) {
            problems.add("block 与 entity 不能同时给：要点的是方块还是实体，选一个");
        }
        if (!textLines.isEmpty() && item != null) {
            problems.add("text 与 item 不能同时给：写字必须空手");
        }
        if (!hasTarget && block == null && entity == null && item == null) {
            problems.add("target、block、entity、item 一个都没给：不知道要对什么用");
        }
        return problems;
    }

    /** 没有自然结束、要凭时机松手的物品不接，写明该用哪个能力。 */
    static final Set<String> TIMED_RELEASE_ITEMS = Set.of(
            "minecraft:bow", "minecraft:crossbow", "minecraft:trident",
            "minecraft:shield", "minecraft:spyglass", "minecraft:fishing_rod");

    /** 计划阶段的一次拒绝：参数怎么改能继续。 */
    record Rejection(String message, String suggestion) {}

    /** 给了不接的物品时在计划阶段拒绝，指向对应的专门能力。 */
    static Optional<Rejection> rejectedItem(String item) {
        if (item == null || !TIMED_RELEASE_ITEMS.contains(item.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of(new Rejection(
                item + " 要凭时机松手（弓、弩、三叉戟、盾牌、望远镜、钓竿），use 不接受",
                item.endsWith("fishing_rod") ? "钓鱼用 fish" : "打怪用 fight"));
    }

    /**
     * 会爆炸的组合：下界、末地的床和主世界、末地的重生锚右键会炸。
     * 维度传目标所在维度（null 表示角色当前维度）；不点，按危险结束。
     */
    static Optional<Problem> explosionDanger(String dimension, String blockTypeId) {
        String where = dimension == null ? "minecraft:overworld" : dimension;
        if ((blockTypeId.endsWith(":bed") || blockTypeId.endsWith("_bed"))
                && (where.equals("minecraft:the_nether") || where.equals("minecraft:the_end"))) {
            return Optional.of(Problem.of(Problem.Kind.DANGER,
                    "在" + dimensionName(where) + "右键床会爆炸，不点", null));
        }
        if (blockTypeId.endsWith(":respawn_anchor")
                && (where.equals("minecraft:overworld") || where.equals("minecraft:the_end"))) {
            return Optional.of(Problem.of(Problem.Kind.DANGER,
                    "在" + dimensionName(where) + "右键重生锚会爆炸，不点", null));
        }
        return Optional.empty();
    }

    private static String dimensionName(String dimension) {
        return switch (dimension) {
            case "minecraft:the_nether" -> "下界";
            case "minecraft:the_end" -> "末地";
            case "minecraft:overworld" -> "主世界";
            default -> dimension;
        };
    }

    /** 交互结论结算后的走向。 */
    sealed interface Settlement {
        /** 生效了：做满次数就完成。 */
        record Applied() implements Settlement {}

        /** 出乎预料：交互本身生效了，世界变成了预料之外的样子，结果照实写、照样算完成。 */
        record Unexpected(String scene) implements Settlement {}

        /** 没能确认：部分完成，绝不再试会用掉东西的交互。 */
        record Unconfirmed(String scene) implements Settlement {}

        /** 没生效，带该上报的问题。 */
        record NotApplied(Problem problem) implements Settlement {}
    }

    /**
     * 把交互结论结算成走向。
     *
     * @param result        交互动作给出的结论与现场
     * @param gameExplained 游戏给了拒绝的提示语（动作栏消息、上锁、领地保护）
     * @param consumesItem  这次交互会不会用掉手上的东西；没能确认时它决定绝不重试
     */
    static Settlement settle(InteractionResult result, boolean gameExplained, boolean consumesItem) {
        return switch (result.verdict()) {
            case APPLIED -> new Settlement.Applied();
            case UNEXPECTED -> new Settlement.Unexpected(result.scene());
            case UNCONFIRMED -> new Settlement.Unconfirmed(result.scene());
            case NOT_APPLIED -> new Settlement.NotApplied(gameExplained
                    ? Problem.of(Problem.Kind.REFUSED_BY_GAME, result.scene(), null)
                    : Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "这样用没有效果：" + result.scene(), null));
        };
    }

    /** 没能确认的交互要不要就此停手：会用掉东西的绝不再试，其余按没做成一次处理。 */
    static boolean mustStopAfterUnconfirmed(boolean consumesItem) {
        return consumesItem;
    }

    /** 交互结论是不是已经生效（含出乎预料：交互本身都生效了）。 */
    static boolean applied(InteractionVerdict verdict) {
        return verdict == InteractionVerdict.APPLIED || verdict == InteractionVerdict.UNEXPECTED;
    }

    private UseDecider() {}
}
