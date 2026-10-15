// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
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
    void 一格挖不下来就跳过_挖完别的照样去捡() {
        // 砍树：最高那根原木在树冠里看不着，挖了十五秒没下来就跳过，下面那根挖下来照样去捡。
        Step stuck = new Step("挖不下来", ActionStatus.running());
        Step fine = new Step("挖", ActionStatus.done());
        BringsPlayerClose close = (target, permissions) -> new Step("走", ActionStatus.done());
        DigsBlocks digs = target -> Optional.of(target.equals(ORE.above()) ? stuck : fine);
        PicksUpDrops drops = new PicksUpDrops() {
            @Override public Set<Integer> nearby() { return Set.of(); }
            @Override public Action pickUpNewSince(Set<Integer> before) { return new Step("捡", ActionStatus.done()); }
        };
        Action collect = new ClientCollectsBlocks(close, digs, drops)
                .collectBatch(List.of(ORE, ORE.above()), Permissions.DEFAULT).orElseThrow();
        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 400 && !(status instanceof ActionStatus.Done); i++) status = collect.tick(null);
        assertTrue(status instanceof ActionStatus.Done, status.toString());
        assertTrue(stuck.closed, "挖不下来的那一格收了尾");
        assertTrue(log.contains("捡"), "挖下来的照样去捡");
    }

    @Test
    void 一格走不到就跳过它_挖下来的照样去捡_跳过的格报给来源() {
        // 坡上砍一棵树：下面两根挖下来了，树顶那根够不着就跳过它，已经挖下来的照样走过去捡，不丢在坡下。
        BlockPos high = ORE.above(2);
        BringsPlayerClose close = (target, permissions) -> high.equals(target.block())
                ? new Step("走不到", ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "没有能靠近目标的站位", null)))
                : new Step("走", ActionStatus.done());
        DigsBlocks digs = target -> Optional.of(new Step("挖", ActionStatus.done()));
        PicksUpDrops drops = new PicksUpDrops() {
            @Override public Set<Integer> nearby() { return Set.of(); }
            @Override public Action pickUpNewSince(Set<Integer> before) { return new Step("捡", ActionStatus.done()); }
        };
        List<BlockPos> givenUp = new ArrayList<>();
        Action collect = new ClientCollectsBlocks(close, digs, drops)
                .collectBatch(List.of(ORE, ORE.above(), high), Permissions.DEFAULT, null, givenUp::add).orElseThrow();
        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 50 && !(status instanceof ActionStatus.Done); i++) status = collect.tick(null);
        assertTrue(status instanceof ActionStatus.Done, status.toString());
        assertEquals(List.of("走", "挖", "走", "挖", "走不到", "捡"), log);
        assertEquals(List.of(high), givenUp);
    }

    @Test
    void 一格都没收下来才失败_不去捡() {
        BringsPlayerClose close = (target, permissions) ->
                new Step("走不到", ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "没有能靠近目标的站位", null)));
        DigsBlocks digs = target -> Optional.of(new Step("挖", ActionStatus.done()));
        PicksUpDrops drops = new PicksUpDrops() {
            @Override public Set<Integer> nearby() { return Set.of(); }
            @Override public Action pickUpNewSince(Set<Integer> before) { return new Step("捡", ActionStatus.done()); }
        };
        List<BlockPos> givenUp = new ArrayList<>();
        Action collect = new ClientCollectsBlocks(close, digs, drops)
                .collectBatch(List.of(ORE, ORE.above()), Permissions.DEFAULT, null, givenUp::add).orElseThrow();
        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 50 && status instanceof ActionStatus.Running; i++) status = collect.tick(null);
        assertTrue(status instanceof ActionStatus.Failed failed
                && failed.problem().kind() == Problem.Kind.UNREACHABLE
                && failed.problem().message().startsWith("这 2 格一格都没挖下来，第一格：走不到"), status.toString());
        assertEquals(List.of(ORE, ORE.above()), givenUp);
        assertTrue(!log.contains("挖") && !log.contains("捡"), log.toString());
        assertTrue(collect.tick(null) instanceof ActionStatus.Failed, "收场后再问照样是失败");
    }

    @Test
    void 连锁挖一下挖掉整根树干_其余几格不再走过去_挖完捡一次() {
        // 装了连锁挖：第一根原木连锁时整根树干一起没了，后两格已经空了，不再走过去挖；掉落物挖完一起捡。
        Set<BlockPos> gone = new HashSet<>();
        List<Predicate<BlockPos>> asked = new ArrayList<>();
        BringsPlayerClose close = (target, permissions) -> new Step("走", ActionStatus.done());
        DigsBlocks digs = new DigsBlocks() {
            @Override public Optional<Action> dig(BlockPos target) {
                return Optional.of(new Step("挖一格", ActionStatus.done()));
            }
            @Override public Optional<Action> digChained(BlockPos trigger, Direction face,
                    BreakAccelerator.ChainShape shape, Predicate<BlockPos> mayBreak) {
                asked.add(mayBreak);
                assertEquals(BreakAccelerator.ChainShape.CONNECTED, shape, "采掘连锁相连的同种方块");
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        gone.addAll(List.of(ORE, ORE.above(), ORE.above(2)));
                        log.add("连锁挖");
                        return ActionStatus.done();
                    }
                    @Override public String describe() { return "连锁挖"; }
                });
            }
            @Override public boolean gone(TickContext context, BlockPos cell) {
                return gone.contains(cell);
            }
        };
        PicksUpDrops drops = new PicksUpDrops() {
            @Override public Set<Integer> nearby() { return Set.of(); }
            @Override public Action pickUpNewSince(Set<Integer> before) { return new Step("捡", ActionStatus.done()); }
        };
        Predicate<BlockPos> natural = pos -> pos.getX() == ORE.getX();
        Action collect = new ClientCollectsBlocks(close, digs, drops)
                .collectBatch(List.of(ORE, ORE.above(), ORE.above(2)), Permissions.DEFAULT, natural).orElseThrow();
        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < 50 && !(status instanceof ActionStatus.Done); i++) status = collect.tick(null);
        assertTrue(status instanceof ActionStatus.Done, status.toString());
        assertEquals(List.of("走", "连锁挖", "捡"), log);
        assertEquals(List.of(natural), asked, "连锁时每一格都交给来源给的判断");
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

    @Test
    void 挖到一半被打断_回来先重新走到跟前_挖的限时从头算() {
        // 实机 10-15：收床挖到一半被饥饿叫走，回来隔着老远接着挖，挖的时间连着打断前的一起算，
        // 十五秒一到就说挖不下来。回来要先走回跟前，挖的限时从头算。
        Step digging = new Step("挖", ActionStatus.running());
        Action collect = collects(new Step("走", ActionStatus.done()), digging, new Step("捡", ActionStatus.done()))
                .collect(ORE, Permissions.DEFAULT).orElseThrow();
        for (int i = 0; i < 200; i++) collect.tick(null);
        collect.pause();
        assertTrue(digging.paused, "被打断时先停住挖");
        ActionStatus status = ActionStatus.running();
        // 打断前已挖了快两百刻；回来再挖两百五十刻也不该按"十五秒挖不下来"收场。
        for (int i = 0; i < 250 && status instanceof ActionStatus.Running; i++) status = collect.tick(null);
        assertTrue(status instanceof ActionStatus.Running, status.toString());
        assertEquals(2, log.stream().filter("走"::equals).count(), "回来先重新走到跟前");
        assertTrue(digging.closed, "打断前那一下挖掘先收尾，重新走过去再挖");
    }
}
