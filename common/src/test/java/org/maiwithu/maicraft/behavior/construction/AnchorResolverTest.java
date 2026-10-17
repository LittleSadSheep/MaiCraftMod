// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/** 锚点解析：脚下、坐标（省略 y 取地表）、记得的地点、观察编号；别的目标对象如实说不支持。 */
class AnchorResolverTest {

    private static final String DIM = "minecraft:overworld";
    private final AnchorResolver resolver = new AnchorResolver(
            id -> id.equals("b7") ? Optional.of(new WorldPosition(10, 70, 10, DIM)) : Optional.empty(),
            name -> name.equals("家") ? Optional.of(new WorldPosition(1, 65, 1, DIM)) : Optional.empty(),
            () -> new WorldPosition(5, 64, 5, DIM),
            (x, z) -> x == 20 ? Optional.of(71) : Optional.empty());

    private WorldPosition ready(Target target) {
        return assertInstanceOf(AnchorResolver.Resolution.Ready.class, resolver.resolve(target)).anchor();
    }

    private Problem failed(Target target) {
        return assertInstanceOf(AnchorResolver.Resolution.Failed.class, resolver.resolve(target)).problem();
    }

    @Test
    void 四种目标对象各落一格() {
        assertEquals(new WorldPosition(5, 64, 5, DIM), ready(new Target.Here()));
        assertEquals(new WorldPosition(3, 60, 4, DIM), ready(new Target.Position(3, 60, 4, null)), "没写维度按角色所在的算");
        assertEquals(new WorldPosition(20, 71, 2, DIM), ready(new Target.Position(20, null, 2, DIM)), "省略 y 取那一列的地表");
        assertEquals(new WorldPosition(1, 65, 1, DIM), ready(new Target.Landmark("家")));
        assertEquals(new WorldPosition(10, 70, 10, DIM), ready(new Target.Seen("b7")));
    }

    @Test
    void 角色位置不带维度时_坐标写不写维度都按此刻所在的算() {
        // 实机：角色位置读出来不带维度（就是此刻所在的维度），坐标不写维度曾空指针、写了又被当成跨维度。
        AnchorResolver here = new AnchorResolver(id -> Optional.empty(), name -> Optional.empty(),
                () -> WorldPosition.here(5, 64, 5), (x, z) -> Optional.empty());
        assertInstanceOf(AnchorResolver.Resolution.Ready.class, here.resolve(new Target.Position(3, 60, 4, null)));
        assertInstanceOf(AnchorResolver.Resolution.Ready.class, here.resolve(new Target.Position(3, 60, 4, DIM)));
    }

    @Test
    void 说不清的各有原因() {
        assertEquals(Problem.Kind.INVALID_PARAMETER, failed(null).kind());
        assertEquals(Problem.Kind.NOT_FOUND, failed(new Target.Landmark("矿洞")).kind());
        assertEquals(Problem.Kind.TARGET_GONE, failed(new Target.Seen("e3")).kind());
        assertEquals(Problem.Kind.UNREACHABLE, failed(new Target.Position(99, null, 2, DIM)).kind(), "那一列没加载");
        assertEquals(Problem.Kind.UNSUPPORTED, failed(new Target.Position(1, 64, 1, "minecraft:the_nether")).kind());
        assertEquals(Problem.Kind.UNSUPPORTED, failed(new Target.Player("Steve")).kind());
    }
}
