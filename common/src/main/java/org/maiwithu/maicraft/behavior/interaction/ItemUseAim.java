// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 用东西的手势：手上拿的东西决定点哪一格、效果落在哪一格、怎样算生效。
 *
 * <p>纯函数：所有世界事实都由调用方读好传进来（目标格的样子、目标周围的邻居、脚下的位置），
 * 这里只做计算，不碰游戏对象。右键会先交给准星下的方块，方块自己有右键行为（箱子、门、工作台）
 * 时手上的东西轮不上，所以倒流体、点火挑支撑面时避开这类方块，实在没有别的面可选才用它，
 * 由调用方潜行后再点。
 */
public final class ItemUseAim {

    /** 手上拿的东西归成的手势。 */
    public enum Gesture {
        /** 空手：对目标本身用。 */
        EMPTY_HAND,
        /** 空桶：从流体源格舀。 */
        SCOOP,
        /** 满桶：把流体倒出去。 */
        POUR,
        /** 打火石、火焰弹：点火。 */
        IGNITE,
        /** 锄、锹、斧：耕地、压土径、去皮。 */
        TRANSFORM_TOOL,
        /** 刷子：对可疑的沙子、沙砾按住刷完。 */
        BRUSH,
        /** 其他对东西用的物品：骨粉、染料、剪刀、拴绳、鞍、给牛挤奶的桶……对目标本身用。 */
        USE_TARGET,
        /** 没有自然结束、要凭时机松手的物品：本能力不接，计划阶段就拒绝。 */
        UNSUPPORTED
    }

    /** 点中的目标格是什么样子，由调用方读世界得到。 */
    public enum CellNature {
        /** 空气，或草、水这类可以被直接替换的格子。 */
        AIR_OR_REPLACEABLE,
        /** 实心方块。 */
        SOLID,
        /** 流体源格。 */
        FLUID_SOURCE,
        /** 流动的流体。 */
        FLUID_FLOWING
    }

    /** 怎样算这次使用生效；确认由交互动作按它核对。 */
    public enum Confirmation {
        /** 服务端确认生效，或者目标变了、打开了界面、骑了上去：空手与大多数物品用这条。 */
        TARGET_OR_HAND_CHANGES,
        /** 手上变成满桶：舀到了源格。 */
        HAND_BECOMES_FULL_BUCKET,
        /** 手上变回空桶，落格出现流体或流体反应的产物（黑曜石、圆石、石头）：倒出去了。 */
        HAND_EMPTIES_OR_FLUID_APPEARS,
        /** 二十刻内落格出现过火，或火变成了下界传送门：火可能自己熄灭，出现过就算点着。 */
        FIRE_APPEARS,
        /** 目标方块变成预期的新样子：耕地、土径、去皮的木头、点着的样子。 */
        BLOCK_TURNS_INTO
    }

    /**
     * 一份瞄准决定：点哪一格、效果落在哪一格、怎样算生效。
     * 效果格是被点面外面那一格（倒流体、点火时流体和火落在那里）；
     * 与目标同格时说明效果就发生在目标本身。
     *
     * @param clickCell    右键点中的格子
     * @param effectCell   效果实际落下的格子
     * @param confirmation 怎样算生效
     * @param describe     给日志与结果用的一句话，说明为什么点这里
     */
    public record Aim(BlockPos clickCell, BlockPos effectCell, Confirmation confirmation, String describe) {
        public Aim {
            Objects.requireNonNull(clickCell, "clickCell");
            Objects.requireNonNull(effectCell, "effectCell");
            Objects.requireNonNull(confirmation, "confirmation");
            Objects.requireNonNull(describe, "describe");
        }
    }

    /**
     * 瞄准时要问的周围事实，调用方在动手前读好。
     *
     * @param feet                     角色脚下的格子；用来猜目标朝向角色的面，效果格正是自己站的地方时写进说明
     * @param solidNeighbors           目标的六个邻居里哪些是实心方块（可当支撑面）
     * @param rightClickableNeighbors  邻居里哪些自己有右键行为（箱子、门、工作台），点它会先触发方块的行为
     * @param aboveOpen                目标正上方是不是空气；锄和锹要求上方敞开
     * @param ignitesItself            目标本身是不是能点着的方块（TNT、营火、蜡烛），点它本身就生效
     */
    public record Around(BlockPos feet, Set<Direction> solidNeighbors,
                         Set<Direction> rightClickableNeighbors, boolean aboveOpen, boolean ignitesItself) {
        public Around {
            Objects.requireNonNull(feet, "feet");
            solidNeighbors = Set.copyOf(solidNeighbors);
            rightClickableNeighbors = Set.copyOf(rightClickableNeighbors);
        }
    }

