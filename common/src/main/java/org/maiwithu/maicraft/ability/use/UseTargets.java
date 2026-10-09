// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 找目标：把目标对象和 block、entity 参数落实成具体的一格方块或一只实体。
 *
 * <p>观察编号 b#、e# 和坐标就是那一个，block、entity 参数只用来核对；记得的地点、地形特征 f#、
 * 脚边是一片地方，给了 block 或 entity 时在那片地方 radius 内找最近的，没给就用那一格本身。
 * 只给 item、不给目标时不对着任何东西用。别的维度去不了；没加载的先走过去，到了再找。
 * 扫描没扫完不冒充"没有"，下一刻接着问。
 *
 * <p>会改动世界的用法（倒流体、点火、耕地、剪毛、挤奶这类）只对角色自己挑的目标看归属：
 * 按类型找时跳过别人的，坐标和地点落到别人的东西上时要 LLM 用观察编号点名；观察编号点名的就是许可。
 */
final class UseTargets {

    /** 找目标的结论。 */
    sealed interface Lookup {
        /** 落实到了具体的一格或一只。 */
        record Found(ResolvedTarget target) implements Lookup {}

        /** 不对着任何方块和实体用，只用手上的东西（喝药水、吹山羊角）。 */
        record HandOnly() implements Lookup {}

        /** 附近搜索还没扫完，下一刻再问。 */
        record Scanning(String what) implements Lookup {}

        /** 目标那一片还没加载：先走过去；heightKnown 为假时只走到那一柱列。 */
        record TravelFirst(WorldPosition where, boolean heightKnown) implements Lookup {}

        /** 落实不了，带该上报的问题。 */
        record Failed(Problem problem) implements Lookup {}
    }

    private final UseInput input;
    private final UseServices services;
    /** 这一次按类型找时有没有因为是别人的而跳过：找不到时据此说清是"只有别人的"。 */
    private boolean skippedOthers;

    UseTargets(UseInput input, UseServices services) {
        this.input = input;
        this.services = services;
    }

    /** 按目标对象的种类落实这一刻的目标。 */
    Lookup lookup() {
        // 每次重新找都重新数跳过了几个别人的：上一刻的扫描结论不带到这一刻。
        skippedOthers = false;
        return switch (input.target()) {
            case null -> nearOrHandOnly(services.world().feet());
            case Target.Here here -> nearOrHandOnly(services.world().feet());
            case Target.Seen seen -> seen(seen.id());
            case Target.Position position -> position(position);
            case Target.Landmark landmark -> landmark(landmark.name());
            default -> new Lookup.Failed(Problem.of(Problem.Kind.UNSUPPORTED,
                    "use 不接受这种目标：" + input.target().kind(), "用观察编号、坐标或记得的地点指定对什么用"));
        };
    }

    // 脚边：给了类型就在身边找最近的，什么类型都没给就只用手上的东西。
    private Lookup nearOrHandOnly(BlockPos feet) {
        if (input.block() == null && input.entity() == null) {
            return new Lookup.HandOnly();
        }
        if (feet == null) {
            return new Lookup.Failed(Problem.of(Problem.Kind.WRONG_TIME, "这一刻角色不在世界里，找不了目标", null));
        }
        return search(feet);
    }

    // 观察编号：实体按游戏实体编号找回当刻的那一只；地形特征是一片地方；设施就是那一格。
    private Lookup seen(String seenId) {
        if (services.seen() == null) {
            return new Lookup.Failed(Problem.of(Problem.Kind.TARGET_GONE,
                    "观察编号 " + seenId + " 对应的东西读不到（感知侧还没接上）", "重新 observe 拿新的观察编号"));
        }
        Optional<ResolvesSeen.Resolved> resolved = services.seen().resolve(seenId);
        if (resolved.isEmpty()) {
            return new Lookup.Failed(Problem.of(Problem.Kind.TARGET_GONE,
                    "观察编号 " + seenId + " 对应的东西已经不在了（走远、被拆或消失）", "重新 observe 拿新的观察编号"));
        }
        ResolvesSeen.Resolved found = resolved.get();
        if (found.gameEntityId() != null) {
            return entity(found.gameEntityId(), "观察编号 " + seenId);
        }
        if (seenId.startsWith("f") && (input.block() != null || input.entity() != null)) {
            return search(found.blockPos());
        }
        return block(found.blockPos(), "观察编号 " + seenId, true);
    }

