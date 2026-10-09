// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;

/**
 * 用东西的判定：参数组合行不行、目标会不会炸、交互的结论怎么变成任务结果。
 *
 * <p>纯判断，不碰游戏：参数组合在计划阶段一次报全；会爆炸的组合（下界与末地的床、
 * 主世界与末地的重生锚）直接按危险结束，一次都不点；交互结论按生效、没生效、
 * 出乎预料、没能确认四种走向结算，没能确认的交互绝不再赌第二次。
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

    /**
     * 给了不接的物品时在计划阶段拒绝：凭时机松手的指向对应的专门能力；
     * 刷子要对着方块一直按住，这一路的按住还没接上，如实说做不了，不点一下就冒充刷过。
     */
    static Optional<Rejection> rejectedItem(String item) {
        if (item == null) {
            return Optional.empty();
        }
        String id = item.toLowerCase(Locale.ROOT);
        if (TIMED_RELEASE_ITEMS.contains(id)) {
            return Optional.of(new Rejection(
                    item + " 要凭时机松手（弓、弩、三叉戟、盾牌、望远镜、钓竿），use 不接受",
                    id.endsWith("fishing_rod") ? "钓鱼用 fish" : "打怪用 fight"));
        }
        if (id.equals("minecraft:brush")) {
            return Optional.of(new Rejection("刷子要对着方块一直按住直到刷完，use 还接不了这种按法",
                    "可疑的沙子、沙砾先别用 use 刷"));
        }
        return Optional.empty();
    }

    /**
     * 会爆炸的组合：下界、末地的床和主世界、末地的重生锚右键会炸。
     * 维度传角色此刻所在的维度（目标就在身边才点得到）；读不到维度时不冒充知道，按不炸处理。
     */
    static Optional<Problem> explosionDanger(String dimension, String blockTypeId) {
        if (dimension == null || blockTypeId == null) {
            return Optional.empty();
        }
        if ((blockTypeId.endsWith(":bed") || blockTypeId.endsWith("_bed"))
                && (dimension.equals("minecraft:the_nether") || dimension.equals("minecraft:the_end"))) {
            return Optional.of(Problem.of(Problem.Kind.DANGER,
                    "在" + dimensionName(dimension) + "右键床会爆炸，不点", null));
        }
        if (blockTypeId.endsWith(":respawn_anchor")
                && (dimension.equals("minecraft:overworld") || dimension.equals("minecraft:the_end"))) {
            return Optional.of(Problem.of(Problem.Kind.DANGER,
                    "在" + dimensionName(dimension) + "右键重生锚会爆炸，不点", null));
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
        /** 生效了：记进变化，做满次数就完成。 */
        record Applied() implements Settlement {}

        /** 出乎预料：交互本身生效了，世界变成了预料之外的样子；结果照实写，按完成收尾，设计对不对交给 LLM。 */
        record Unexpected(String scene) implements Settlement {}

        /** 没能确认：部分完成，进未确认清单，绝不再试。 */
        record Unconfirmed(String scene) implements Settlement {}

        /** 没生效，带该上报的问题。 */
        record NotApplied(Problem problem) implements Settlement {}
    }

    /**
     * 把交互结论结算成走向。
     *
     * @param result      交互动作给出的结论与现场
     * @param gameMessage 出手后动作栏新冒出的提示语（"箱子已上锁"、领地保护）；没有为空
     */
    static Settlement settle(InteractionResult result, Optional<String> gameMessage) {
        return switch (result.verdict()) {
            case APPLIED -> new Settlement.Applied();
            case UNEXPECTED -> new Settlement.Unexpected(result.scene());
            case UNCONFIRMED -> new Settlement.Unconfirmed(result.scene());
            // 游戏说了为什么就附原话；什么都没说、目标也没变，就是这样用没有效果。
            case NOT_APPLIED -> new Settlement.NotApplied(gameMessage
                    .map(message -> Problem.of(Problem.Kind.REFUSED_BY_GAME, "游戏拒绝了：" + message, null))
                    .orElseGet(() -> Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                            "这样用没有效果：" + result.scene(), null)));
        };
    }

    /**
     * 某一次没有效果就停下：一次都没做成按失败；做成过几次按部分完成，写明做成了几次，
     * 不把没做成的次数也算进去。
     */
    static TaskResult stopped(long appliedTimes, long count, Problem problem) {
        if (appliedTimes == 0) {
            return TaskResult.failed("用东西没有生效：" + problem.message(), problem);
        }
        return TaskResult.builder(TaskResult.Status.PARTIAL,
                        "确认生效了 " + appliedTimes + " 次，第 " + (appliedTimes + 1) + " 次没有效果，停下了（要做 "
                                + count + " 次）")
                .problem(problem).build();
    }

    /** 这一下没能确认生效没有：部分完成，写明前面确认过几次；不盲目重做，免得再花一次材料去赌。 */
    static TaskResult unconfirmed(long appliedTimes, String scene) {
        String before = appliedTimes == 0 ? "" : "前面确认生效了 " + appliedTimes + " 次；";
        return TaskResult.builder(TaskResult.Status.PARTIAL,
                before + "这一下没能确认有没有生效，不盲目重做：" + scene).build();
    }

    private UseDecider() {}
}