    /** 没有自然结束、要凭时机松手的物品；本能力不接受，计划阶段直接拒绝并指向对应能力。 */
    private static final Set<String> TIMED_RELEASE_ITEMS = Set.of(
            "minecraft:bow", "minecraft:crossbow", "minecraft:trident",
            "minecraft:shield", "minecraft:spyglass", "minecraft:fishing_rod");

    private ItemUseAim() {}

    /** 按手上的物品 ID 归出手势；空手传 null 或空白。 */
    public static Gesture gestureOf(String heldItemId) {
        if (heldItemId == null || heldItemId.isBlank()) return Gesture.EMPTY_HAND;
        String id = heldItemId.toLowerCase(Locale.ROOT);
        if (TIMED_RELEASE_ITEMS.contains(id)) return Gesture.UNSUPPORTED;
        if (id.endsWith(":bucket")) return Gesture.SCOOP;
        if (id.endsWith("_bucket")) return Gesture.POUR;
        if (id.endsWith(":flint_and_steel") || id.endsWith(":fire_charge")) return Gesture.IGNITE;
        if (id.endsWith("_hoe") || id.endsWith("_shovel") || id.endsWith("_axe")) return Gesture.TRANSFORM_TOOL;
        if (id.endsWith(":brush")) return Gesture.BRUSH;
        return Gesture.USE_TARGET;
    }

    /**
     * 空桶点到流动的流体时不算失败：改舀附近同种的源格。
     * 调用方按这个回答先查附近有没有源格，查到了用 {@link #scoop} 对它瞄准。
     */
    public static boolean needsSourceSearch(Gesture gesture, CellNature nature) {
        return gesture == Gesture.SCOOP && nature == CellNature.FLUID_FLOWING;
    }

    /** 对一个确定的源格舀：射线只认源格，点到流动的格子不算数。 */
    public static Aim scoop(BlockPos source) {
        return new Aim(source, source, Confirmation.HAND_BECOMES_FULL_BUCKET, "舀流体源格 " + source.toShortString());
    }

    /** 按手势和目标格的样子给出瞄准决定；给不出时为空，调用方换站位或换目标。
     *
     * <p>给不出有了几类原因：空桶对着的既不是源格也不需要找源格；锄和锹要的上方敞开不满足；
     * 倒流体、点火对着空气或流体、旁边又没有实心方块当支撑面。效果格正是自己站的地方不算给不出：
     * 往脚下倒水、往脚下倒岩浆都是玩家本来就会做的事，照给瞄准、说明里写清，由 LLM 自己担后果
     * （所有者 10-16："岩浆会流到脚下就不能放"这类预测门不能有，只会多绕一轮、让小模型以为 MCP 坏了）。
     */
    public static Optional<Aim> aim(Gesture gesture, BlockPos target, CellNature nature, Around around) {
        return aim(gesture, target, nature, around, null);
    }

    /**
     * 同上，点名了点目标的哪一面：倒流体、点火落在这一面外面那一格（"对草地顶面倒水"就倒在草地上面），
     * 不再按朝向角色的那一面猜；没点名为 null。
     */
    public static Optional<Aim> aim(Gesture gesture, BlockPos target, CellNature nature, Around around, Direction face) {
        return switch (gesture) {
            // 刷子点目标本身，按住直到刷完；做成以那一格变回普通方块为准。
            case EMPTY_HAND, USE_TARGET ->
                Optional.of(new Aim(target, target, Confirmation.TARGET_OR_HAND_CHANGES, "对目标本身用"));
            case BRUSH ->
                Optional.of(new Aim(target, target, Confirmation.BLOCK_TURNS_INTO, "点目标本身，按住直到刷完"));
            case SCOOP -> aimScoop(nature, target);
            case POUR -> aimEffect(gesture, target, nature, around, Confirmation.HAND_EMPTIES_OR_FLUID_APPEARS, face);
            case IGNITE -> around.ignitesItself()
                    ? Optional.of(new Aim(target, target, Confirmation.FIRE_APPEARS, "点着目标本身"))
                    : aimEffect(gesture, target, nature, around, Confirmation.FIRE_APPEARS, face);
            case TRANSFORM_TOOL -> aimTransform(target, nature, around);
            case UNSUPPORTED -> Optional.empty();
        };
    }

    // 空桶：只认源格；点到流动的格子由调用方先找源格，这里不猜。
    private static Optional<Aim> aimScoop(CellNature nature, BlockPos target) {
        if (nature == CellNature.FLUID_SOURCE) return Optional.of(scoop(target));
        return Optional.empty();
    }

