// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.maiwithu.maicraft.ability.vehicle.spi.TrainScheduler;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TaskInput;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 给列车定时刻表（machine_configure 指列车的一节车厢 e#，settings 写 schedule.*）：
 * {@code schedule.1.station} 去哪个站（可带 * 通配）、{@code schedule.1.wait} 到了等什么（delay 秒数、idle 秒数、powered、
 * unloaded，几条用逗号隔开、都满足才走；不写按机械动力时刻表界面的默认等 5 秒）、{@code schedule.cyclic} 循环不循环（默认循环）。
 * 写好交给坐在列车操纵台座位上的生物（列车长），由联动的定时刻表办法去做；手动开车不做。
 */
final class TrainScheduleTask extends PhasedTask<TrainScheduleTask.Phase> {

    enum Phase { FIND, RUN }

    /** 一张时刻表最多几站。 */
    static final int MAX_STOPS = 32;
    /** 不写等什么时，和机械动力时刻表界面的默认一样等 5 秒。 */
    static final String DEFAULT_WAIT = "delay 5";

    private static final Pattern STATION = Pattern.compile("schedule\\.(\\d+)\\.station");
    private static final Pattern WAIT = Pattern.compile("schedule\\.(\\d+)\\.wait");
    private static final Pattern CONDITION = Pattern.compile("(delay|idle) (\\d+)|powered|unloaded");

    /**
     * 给哪趟车、写什么。
     *
     * @param seenId 指它的观察编号（说的时候用）
     */
    record Input(String seenId, TrainScheduler.Request request) implements TaskInput {
        Input {
            Objects.requireNonNull(seenId, "seenId");
            Objects.requireNonNull(request, "request");
        }

        @Override public String describe() {
            return "给列车 " + seenId + " 定时刻表（" + request.stops().size() + " 站）";
        }
    }

    /** 结果细节：定时刻表的经过。 */
    record Details(TrainScheduler.Report schedule) implements ResultDetails {}

    private final Input input;
    private final List<TrainScheduler> schedulers;
    private TrainScheduler.Run run;

    TrainScheduleTask(Input input, List<TrainScheduler> schedulers) {
        super("定列车时刻表", Phase.FIND, new ProgressTracker(20L * 60, 20L * 60 * 3));
        this.input = input;
        this.schedulers = List.copyOf(schedulers);
    }

    /** machine_configure 指 e# 且写的是 schedule.* 时的决定：车还在不在、每一项写得对不对，都对就交给联动去做。 */
    static StepDecision decide(String seenId, Map<String, String> settings, MachineVehicles vehicles,
            Permissions permissions) {
        Optional<Integer> entity = vehicles.entityOf().apply(seenId);
        if (entity.isEmpty()) {
            return new StepDecision.Finish(TaskResult.failed("指的车不在了", Problem.of(Problem.Kind.TARGET_GONE,
                    "观察编号 " + seenId + " 指的车厢已经不在了", "重新 observe 再指")));
        }
        List<TrainScheduler.Stop> stops = new ArrayList<>();
        boolean[] cyclic = {true};
        String wrong = parse(settings, stops, cyclic);
        if (wrong != null) {
            return new StepDecision.Finish(TaskResult.failed("时刻表写法不对", Problem.of(Problem.Kind.INVALID_PARAMETER,
                    wrong, "例如 {\"schedule.1.station\": \"矿场\", \"schedule.1.wait\": \"delay 10\", "
                            + "\"schedule.2.station\": \"仓库\", \"schedule.cyclic\": \"true\"}")));
        }
        return new StepDecision.Run(new Input(seenId, new TrainScheduler.Request(entity.get(), stops, cyclic[0], permissions)));
    }

    /**
     * 读 schedule.* 设置：站号从 1 起连着编，每站要有站名，等什么几条用逗号隔开；有错返回一句为什么，全对返回 null。
     *
     * @param cyclic 读到的循环不循环写进第一格
     */
    static String parse(Map<String, String> settings, List<TrainScheduler.Stop> into, boolean[] cyclic) {
        Map<Integer, String> stations = new TreeMap<>();
        Map<Integer, String> waits = new TreeMap<>();
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            String key = entry.getKey().trim();
            String value = entry.getValue().trim();
            Matcher station = STATION.matcher(key);
            Matcher wait = WAIT.matcher(key);
            if (key.equals("schedule.cyclic")) {
                if (!value.equals("true") && !value.equals("false")) return "schedule.cyclic 写 true 或 false";
                cyclic[0] = value.equals("true");
            } else if (station.matches()) {
                if (value.isEmpty()) return key + " 要写站名";
                stations.put(Integer.parseInt(station.group(1)), value);
            } else if (wait.matches()) {
                waits.put(Integer.parseInt(wait.group(1)), value);
            } else {
                return "列车时刻表只认 schedule.N.station、schedule.N.wait、schedule.cyclic，收到「" + key + "」";
            }
        }
        if (stations.isEmpty()) return "时刻表至少要有一站：schedule.1.station";
        if (stations.size() > MAX_STOPS) return "时刻表最多 " + MAX_STOPS + " 站";
        for (Integer number : waits.keySet()) {
            if (!stations.containsKey(number)) return "schedule." + number + ".wait 没有对应的 schedule." + number + ".station";
        }
        int expected = 1;
        for (Map.Entry<Integer, String> station : stations.entrySet()) {
            if (station.getKey() != expected) return "站号要从 1 起连着编，缺 schedule." + expected + ".station";
            expected++;
            List<String> conditions = new ArrayList<>();
            String wait = waits.getOrDefault(station.getKey(), DEFAULT_WAIT);
            for (String part : wait.split("[,，、]")) {
                String condition = part.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                if (condition.isEmpty()) continue;
                if (!CONDITION.matcher(condition).matches()) {
                    return "schedule." + station.getKey() + ".wait 里的「" + part.trim() + "」认不出：写 delay 秒数、idle 秒数、powered、unloaded";
                }
                conditions.add(condition);
            }
            if (conditions.isEmpty()) conditions.add(DEFAULT_WAIT);
            into.add(new TrainScheduler.Stop(station.getValue(), conditions));
        }
        return null;
    }

    @Override protected Action enter(Phase phase) {
        return phase == Phase.RUN ? run : null;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case FIND -> find();
            case RUN -> runActionThen(context, this::settle);
        };
    }

    private Next<Phase> find() {
        for (TrainScheduler scheduler : schedulers) {
            Optional<TrainScheduler.Run> started = scheduler.schedule(input.request());
            if (started.isPresent()) {
                run = started.get();
                return Next.go(Phase.RUN, "去找列车长");
            }
        }
        return Next.fail(Problem.of(Problem.Kind.UNSUPPORTED, input.seenId() + " 不是认得的列车车厢（要装了机械动力的联动）"));
    }

    // 结算：写进去没有、读回是什么、换没换掉旧表、交上没有，分开说。
    private Next<Phase> settle() {
        TrainScheduler.Report report = run.report();
        if (report == null) return Next.fail(Problem.of(Problem.Kind.INTERNAL_ERROR, "定时刻表做完了却没有交代经过"));
        return Next.done(TaskResult.done("时刻表" + (report.replaced() ? "换掉了旧的，" : "") + "交给了列车长（"
                + report.conductor() + "）：" + String.join("；", report.readBack())));
    }

    @Override protected ResultDetails details() {
        TrainScheduler.Report report = run == null ? null : run.report();
        return report == null ? ResultDetails.NONE : new Details(report);
    }

    @Override protected String describePhase(Phase value) {
        return value == Phase.FIND ? "认列车" : "定时刻表";
    }
}
