// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.find;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.maiwithu.maicraft.behavior.perception.DirectionWords;
import org.maiwithu.maicraft.behavior.perception.RemembersSightings;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SceneSelf;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 寻找任务：每刻问一轮扫描，收命中、发观察编号，找满即收工。
 *
 * <p>命中复核与视线闸在读端裁决过才到这里；任务这边守住计数纪律——
 * 同一次运行里每样只数一次，读端重复给出的不再收；扫完了还不足就按结算如实交账。
 * 视线验证过的方块命中顺路写进世界记忆（来源=亲眼看到），后续采集与出行不再被重复质疑。
 */
final class FindTask extends PhasedTask<FindTask.Phase> {

    /** 寻找的阶段：只扫描，不动身体。 */
    enum Phase { SCAN }

    // 十秒没有新命中就该问问是不是扫完了；两分钟还扫不完按卡住收场，不无限等。
    private static final long STUCK_AFTER_TICKS = 200;
    private static final long MAX_TICKS = 20L * 120;

    private final FindInput input;
    private final Scene scene;
    private final FindsAround finder;
    private final RemembersSightings memory;

    /** 已经数过的命中，按观察编号收着，结算时由近到远给出。 */
    private final Map<String, FindDetails.Found> accepted = new LinkedHashMap<>();
    /** 去重的凭据：实体按游戏实体编号，方块与线索按位置。 */
    private final Set<String> counted = new HashSet<>();

    FindTask(FindInput input, Scene scene, FindsAround finder, RemembersSightings memory) {
        super("寻找", Phase.SCAN, new ProgressTracker(STUCK_AFTER_TICKS, MAX_TICKS));
        this.input = input;
        this.scene = scene;
        this.finder = finder;
        this.memory = memory;
    }

    @Override
    protected Action enter(Phase phase) {
        // 扫描读端自己分刻推进，这里没有要先动手准备的东西。
        return null;
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        FindsAround.Round round = finder.scan(input);
        if (round.worldChanged()) {
            // 扫描中换了世界：这一轮的命中作废，如实结束，不冒充任何找没找到的结论。
            return Next.done(TaskResult.cancelled("寻找：中途换到另一个世界，这次寻找作废"));
        }
        SceneSelf self = scene.self();
        if (self == null) {
            // 还没看清自己的位置，整理不出方位：等自身观察到了再收命中。
            return Next.stay();
        }
        accept(self, round.hits(), context.gameTick());
        List<FindDetails.Found> found = found();
        if (found.size() >= input.count()) {
            return Next.done(FindSettlement.done(input, found, countByType(found)));
        }
        if (round.complete()) {
            return Next.done(FindSettlement.insufficient(input, found, countByType(found)));
        }
        return Next.stay();
    }

    /** 收下这一轮没数过的命中：由近到远收，发观察编号，方块命中顺路写进世界记忆。 */
    private void accept(SceneSelf self, List<FindsAround.Hit> hits, long nowTick) {
        List<FindsAround.Hit> fresh = new ArrayList<>();
        for (FindsAround.Hit hit : hits) {
            if (!counted.contains(countKey(hit))) {
                fresh.add(hit);
            }
        }
        // 由近到远收：找满即停，不把更远的也数进来。
        fresh.sort((first, second) -> Double.compare(distance(self, first), distance(self, second)));
        for (FindsAround.Hit hit : fresh) {
            if (accepted.size() >= input.count()) {
                return;
            }
            counted.add(countKey(hit));
            String id = issueId(hit, self, nowTick);
            FindDetails.Found found = toFound(id, hit, self);
            accepted.put(id, found);
            recordProgress("找到 " + hit.typeId() + "（" + found.direction() + "，" + found.distance() + "格）");
            // 视线验证过的方块命中写进世界记忆：这里是"亲眼看到这里有"，不猜里面有什么。
            if (hit.kind() == FindInput.FindKind.BLOCK) {
                memory.siteSeen(hit.position(), List.of(hit.typeId()), Instant.now());
            }
        }
    }

    /** 发观察编号：方块 b#、实体 e#（同一只沿用旧编号）、结构线索 f#。 */
    private String issueId(FindsAround.Hit hit, SceneSelf self, long nowTick) {
        String direction = directionOf(hit.position(), self);
        return switch (hit.kind()) {
            case BLOCK -> scene.seen().facility(hit.position(), direction, nowTick);
            case ENTITY -> scene.seen().entity(hit.gameEntityId(), hit.position(), direction, nowTick);
            case STRUCTURE -> scene.seen().feature(hit.position(), direction, nowTick);
        };
    }

    private FindDetails.Found toFound(String id, FindsAround.Hit hit, SceneSelf self) {
        int dx = hit.position().x() - (int) Math.floor(self.x());
        int dy = hit.position().y() - (int) Math.floor(self.y());
        int dz = hit.position().z() - (int) Math.floor(self.z());
        return new FindDetails.Found(id, hit.typeId(), hit.position(),
                (int) Math.round(Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz)),
                dy,
                DirectionWords.relative(dx, dz, self.facingYaw()),
                DirectionWords.compassOf(dx, dz),
                hit.creatureProtected(), hit.protectionReason());
    }

    private String directionOf(WorldPosition position, SceneSelf self) {
        return DirectionWords.relative(position.x() - (int) Math.floor(self.x()),
                position.z() - (int) Math.floor(self.z()), self.facingYaw());
    }

    // 由近到远收时用的水平距离：找 nearest 用三维没有意义，高度差单列在结果里。
    private double distance(SceneSelf self, FindsAround.Hit hit) {
        double dx = hit.position().x() - self.x();
        double dz = hit.position().z() - self.z();
        return dx * dx + dz * dz;
    }

    private String countKey(FindsAround.Hit hit) {
        return hit.gameEntityId() >= 0
                ? "e" + hit.gameEntityId()
                : "p" + hit.position().x() + "," + hit.position().y() + "," + hit.position().z();
    }

    private List<FindDetails.Found> found() {
        List<FindDetails.Found> entries = new ArrayList<>(accepted.values());
        entries.sort((first, second) -> Integer.compare(first.distance(), second.distance()));
        return entries;
    }

    private Map<String, Integer> countByType(List<FindDetails.Found> found) {
        Map<String, Integer> counts = new HashMap<>();
        for (FindDetails.Found entry : found) {
            counts.merge(entry.type(), 1, Integer::sum);
        }
        return counts;
    }

    @Override
    protected ResultDetails details() {
        List<FindDetails.Found> found = found();
        return new FindDetails(found, input.radius(), true, countByType(found));
    }

    @Override
    protected String describePhase(Phase value) {
        return input.describe();
    }
}