    // 锄、锹、斧：只点侧面或顶面（不点底面），目标上方必须是空气；效果是目标自己变样子。
    private static Optional<Aim> aimTransform(BlockPos target, CellNature nature, Around around) {
        if (nature != CellNature.SOLID || !around.aboveOpen()) return Optional.empty();
        return Optional.of(new Aim(target, target, Confirmation.BLOCK_TURNS_INTO,
                "点目标的侧面或顶面，让它变成耕地、土径或去皮的样子"));
    }

    /**
     * 倒流体与点火：效果落在被点面外面那一格。
     * 目标是空气或可替换的格子时，点它旁边方块的支撑面；目标是实心方块时，
     * 点它朝向角色的面，效果落在那个面外面的格子。效果格正是自己站的地方时照样给，说明里写清。
     */
    private static Optional<Aim> aimEffect(Gesture gesture, BlockPos target, CellNature nature,
            Around around, Confirmation confirmation, Direction face) {
        String doing = gesture == Gesture.POUR ? "倒" : "点火";
        if (nature == CellNature.AIR_OR_REPLACEABLE) {
            return supportFace(target, around)
                    .map(support -> new Aim(support, target, confirmation,
                            "点 " + support.toShortString() + " 朝向目标的支撑面，把" + doing + "到 " + target.toShortString()));
        }
        if (nature != CellNature.SOLID) return Optional.empty();
        // 点名了哪一面就落在那一面外面：实机 10-13 站在草地边上"对草地顶面倒水"，按朝向角色的面猜成了侧面，
        // 侧面外面是脚下那块地、看不见，回回"看不到任何一面"。
        Direction towardPlayer = face != null ? face : faceToward(around.feet(), target);
        BlockPos effect = target.relative(towardPlayer);
        // 目标在脚下、点它的顶面，效果就落在自己站的格子里：不拒绝也不替 LLM 挪站位，照倒、照点，说明里写清落在自己身上。
        // 往脚下倒水救火、寻死往脚下倒岩浆都是这条路；真不想淋到自己，LLM 点名别的面或先 travel 一步就行。
        String onSelf = overlapsPlayer(effect, around.feet()) ? "，那一格正是自己站的地方" : "";
        return Optional.of(new Aim(target, effect, confirmation,
                "点目标朝向角色的面，" + doing + "在面外的 " + effect.toShortString() + onSelf));
    }

    /**
     * 挑一个支撑面：优先没有右键行为的实心邻居；实在只有箱子、门这类，也用它，
     * 调用方看到说明后潜行再点，免得手里的东西被方块的行为截走。周围没有实心邻居时给不出。
     */
    private static Optional<BlockPos> supportFace(BlockPos target, Around around) {
        List<Direction> plain = new ArrayList<>();
        List<Direction> clickable = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            if (!around.solidNeighbors().contains(direction)) continue;
            if (around.rightClickableNeighbors().contains(direction)) clickable.add(direction);
            else plain.add(direction);
        }
        List<Direction> choices = plain.isEmpty() ? clickable : plain;
        if (choices.isEmpty()) return Optional.empty();
        // 挑离脚下最近的方向，让角色不必探出身子去点。
        Direction best = choices.getFirst();
        for (Direction direction : choices) {
            if (distanceSq(target.relative(direction), around.feet())
                    < distanceSq(target.relative(best), around.feet())) {
                best = direction;
            }
        }
        return Optional.of(target.relative(best));
    }

    // 目标朝向角色那一面：脚下位置到目标的三个轴里，差得最多的那个轴决定面。
    private static Direction faceToward(BlockPos feet, BlockPos target) {
        int dx = feet.getX() - target.getX();
        int dy = feet.getY() - target.getY();
        int dz = feet.getZ() - target.getZ();
        int ax = Math.abs(dx);
        int ay = Math.abs(dy);
        int az = Math.abs(dz);
        if (ax >= ay && ax >= az) return dx >= 0 ? Direction.EAST : Direction.WEST;
        if (az >= ay) return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        return dy > 0 ? Direction.UP : Direction.DOWN;
    }

    /** 这一格压着身体（脚下或头顶）：倒流体、点火落在这里会淋到、烧到自己，结果里要写明。 */
    public static boolean overlapsPlayer(BlockPos cell, BlockPos feet) {
        return cell.equals(feet) || cell.equals(feet.above());
    }

    private static long distanceSq(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dy = a.getY() - b.getY();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