    // 坐标：别的维度去不了；没给 y 用那一列最上面的方块；没加载先走过去。
    private Lookup position(Target.Position position) {
        String here = services.world().dimension();
        if (position.dimension() != null && here != null && !position.dimension().equals(here)) {
            return new Lookup.Failed(Problem.of(Problem.Kind.UNREACHABLE,
                    "目标坐标在别的维度（" + position.dimension() + "），跨维度出行还没接上", null));
        }
        BlockPos cell;
        if (position.y() == null) {
            BlockPos column = new BlockPos(position.x(), 0, position.z());
            if (!services.world().loaded(column)) {
                return new Lookup.TravelFirst(new WorldPosition(position.x(), 0, position.z(), here), false);
            }
            Optional<BlockPos> ground = services.world().ground(position.x(), position.z());
            if (ground.isEmpty()) {
                return new Lookup.Failed(Problem.of(Problem.Kind.NOT_FOUND,
                        "（" + position.x() + ", " + position.z() + "）那一列没有方块可用", "给出 y 坐标"));
            }
            cell = ground.get();
        } else {
            cell = new BlockPos(position.x(), position.y(), position.z());
        }
        if (input.entity() != null) {
            return services.world().loaded(cell) ? search(cell) : travelTo(cell);
        }
        return block(cell, "坐标", false);
    }

    // 记得的地点：没记过如实说没有，绝不退回到脚边去找；给了类型在那一片找，没给就是那一格。
    private Lookup landmark(String name) {
        Optional<WorldPosition> place = services.memory() == null ? Optional.empty() : services.memory().place(name);
        if (place.isEmpty()) {
            return new Lookup.Failed(Problem.of(Problem.Kind.NOT_FOUND, "没有记住叫「" + name + "」的地点", null));
        }
        WorldPosition at = place.get();
        String here = services.world().dimension();
        if (at.dimension() != null && here != null && !at.dimension().equals(here)) {
            return new Lookup.Failed(Problem.of(Problem.Kind.UNREACHABLE,
                    "地点「" + name + "」在别的维度（" + at.dimension() + "），跨维度出行还没接上", null));
        }
        BlockPos cell = new BlockPos(at.x(), at.y(), at.z());
        if (!services.world().loaded(cell)) {
            return travelTo(cell);
        }
        if (input.block() != null || input.entity() != null) {
            return search(cell);
        }
        return block(cell, "地点「" + name + "」", false);
    }

    // 在一片地方按类型找最近的；没扫完不说话，扫完了还没有就是没找到，写明查了多大范围。
    private Lookup search(BlockPos center) {
        if (services.search() == null) {
            return new Lookup.Failed(Problem.of(Problem.Kind.UNSUPPORTED,
                    "附近搜索没接上，按类型找不了目标", "用观察编号或坐标指定对什么用"));
        }
        int radius = (int) input.radius();
        if (input.entity() != null) {
            SearchesNearby.EntityResult result = services.search().nearestEntity(input.entity(), center, radius,
                    this::othersCreature);
            if (result.found().isPresent()) {
                return entity(result.found().getAsInt(), input.entity());
            }
            return result.scannedComplete() ? notFound(input.entity()) : new Lookup.Scanning("正在扫附近的" + input.entity());
        }
        SearchesNearby.BlockResult result = services.search().nearestBlock(input.block(), center, radius,
                this::othersBlock);
        if (result.found().isPresent()) {
            return block(result.found().get(), input.block(), false);
        }
        return result.scannedComplete() ? notFound(input.block()) : new Lookup.Scanning("正在扫附近的" + input.block());
    }

