// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.ReadsCharacterPosition;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.travel.ReadsSeenTargets;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 锚点解析：把施工或预览的目标对象落实成设计原点要落的那一格。脚边取角色脚下；坐标省略 y 时取那一列的地表；
 * 记得的地点按名字查；观察编号查场景里它在哪。只读现场、给结论，不动角色。
 */
public final class AnchorResolver {

    /** 解析的结局：落实了一格，或说不清。 */
    public sealed interface Resolution {
        record Ready(WorldPosition anchor) implements Resolution {}

        record Failed(Problem problem) implements Resolution {}
    }

    /** 读一列地表高度的接缝：那一列最上面挡得住身体的方块之上的那一格；没加载为空。 */
    public interface GroundHeights {
        Optional<Integer> surfaceY(int x, int z);
    }

    private final ReadsSeenTargets seen;
    private final ReadsRememberedPlaces places;
    private final ReadsCharacterPosition character;
    private final GroundHeights ground;

    public AnchorResolver(ReadsSeenTargets seen, ReadsRememberedPlaces places, ReadsCharacterPosition character, GroundHeights ground) {
        this.seen = Objects.requireNonNull(seen, "seen");
        this.places = Objects.requireNonNull(places, "places");
        this.character = Objects.requireNonNull(character, "character");
        this.ground = Objects.requireNonNull(ground, "ground");
    }

    public Resolution resolve(Target target) {
        if (target == null) {
            return new Resolution.Failed(Problem.of(Problem.Kind.INVALID_PARAMETER, "要给 target：图纸落在哪", "用 here、坐标、记得的地点或观察编号"));
        }
        return switch (target) {
            case Target.Here here -> new Resolution.Ready(character.currentPosition());
            case Target.Position position -> position(position);
            case Target.Landmark landmark -> places.place(landmark.name())
                    .<Resolution>map(Resolution.Ready::new)
                    .orElseGet(() -> new Resolution.Failed(Problem.of(Problem.Kind.NOT_FOUND,
                            "没记住叫「" + landmark.name() + "」的地方", "先用 remember 记下，或改用坐标")));
            case Target.Seen seenTarget -> seen.positionOf(seenTarget.id())
                    .<Resolution>map(Resolution.Ready::new)
                    .orElseGet(() -> new Resolution.Failed(Problem.of(Problem.Kind.TARGET_GONE,
                            "观察编号 " + seenTarget.id() + " 对应的东西已经不在了", "重新 observe 拿新的编号")));
            default -> new Resolution.Failed(Problem.of(Problem.Kind.UNSUPPORTED,
                    "施工的锚点不接受这种目标对象：" + target.kind(), "用 here、坐标、记得的地点或观察编号"));
        };
    }

    // 坐标：没写维度按角色所在的算；省略 y 取那一列的地表，那一列没加载就说不清。
    // 角色位置不带维度时就是"此刻所在的维度"，和坐标写的维度对不上才算跨维度。
    private Resolution position(Target.Position position) {
        String here = character.currentPosition().dimension();
        String dimension = position.dimension() == null ? here : position.dimension();
        if (here != null && dimension != null && !dimension.equals(here)) {
            return new Resolution.Failed(Problem.of(Problem.Kind.UNSUPPORTED, "锚点在另一个维度（" + dimension + "），跨维度施工还没有支持", null));
        }
        if (position.y() != null) return new Resolution.Ready(new WorldPosition(position.x(), position.y(), position.z(), dimension));
        Optional<Integer> surface = ground.surfaceY(position.x(), position.z());
        return surface.<Resolution>map(y -> new Resolution.Ready(new WorldPosition(position.x(), y, position.z(), dimension)))
                .orElseGet(() -> new Resolution.Failed(Problem.of(Problem.Kind.UNREACHABLE,
                        "（" + position.x() + ", " + position.z() + "）那一列还没加载，取不到地表高度", "先走过去，或把 y 写上")));
    }
}
