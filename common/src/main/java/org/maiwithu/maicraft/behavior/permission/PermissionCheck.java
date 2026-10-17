// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Optional;
import java.util.UUID;
import org.maiwithu.maicraft.behavior.retry.QuestionEscalation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 许可检查点：所有改变世界的动作在动手前都到这里问一次，全仓只有这一个检查。
 *
 * <p>两条规矩：
 * <ul>
 * <li>LLM 明确点名的目标（观察编号）视为对这个目标的许可——"拆掉这面墙"点名了那面墙，
 *     即使它是玩家放的也放行；但角色自己挑的目标必须过保护和许可档位两道关。
 *     例外是有主的生物（有名字、驯服、拴绳、圈养）和玩家：伤害它们点了名也要再确认一次，见 {@link #namedCreatureAllowed}。</li>
 * <li>被拒时给 {@code NEED_APPROVAL} 问题，写清需要哪一项许可、涉及哪些格子或生物，
 *     不让 LLM 猜该开哪个开关；要不要升级成向 LLM 提问，走提问升级的判断。</li>
 * </ul>
 *
 * <p>被服务器拒绝（领地保护、权限不够）不在这里：那是游戏的真实结算，属于 {@code REFUSED_BY_GAME}，
 * 交互提交后如实返回。
 */
public final class PermissionCheck {

    /** 改变世界的动作：检查点按动作挑对应的许可档位。 */
    public enum WorldAction {
        /** 挖掉一格方块。 */
        DIG_BLOCK,
        /** 放置一格方块（搭柱子、垫脚、封门）。 */
        PLACE_BLOCK,
        /** 攻击一只生物（打一下、还手）。 */
        FIGHT,
        /** 为了具体需要击杀一只动物（吃的、羊毛、皮革；剪毛挤奶不算）。 */
        KILL_ANIMAL,
        /** 消耗一件稀有物品（末影珍珠、不死图腾、钻石……）。 */
        USE_RARE_ITEM
    }

    private final Protection protection;
    private final ReadsCreatureSituation creatures;

    public PermissionCheck(Protection protection, ReadsCreatureSituation creatures) {
        this.protection = protection;
        this.creatures = creatures;
    }

    /**
     * LLM 这次任务点名的目标能不能按这个动作处理：方块的观察编号视为对那个目标的许可；
     * 位置与地标目标走完整的保护与档位检查。
     *
     * @throws IllegalArgumentException 动作与目标对象配不上（例如对着"往前 100 格"挖方块）、坐标没给高度，
     *         或对点名的生物动手——能力应先把目标对象落实成具体的一格，生物先找回是哪一只再走
     *         {@link #namedCreatureAllowed}
     */
    public Optional<Problem> allows(Permissions permissions, WorldAction action, Target target) {
        if (action == WorldAction.USE_RARE_ITEM) return rareItemAllowed(permissions);
        switch (target) {
            case Target.Seen seen -> {
                // 点名即许可：LLM 说拆哪面墙就拆哪面。点名的生物还要看有没有主，得先找回是哪一只。
                if (action == WorldAction.FIGHT || action == WorldAction.KILL_ANIMAL) {
                    throw new IllegalArgumentException("点名的生物要先找回是哪一只，再用 namedCreatureAllowed 看它有没有主");
                }
                return Optional.empty();
            }
            case Target.Position position -> {
                requireBlockAction(action);
                // 没给高度的坐标是一整列，不是一格：先落实到具体哪一格再来问。
                if (position.y() == null) {
                    throw new IllegalArgumentException("坐标没给高度，先落实到具体哪一格再检查许可");
                }
                return blockAllowed(permissions, action,
                        new WorldPosition(position.x(), position.y(), position.z(), position.dimension()), null);
            }
            case Target.Landmark landmark -> {
                requireBlockAction(action);
                // 地标还不知道在哪（没记过）：位置落实不了，等能力先找到它、拿位置再来检查。
                var spot = protection.landmarkPosition(landmark.name());
                return spot.isEmpty() ? Optional.empty()
                        : blockAllowed(permissions, action, spot.get(), null);
            }
            case Target.Player player -> {
                requireCreatureAction(action);
                return creatureAllowed(permissions, action,
                        new ReadsCreatureSituation.CreatureSituation(true, false, false, false, false, false),
                        "玩家 " + player.name());
            }
            default -> throw new IllegalArgumentException(
                    "这个动作的目标要先落实成观察编号、位置或地标再来检查许可");
        }
    }

    /**
     * 角色自己挑中的一格方块能不能动：先过保护（归属记录、记住的区域、额外地标、玩家放置推断），
     * 再过许可档位。观察编号不用走这里，{@link #allows} 已把点名当许可。
     */
    public Optional<Problem> blockAllowed(Permissions permissions, WorldAction action,
            WorldPosition position, String blockType) {
        requireBlockAction(action);
        if (protection.blockProtected(position, blockType, permissions.protectedLandmarks())) {
            return Optional.of(protectedBlockRejection(action, position, permissions));
        }
        if (action == WorldAction.PLACE_BLOCK && permissions.changeBlocks() == Permissions.BlockChanges.NONE) {
            return Optional.of(Problem.of(Problem.Kind.NEED_APPROVAL,
                    "要在 " + describe(position) + " 放方块，但这次任务的 change_blocks=none，一格都不能动",
                    "把 change_blocks 提到 temporary 或更高"));
        }
        if (digBlockedByTier(permissions, action, position)) {
            return Optional.of(tierRejection(permissions, describe(position)));
        }
        return Optional.empty();
    }

    /** 挖方块按档位放行：none 都不行；temporary 只放行收回自己搭的；natural 与 any 都行。 */
    private boolean digBlockedByTier(Permissions permissions, WorldAction action, WorldPosition position) {
        if (action != WorldAction.DIG_BLOCK) return false;
        return switch (permissions.changeBlocks()) {
            case NONE -> true;
            case TEMPORARY -> !protection.selfPlaced(position);
            case NATURAL, ANY -> false;
        };
    }

    /**
     * 角色自己挑中的一只生物能不能动。处境读不到时按受保护处理——宁可放过一只野怪，
     * 不打别人的宠物。
     */
    public Optional<Problem> creatureAllowed(Permissions permissions, WorldAction action,
            UUID entityId, String describe) {
        requireCreatureAction(action);
        var situation = creatures.situationOf(entityId);
        if (situation.isEmpty()) {
            return Optional.of(Problem.of(Problem.Kind.NEED_APPROVAL,
                    describe + "现在看不真切（有名字、被驯服、围栏里的都可能），按别人的生物处理不碰它",
                    "想动它，就用观察编号明确点名这只生物"));
        }
        return creatureAllowed(permissions, action, situation.get(), describe);
    }

    /**
     * LLM 用观察编号点名要打（或为需要击杀）的一只生物：点名本身就是许可，但有主的（有名字、驯服、拴绳、圈养）
     * 和玩家点了名也要再确认一次——这次任务把 fight（击杀动物时是 kill_animals）开到 any 才动手；
     * 击杀玩家永远不行。处境读不到时按有主算。
     */
    public Optional<Problem> namedCreatureAllowed(Permissions permissions, WorldAction action,
            UUID entityId, String describe) {
        requireCreatureAction(action);
        var situation = creatures.situationOf(entityId);
        boolean player = situation.map(ReadsCreatureSituation.CreatureSituation::player).orElse(false);
        if (player) {
            if (action == WorldAction.KILL_ANIMAL) {
                return Optional.of(Problem.of(Problem.Kind.NEED_APPROVAL,
                        "没有任何一项许可允许伤害玩家：" + describe, null));
            }
            return fightPlayerAllowed(permissions, describe);
        }
        boolean bonded = situation.map(ReadsCreatureSituation.CreatureSituation::bondedToSomeone).orElse(true);
        if (!bonded) return Optional.empty();
        boolean confirmed = action == WorldAction.FIGHT
                ? permissions.fight() == Permissions.Fight.ANY
                : permissions.killAnimals() == Permissions.AnimalKilling.ANY;
        if (confirmed) return Optional.empty();
        String tier = action == WorldAction.FIGHT ? "fight" : "kill_animals";
        return Optional.of(Problem.of(Problem.Kind.NEED_APPROVAL,
                describe + (situation.isEmpty() ? "看不真切，可能是别人的（有名字、驯服、拴着或圈着）"
                        : "是有主的（有名字、驯服、拴着绳或圈养着）")
                        + "，点了名也要再确认一次才动手",
                "确实要动它，就把这次任务的 " + tier + " 设为 any 再下一次"));
    }

    /** 已知处境的许可判断：受保护的生物一律拒；对玩家的动作只有 fight=any 放行，击杀玩家永远不行。 */
    public Optional<Problem> creatureAllowed(Permissions permissions, WorldAction action,
            ReadsCreatureSituation.CreatureSituation situation, String describe) {
        requireCreatureAction(action);
        if (action == WorldAction.KILL_ANIMAL && situation.player()) {
            return Optional.of(Problem.of(Problem.Kind.NEED_APPROVAL,
                    "没有任何一项许可允许伤害玩家：" + describe, null));
        }
        if (protection.creatureProtected(situation)) {
            return Optional.of(Problem.of(Problem.Kind.NEED_APPROVAL,
                    describe + "是有主的（有名字、被驯服、拴着绳或围栏里），任何许可档位都不碰它",
                    "想动它，就用观察编号明确点名这只生物"));
        }
        if (situation.player()) {
            return fightPlayerAllowed(permissions, describe);
        }
        return switch (action) {
            case FIGHT -> fightCreatureAllowed(permissions, situation, describe);
            case KILL_ANIMAL -> killAnimalAllowed(permissions, situation, describe);
            default -> throw new IllegalArgumentException("生物动作只有打与击杀：" + action);
        };
    }

    /** 稀有消耗品只有一档开关：没开就拒，说清要用它就得开 use_rare_items。 */
    public Optional<Problem> rareItemAllowed(Permissions permissions) {
        if (permissions.useRareItems()) return Optional.empty();
        return Optional.of(Problem.of(Problem.Kind.NEED_APPROVAL,
                "这次任务不许用稀有消耗品（use_rare_items=false），例如末影珍珠、不死图腾、钻石",
                "要用的话，把这次任务的 use_rare_items 设为 true"));
    }

    /** 被拒的许可问题升级成向 LLM 提问：只有 NEED_APPROVAL 升级，其余原样留给重试策略。 */
    public Optional<Question> approvalQuestion(Problem refusal) {
        return QuestionEscalation.escalate(refusal);
    }

    private Optional<Problem> fightPlayerAllowed(Permissions permissions, String describe) {
        if (permissions.fight() == Permissions.Fight.ANY) return Optional.empty();
        return Optional.of(Problem.of(Problem.Kind.NEED_APPROVAL,
                "对玩家动手需要把 fight 开到 any，现在只是 " + permissions.fight().name(),
                "确认要这么做的话，把这次任务的 fight 设为 any"));
    }

    private Optional<Problem> fightCreatureAllowed(Permissions permissions,
            ReadsCreatureSituation.CreatureSituation situation, String describe) {
        return switch (permissions.fight()) {
            // 只自卫：不是它先动的手就不打。
            case SELF_DEFENSE -> Optional.of(Problem.of(Problem.Kind.NEED_APPROVAL,
                    "现在只自卫（fight=self_defense），不会主动打 " + describe,
                    "把 fight 提到 hostile_mobs 或 any，或明确点名这个目标"));
            case HOSTILE_MOBS -> situation.hostileMob() ? Optional.empty()
                    : Optional.of(Problem.of(Problem.Kind.NEED_APPROVAL,
                            describe + "不是敌对生物，主动打它超出现在的 fight=hostile_mobs",
                            "把 fight 提到 any，或明确点名这个目标"));
            case ANY -> Optional.empty();
        };
    }

    private Optional<Problem> killAnimalAllowed(Permissions permissions,
            ReadsCreatureSituation.CreatureSituation situation, String describe) {
        return switch (permissions.killAnimals()) {
            case NONE -> Optional.of(Problem.of(Problem.Kind.NEED_APPROVAL,
                    "这次任务不许击杀动物（kill_animals=none），却要处理 " + describe,
                    "把 kill_animals 提到 wild 或 any"));
            // 野外的普通动物可以为具体需要击杀；敌对生物的清理归 fight 管，不占击杀动物的许可。
            case WILD -> situation.hostileMob()
                    ? Optional.of(Problem.of(Problem.Kind.NEED_APPROVAL,
                            describe + "是敌对生物，处理它归 fight 管，kill_animals 不覆盖",
                            "把 fight 提到 hostile_mobs 或 any"))
                    : Optional.empty();
            case ANY -> Optional.empty();
        };
    }

    /** 许可档位不够的拒绝：说清现在的 change_blocks 是哪一档、要提到哪一档。 */
    private Problem tierRejection(Permissions permissions, String where) {
        return Problem.of(Problem.Kind.NEED_APPROVAL,
                "要挖" + where + "，但这次任务的 change_blocks 只是 " + permissions.changeBlocks().name()
                        + "，动不了它",
                "把 change_blocks 提到 natural 或 any；只是要搭临时方块再收回的话，提到 temporary 也能收");
    }

    /** 受保护方块的拒绝：说清是哪路保护挡的，以及为什么许可档位开不到它。 */
    private Problem protectedBlockRejection(WorldAction action, WorldPosition position,
            Permissions permissions) {
        String where = describe(position);
        var landmark = protection.protectedLandmarkHit(position, permissions.protectedLandmarks());
        if (landmark.isPresent()) {
            return Problem.of(Problem.Kind.NEED_APPROVAL,
                    where + "在额外保护的地标『" + landmark.get() + "』旁边，不能动",
                    "把『" + landmark.get() + "』从 protected_landmarks 里去掉，或明确点名这个目标");
        }
        return Problem.of(Problem.Kind.NEED_APPROVAL,
                where + "是玩家放的或在玩家的地盘里（服务端记录、记住的区域或推断），任何许可档位都不动它",
                "想动它，就用观察编号明确点名这个目标");
    }

    private static String describe(WorldPosition position) {
        return "(" + position.x() + ", " + position.y() + ", " + position.z() + ")";
    }

    private static void requireBlockAction(WorldAction action) {
        if (action != WorldAction.DIG_BLOCK && action != WorldAction.PLACE_BLOCK) {
            throw new IllegalArgumentException("方块位置上只检查挖与放：" + action);
        }
    }

    private static void requireCreatureAction(WorldAction action) {
        if (action != WorldAction.FIGHT && action != WorldAction.KILL_ANIMAL) {
            throw new IllegalArgumentException("生物身上只检查打与击杀：" + action);
        }
    }
}