    // 一只实体：按编号找回当刻的它；准星选不中的（掉落物、经验球、箭）用不了；类型对不上就是换了东西。
    private Lookup entity(int entityId, String label) {
        Optional<UseSeams.ReadsWorld.SeenEntity> seen = services.world().entity(entityId);
        if (seen.isEmpty()) {
            return new Lookup.Failed(Problem.of(Problem.Kind.TARGET_GONE,
                    label + " 对应的实体已经不在了", "重新 observe 拿新的观察编号"));
        }
        UseSeams.ReadsWorld.SeenEntity now = seen.get();
        if (!now.pickable()) {
            return new Lookup.Failed(Problem.of(Problem.Kind.UNSUPPORTED,
                    now.typeId() + " 准星选不中（掉落物、经验球、射出去的箭这类），use 用不了", "捡东西用 gather"));
        }
        if (input.block() != null) {
            return new Lookup.Failed(Problem.of(Problem.Kind.TARGET_GONE,
                    "目标应为方块 " + input.block() + "，" + label + " 对应的是实体 " + now.typeId(), null));
        }
        if (input.entity() != null && !now.typeId().equals(input.entity())) {
            return new Lookup.Failed(Problem.of(Problem.Kind.TARGET_GONE,
                    "目标应为 " + input.entity() + "，现在是 " + now.typeId(), null));
        }
        return new Lookup.Found(ResolvedTarget.entity(entityId, now.cell(), now.typeId()));
    }

    // 按类型找到的一格是别人的、这次的用法又会改动它：跳过，记下跳过过。
    private boolean othersBlock(BlockPos cell) {
        String now = services.world().blockId(cell).orElse("minecraft:air");
        boolean skip = guarded(ResolvedTarget.block(cell, now, now));
        skippedOthers |= skip;
        return skip;
    }

    // 按类型找到的一只是别人的（有名字、驯服、拴绳、圈养）、这次的用法又会改动它：跳过，记下跳过过。
    private boolean othersCreature(int entityId) {
        boolean skip = services.world().entity(entityId)
                .map(seen -> guarded(ResolvedTarget.entity(entityId, seen.cell(), seen.typeId())))
                .orElse(false);
        skippedOthers |= skip;
        return skip;
    }

    // 角色自己挑的目标能不能用：只有会改动世界的用法才看归属；认不出别人的东西时一律当别人的，宁可不做。
    private boolean guarded(ResolvedTarget target) {
        UseSeams.OthersThings others = services.others();
        if (others == null) return true;
        return others.changesWorld(input.item(), target)
                && others.someoneElses(target, input.permissions().protectedLandmarks());
    }

    // 一格方块：没加载先走过去；给了 block 就核对那一格现在是不是它；不是点名的，别人的东西用法会改动它时不碰。
    private Lookup block(BlockPos cell, String label, boolean named) {
        if (!services.world().loaded(cell)) {
            return travelTo(cell);
        }
        String now = services.world().blockId(cell).orElse("minecraft:air");
        if (input.block() != null && !services.world().blockIs(cell, input.block())) {
            return new Lookup.Failed(Problem.of(Problem.Kind.TARGET_GONE,
                    "目标应为 " + input.block() + "，" + cell.toShortString() + " 现在是 " + now, null));
        }
        ResolvedTarget target = ResolvedTarget.block(cell, now, label + " " + now + " " + cell.toShortString());
        if (!named && guarded(target)) {
            return new Lookup.Failed(Problem.of(Problem.Kind.NEED_APPROVAL,
                    target.describe() + " 是别人的（玩家放的或在玩家的地盘里），这样用会改动它，不碰",
                    "确实要用，就先 observe 再用观察编号点名它"));
        }
        return new Lookup.Found(target);
    }

    private Lookup travelTo(BlockPos cell) {
        return new Lookup.TravelFirst(
                new WorldPosition(cell.getX(), cell.getY(), cell.getZ(), services.world().dimension()), true);
    }

    private Lookup notFound(String what) {
        if (skippedOthers) {
            // 附近有，但都是别人的：要 LLM 点名才动，不是没有。
            return new Lookup.Failed(Problem.of(Problem.Kind.NEED_APPROVAL,
                    input.radius() + " 格内的 " + what + " 都是别人的，这样用会改动它们，不碰",
                    "确实要用，就先 observe 再用观察编号点名其中一个"));
        }
        return new Lookup.Failed(Problem.of(Problem.Kind.NOT_FOUND,
                input.radius() + " 格内没有找到 " + what + "（只查了已加载的区域）", null));
    }
}
