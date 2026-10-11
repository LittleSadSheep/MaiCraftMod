// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.retry.QuestionEscalation;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

import java.util.List;
import java.util.Optional;

/**
 * 目的地解析：把目标对象读成一段导航要求，只读现场、给结论，不动角色。
 *
 * <p>解析规矩：坐标给不全 y 也能去——先走到那一柱列；叫得出名字的地点查记过的地方，
 * 记不得就向玩家提问，不拿脚边顶替，也不把名字猜成别的东西；观察编号直接查它指向的位置，
 * 编号失效如实以"东西不在了"收场。"就是去那个维度"与落在别的维度的目的地都按维度路由
 * 认门：主世界与下界走下界门，主世界与末地走末地门，其余维度对如实说走不了。
 */
public final class DestinationResolver {

    /** 解析的一种结局。 */
    public sealed interface Resolution {

        /** 解析出了目的地，可以上路。 */
        record Ready(TravelDestination destination) implements Resolution {}

        /**
         * 要跨维度的行程：去 {@code targetDimension}；{@code destination} 不为 null 时
         * 是"那个维度的这个坐标"，跨完接着步行，为 null 表示"就是去那个维度"。
         */
        record CrossDimension(String targetDimension, TravelDestination destination) implements Resolution {}

        /** 目标就是脚下（或就是当前维度）：已经站在目的地。 */
        record AlreadyThere(WorldPosition spot) implements Resolution {}

        /** 目标说不清，要向玩家提问；由内核的提问钩子取走。 */
        record Unclear(Question question) implements Resolution {}

        /** 解析不下去，带着现场事实结束。 */
        record DeadEnd(Problem problem) implements Resolution {}
    }

    private final TravelWorldView world;
    private final ReadsRememberedPlaces rememberedPlaces;
    private final ReadsSeenTargets seenTargets;

    public DestinationResolver(TravelWorldView world, ReadsRememberedPlaces rememberedPlaces,
            ReadsSeenTargets seenTargets) {
        this.world = world;
        this.rememberedPlaces = rememberedPlaces;
        this.seenTargets = seenTargets;
    }

    /** 解析一个目标对象；radius 是到达容差（单位格，默认 2，0 表示要站进那一格）。 */
    public Resolution resolve(Target target, double radius) {
        if (target instanceof Target.Here) {
            return new Resolution.AlreadyThere(world.currentSpot());
        }
        if (target instanceof Target.Dimension dimension) {
            return resolveDimension(dimension.name());
        }
        if (target instanceof Target.Position position) {
            return resolvePosition(position, radius);
        }
        if (target instanceof Target.Landmark landmark) {
            return resolveLandmark(landmark, radius);
        }
        if (target instanceof Target.Seen seen) {
            return resolveSeen(seen, radius);
        }
        if (target instanceof Target.Direction direction) {
            return resolveDirection(direction, radius);
        }
        // 玩家与前一步的位置不是出行目标：跟着人走归陪伴，前一步的位置只有目标推进记得。
        return new Resolution.DeadEnd(Problem.of(Problem.Kind.UNSUPPORTED,
                "出行还不支持这种目的地：" + target.kind()));
    }

    /** "就是去那个维度"：已在就是已满足；按维度路由认门，其余维度对如实说走不了。 */
    private Resolution resolveDimension(String dimension) {
        String here = world.currentSpot().dimension();
        if (dimension.equals(here)) {
            return new Resolution.AlreadyThere(world.currentSpot());
        }
        return routeAcross(dimension, null);
    }

    /** 目的地落在别的维度："那个维度的这个坐标"，跨完接着步行；认不出门就带着原因结束。 */
    private Resolution routeAcross(String dimension, TravelDestination destination) {
        DimensionRoute.Decision route = DimensionRoute.decide(world.currentSpot().dimension(), dimension);
        return switch (route.way()) {
            case NO_DIRECT_PORTAL, UNKNOWN_DIMENSION -> new Resolution.DeadEnd(route.problem());
            default -> new Resolution.CrossDimension(dimension, destination);
        };
    }

