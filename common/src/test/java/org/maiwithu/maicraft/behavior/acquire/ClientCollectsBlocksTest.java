// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.inventory.PicksUpDrops;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 把一格收进包：先走过去、再挖、最后只捡这一下掉出来的；手上那一步跟着暂停与收尾。 */
class ClientCollectsBlocksTest {

    private static final BlockPos ORE = new BlockPos(5, 60, 5);

    private final List<String> log = new ArrayList<>();

    /** 记一笔就做完的动作；记下有没有被暂停、被收尾。 */
    private final class Step implements Action {
        private final String name;
        private final ActionStatus end;
        boolean paused;
        boolean closed;

        Step(String name, ActionStatus end) {
            this.name = name;
            this.end = end;
        }

        @Override public ActionStatus tick(TickContext context) {
            log.add(name);
            return end;
        }

        @Override public void pause() { paused = true; }
        @Override public void close() { closed = true; }
        @Override public String describe() { return name; }
    }

    private Set<Integer> askedSince;

    private ClientCollectsBlocks collects(Step approach, Step dig, Step pickUp) {
        BringsPlayerClose close = (target, permissions) -> approach;
        DigsBlocks digs = target -> Optional.of(dig);
        PicksUpDrops drops = new PicksUpDrops() {
            @Override public Set<Integer> nearby() {
                log.add("记下脚边原有的");
                return Set.of(7);
            }

            @Override public Action pickUpNewSince(Set<Integer> before) {
                askedSince = before;
                return pickUp;
            }
        };
        return new ClientCollectsBlocks(close, digs, drops);
    }

    @Test
    void 走过去_挖_只捡这一下掉出来的() {
        Action collect = collects(new Step("走", ActionStatus.done()), new Step("挖", ActionStatus.done()),
                new Step("捡", ActionStatus.done())).collect(ORE, Permissions.DEFAULT).orElseThrow();
        while (!(collect.tick(null) instanceof ActionStatus.Done)) {
            // 一步步推进到做完。
        }
        assertEquals(List.of("走", "记下脚边原有的", "挖", "捡"), log);
        assertEquals(Set.of(7), askedSince);
    }

    @Test
    void 走不到如实按到不了失败() {
        Action collect = collects(new Step("走", ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "被围住了", null))),
                new Step("挖", ActionStatus.done()), new Step("捡", ActionStatus.done()))
                .collect(ORE, Permissions.DEFAULT).orElseThrow();
        ActionStatus status = collect.tick(null);
        assertTrue(status instanceof ActionStatus.Failed failed
                && failed.problem().kind() == Problem.Kind.UNREACHABLE, status.toString());
        assertTrue(!log.contains("挖"), "走不到不该隔空挖");
    }

    @Test
    void 一批三格_逐格走过去挖掉_整批挖完只捡一次() {
        // 砍一棵树：三根原木一根一根靠近、挖掉，挖完整根树干才去捡一次，不是砍一根捡一趟。
        Action collect = collects(new Step("走", ActionStatus.done()), new Step("挖", ActionStatus.done()),
                new Step("捡", ActionStatus.done()))
                .collectBatch(List.of(ORE, ORE.above(), ORE.above(2)), Permissions.DEFAULT).orElseThrow();
        while (!(collect.tick(null) instanceof ActionStatus.Done)) {
            // 一步步推进到做完。
        }
        assertEquals(List.of("走", "记下脚边原有的", "挖", "走", "挖", "走", "挖", "捡"), log);
        assertEquals(Set.of(7), askedSince, "只捡第一下动手之后新冒出来的");
    }

    @Test
    void 手上那一步跟着暂停与收尾() {
        Step dig = new Step("挖", ActionStatus.running());
        Action collect = collects(new Step("走", ActionStatus.done()), dig, new Step("捡", ActionStatus.done()))
                .collect(ORE, Permissions.DEFAULT).orElseThrow();
        collect.tick(null);
        collect.tick(null);
        collect.pause();
        assertTrue(dig.paused);
        collect.close();
        assertTrue(dig.closed);
    }
}