    private Resolution resolvePosition(Target.Position position, double radius) {
        String here = world.currentSpot().dimension();
        TravelDestination destination = position.y() == null
                ? TravelDestination.anyHeight(position.x(), position.z(), radius, position.dimension())
                : TravelDestination.confirmed(
                        new WorldPosition(position.x(), position.y(), position.z(), position.dimension()), radius);
        // 目的地落在别的维度：坐标按目标维度自己的坐标系写，跨维度段先送过去。
        if (position.dimension() != null && !position.dimension().equals(here)) {
            return routeAcross(position.dimension(), destination);
        }
        if (position.y() == null) {
            // 高度纪律：没给 y 就不猜高度，先走到那一柱列，到了再找能站的格子。
            return new Resolution.Ready(TravelDestination.anyHeight(
                    position.x(), position.z(), radius, position.dimension()));
        }
        return new Resolution.Ready(destination);
    }

    private Resolution resolveLandmark(Target.Landmark landmark, double radius) {
        return rememberedPlaces.place(landmark.name())
                .<Resolution>map(place -> resolveRemembered(place, radius))
                .orElseGet(() -> new Resolution.Unclear(QuestionEscalation.unclearTarget(
                        "没记住叫「" + landmark.name() + "」的地方，也不拿脚边顶替",
                        List.of(new Question.Option("tell_where", "改用坐标或看得见的东西说明目的地"),
                                new Question.Option("give_up", "这次不去，取消出行")))));
    }

    /** 记住的地点可能在别的维度（记的时候人在下界）：落在哪个维度按哪个维度走。 */
    private Resolution resolveRemembered(WorldPosition place, double radius) {
        TravelDestination destination = TravelDestination.confirmed(place, radius);
        String here = world.currentSpot().dimension();
        if (place.dimension() != null && !place.dimension().equals(here)) {
            return routeAcross(place.dimension(), destination);
        }
        return new Resolution.Ready(destination);
    }

    private Resolution resolveSeen(Target.Seen seen, double radius) {
        Optional<WorldPosition> place = seenTargets.positionOf(seen.id());
        if (place.isEmpty()) {
            return new Resolution.DeadEnd(Problem.of(Problem.Kind.TARGET_GONE,
                    "观察编号 " + seen.id() + " 对应的东西已经不在了，没法照着它走"));
        }
        return resolveRemembered(place.get(), radius);
    }

    private Resolution resolveDirection(Target.Direction direction, double radius) {
        WorldPosition spot = world.currentSpot();
        // 相对方向先按此刻朝向换算成东南西北，再算落点；落点的高度没法核实，走柱列。
        int[] vector = vectorOf(direction.toward());
        int x = spot.x() + vector[0] * direction.distance();
        int z = spot.z() + vector[1] * direction.distance();
        return new Resolution.Ready(TravelDestination.anyHeight(x, z, radius, spot.dimension()));
    }

    /** 方向变成单位向量（x, z）；相对的前后左右按此刻朝向折算。 */
    private int[] vectorOf(Target.Toward toward) {
        Target.Toward facing = world.currentFacing();
        return switch (toward) {
            case NORTH -> new int[]{0, -1};
            case SOUTH -> new int[]{0, 1};
            case WEST -> new int[]{-1, 0};
            case EAST -> new int[]{1, 0};
            case FORWARD -> vectorOf(facing);
            case BACKWARD -> reverse(vectorOf(facing));
            case LEFT -> rotateLeft(vectorOf(facing));
            case RIGHT -> rotateLeft(rotateLeft(rotateLeft(vectorOf(facing))));
        };
    }

    private int[] reverse(int[] vector) {
        return new int[]{-vector[0], -vector[1]};
    }

    // 面朝北时左手边是西：(0, -1) 转成 (-1, 0)。
    private int[] rotateLeft(int[] vector) {
        return new int[]{vector[1], -vector[0]};
    }
}
