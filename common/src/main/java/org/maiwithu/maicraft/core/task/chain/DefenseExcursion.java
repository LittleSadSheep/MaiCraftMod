// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.chain;

import com.google.gson.JsonObject;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.combat.Menace;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 一段自卫插曲：从第一次接管身体到把身体交还工作为止，中间可能接连打好几场。
 * 只记观察事实——接管时站在哪（工位）、打断了哪个任务、离工位最远多远、最后停在哪——
 * 供开始与结束通知、被打断任务的回执共用；不保存路线、实体编号或其他内部句柄。
 *
 * <p>打完后被带离工位超过 {@link #RETURN_DISTANCE} 格时先沿只走不改地形的步行路线回去，
 * 到了再交还身体；伤重、路线连续失败、回位途中反复挨打或换了维度时不再硬走，
 * 暂停被打断的任务并把工位、现位置和回位经过交给模型决定。
 */
final class DefenseExcursion {
    /** 离接管处超过这么远才走回去；几格之内的走位不值得专门往回走，任务自己会就近接着干。 */
    static final double RETURN_DISTANCE = 6.0;
    /** 回到工位的判定：水平两格、上下三格内都算回来了——工位常在脚手架或台阶上，站到底下由原任务自己再上去。 */
    private static final double ARRIVED_RADIUS = 2.0;
    private static final double ARRIVED_HEIGHT = 3.0;
    /** 回位路线最多失败这么多次就停下交给模型；再试只会在同一处反复规划。 */
    private static final int MAX_ROUTE_FAILURES = 2;
    /** 回位途中最多被新的袭击打断这么多次；工位附近一直有怪时不再往回送，免得自卫无限占着身体。 */
    private static final int MAX_RETURN_FIGHTS = 2;
    private static final String WALK_BACK = "walk_back_if_displaced";
    /** 本身就在赶路、跟随或追着目标走的能力：接管处只是路过的点，走回去只会白走一趟或打乱原路线。 */
    private static final Set<String> MOVING_ABILITIES = Set.of(
            "maicraft:travel", "maicraft:travel_dimension", "maicraft:follow", "maicraft:explore",
            "maicraft:find_structure", "maicraft:reach_milestone", "maicraft:obtain_elytra",
            "maicraft:defeat_ender_dragon", "maicraft:physical_control", "maicraft:combat", "maicraft:sleep");
    /** 这些回位结论表示角色仍被留在别处而原任务还在执行，必须暂停任务交给模型，不能让它在新地点悄悄续上。 */
    private static final Set<String> STRANDED = Set.of("too_hurt", "path_failed", "repeated_attacks", "dimension_changed");

    private final BlockPos workSite;
    private final Vec3 workSiteExact;
    private final String dimension;
    private final long startedTick;
    private final float startHealth;
    private final int initialThreats;
    private final TaskRecord interrupted;
    private final String returnPlan;
    private final NavGoal returnGoal;
    private final MobDefenseChain.ReturnRoute route;
    private int fights;
    private double farthest;
    private boolean walking;
    private int routeFailures;
    private int returnFights;
    private String lastRouteFailure;
    private String returnStatus;

    // 第一场自卫开打时建立插曲：此刻脚下就是工位，此刻当前任务槽里的任务就是被打断的工作，
    // 回不回工位也在此刻按被打断的步骤和是否骑乘定下，之后不随战斗过程改变。
    DefenseExcursion(LocalPlayer player, int threats, MobDefenseChain.ReturnRoute route) {
        workSite = player.blockPosition().immutable();
        workSiteExact = player.position();
        dimension = dimension(player);
        startedTick = player.level().getGameTime();
        startHealth = player.getHealth();
        initialThreats = threats;
        interrupted = CompanionTickDispatcher.current();
        returnPlan = plan(player, interrupted);
        returnGoal = NavGoal.nearGround(workSite, ARRIVED_RADIUS, ARRIVED_HEIGHT);
        this.route = route;
    }

    // 只有仍在执行的总任务才回工位：它能被暂停并收到通知；赶路类步骤和骑乘中的身体只报告位移不往回走。
    private static String plan(LocalPlayer player, TaskRecord task) {
        if (task == null) return "none_no_task";
        if (!(task instanceof IntentTaskRecord intent)) return "none_untracked_task";
        if (intent.getState().isTerminal() || intent.paused()) return "none_task_inactive";
        int step = intent.stepIndex();
        if (step >= 0 && step < intent.steps().size()
                && MOVING_ABILITIES.contains(intent.steps().get(step).ability())) return "none_moving_task";
        if (player.isPassenger()) return "none_riding";
        return WALK_BACK;
    }

    // 同一段插曲里打完一只又来一只时只累计场次，不重复发开始通知，也不换工位；
    // 若这一场是在回位途中打起来的，当前路线作废并记一次被打断，打完再从当时位置重新规划。
    void fightStarted() {
        fights++;
        if (walking) {
            returnFights++;
            route.stop();
        }
    }

    // 被更急的本能（换气、落地保护、贴边站稳）抢走身体时只停下当前路线，不计失败，回来后从当时位置重新规划。
    void suspendWalk() {
        route.stop();
    }

    int initialThreats() {
        return initialThreats;
    }

    TaskRecord interrupted() {
        return interrupted;
    }

    BlockPos workSite() {
        return workSite;
    }

    // 每刻量一次离工位多远并记下最远距离，回执据此说明角色曾被带出多远；换维度后无法比较就不记。
    void observe(LocalPlayer player) {
        double distance = distance(player);
        if (!Double.isNaN(distance)) farthest = Math.max(farthest, distance);
    }

    // 与工位的直线距离；角色已到另一个维度时没有可比距离，返回 NaN。
    double distance(LocalPlayer player) {
        return dimension.equals(dimension(player)) ? player.position().distanceTo(workSiteExact) : Double.NaN;
    }

    // 插曲期间红心净减少多少；回血或吸收值不冒充为没受伤，只报告起止两次读数之差。
    float healthLost(LocalPlayer player) {
        return Math.max(0.0F, startHealth - player.getHealth());
    }

    /**
     * 危险与宽限都过去后的一刻：先核对还该不该回、已经到没到，再判断伤势和被打断次数，最后沿路线走一步。
     * 返回 true 表示还在往回走、身体继续由自卫占着；返回 false 时回位已有结论，由调用方收尾插曲。
     */
    boolean walkBack(LocalPlayer player) {
        if (returnStatus != null) return false;
        String blocked = returnBlocked(player);
        if (blocked != null) return conclude(blocked);
        if (arrived(player)) return conclude(walking ? "returned" : "not_needed");
        if (!walking && distance(player) <= RETURN_DISTANCE) return conclude("not_needed");
        // 撤退多半是因为血量见底，原来的怪往往还在工位附近；这时往回走只会再挨打，先停下交给模型安排。
        if (Menace.outmatched(player)) return conclude("too_hurt");
        if (returnFights >= MAX_RETURN_FIGHTS) return conclude("repeated_attacks");
        walking = true;
        PlayerNav.Status status = route.step(player, returnGoal, () -> arrived(player));
        if (status == PlayerNav.Status.RUNNING) return true;
        if (status == PlayerNav.Status.ARRIVED && arrived(player)) return conclude("returned");
        // 路线失败或报告到达却不在工位范围：记一次失败，下一刻从当前位置重新规划，连续失败就停下。
        routeFailures++;
        lastRouteFailure = status == PlayerNav.Status.FAILED
                ? route.failure() : "route ended outside the work site";
        route.stop();
        return routeFailures >= MAX_ROUTE_FAILURES ? conclude("path_failed") : true;
    }

    // 回位的前提逐条核对：计划要回、身体还在、被打断的任务仍在执行、人还在同一维度；任何一条不满足就按原因收尾。
    private String returnBlocked(LocalPlayer player) {
        if (!WALK_BACK.equals(returnPlan)) return "not_planned";
        if (player.isDeadOrDying()) return "body_unavailable";
        String task = taskState();
        if (!"continuing".equals(task)) return "task_" + task;
        if (!dimension.equals(dimension(player))) return "dimension_changed";
        return null;
    }

    private boolean arrived(LocalPlayer player) {
        return dimension.equals(dimension(player)) && returnGoal.isAt(PlayerNav.playerFeet(player));
    }

    private boolean conclude(String status) {
        returnStatus = status;
        route.stop();
        return false;
    }

    // 收尾时回位还没有结论（自卫被叫停或身体没了），如实记为中止，不冒充已回到工位。
    void abandonWalk(String status) {
        if (returnStatus == null) conclude(status);
    }

    /**
     * 回位没成且被打断的任务仍在执行时，暂停它并发出暂停通知；模型已暂停、替换或结束的任务不动。
     * 暂停后继续只会从当前位置接着做，不会再自动走回工位，通知里写明这一点。
     */
    boolean pauseStrandedTask(LocalPlayer player, long gameTime) {
        if (!STRANDED.contains(returnStatus) || !(interrupted instanceof IntentTaskRecord intent)) return false;
        JsonObject facts = finishedFacts(player);
        facts.remove("interrupted_task");
        facts.addProperty("resume_effect", "continues_from_current_position");
        return IntentRuntime.get().selfDefenseDisplaced(intent, gameTime, facts);
    }

    /** 被打断的任务此刻的状态：仍在当前槽且未暂停才算继续，其余如实报告暂停、被替换或已结束。 */
    String taskState() {
        if (interrupted == null) return "none";
        if (interrupted.getState().isTerminal()) return "finished";
        if (CompanionTickDispatcher.current() != interrupted) return "replaced";
        if (interrupted instanceof IntentTaskRecord intent && intent.paused()) return "paused";
        return "continuing";
    }

    // 开始通知：接管处（之后回位的工位）、被打断的任务和打完后会不会走回来，模型一收到就知道角色是从哪项工作里被拉走的。
    JsonObject startedFacts() {
        JsonObject facts = new JsonObject();
        facts.add("work_site", position(workSite, dimension));
        if (interrupted != null) facts.add("interrupted_task", describeTask());
        facts.addProperty("return_plan", returnPlan);
        return facts;
    }

    // 结束通知：工位、现位置、现距离、最远距离、场次和回位经过，再附上被打断任务现在是否继续执行。
    JsonObject finishedFacts(LocalPlayer player) {
        JsonObject facts = new JsonObject();
        facts.add("work_site", position(workSite, dimension));
        facts.add("position", position(player.blockPosition(), dimension(player)));
        double distance = distance(player);
        if (Double.isNaN(distance)) facts.addProperty("same_dimension", false);
        else facts.addProperty("distance_from_work_site", round(distance));
        facts.addProperty("max_distance_from_work_site", round(farthest));
        facts.addProperty("fights", fights);
        facts.add("return", returnFacts());
        if (interrupted != null) {
            JsonObject task = describeTask();
            task.addProperty("state", taskState());
            facts.add("interrupted_task", task);
        }
        return facts;
    }

    // 回位经过：计划、结论、路线失败次数与最后一次失败原因、途中被打断的场次，模型据此判断要不要另派路线或先处理威胁。
    private JsonObject returnFacts() {
        JsonObject facts = new JsonObject();
        facts.addProperty("plan", returnPlan);
        facts.addProperty("status", returnStatus == null ? "unfinished" : returnStatus);
        facts.addProperty("route_failures", routeFailures);
        facts.addProperty("fights_during_return", returnFights);
        if (lastRouteFailure != null) facts.addProperty("last_route_failure", lastRouteFailure);
        return facts;
    }

    // 给被打断的总任务记一笔账：结束事实去掉重复的任务身份，补上起止游戏刻和战果说明。
    void recordOnTask(JsonObject finishedFacts, String outcome, long finishedTick) {
        if (!(interrupted instanceof IntentTaskRecord intent)) return;
        JsonObject entry = finishedFacts.deepCopy();
        entry.remove("interrupted_task");
        entry.addProperty("outcome", outcome);
        entry.addProperty("started_game_time", startedTick);
        entry.addProperty("finished_game_time", finishedTick);
        intent.recordSelfDefenseExcursion(entry);
    }

    // 总任务给出公开编号与当时所在步骤的能力；其他内部任务只给工具名，不暴露内部动作编号。
    private JsonObject describeTask() {
        JsonObject task = new JsonObject();
        if (interrupted instanceof IntentTaskRecord intent) {
            task.addProperty("task_id", intent.externalId().toString());
            int step = intent.stepIndex();
            if (step >= 0 && step < intent.steps().size()) {
                task.addProperty("ability", intent.steps().get(step).ability());
                task.addProperty("step_index", step);
            }
        } else {
            task.addProperty("tool", interrupted.getToolName());
        }
        return task;
    }

    private static JsonObject position(BlockPos pos, String dimension) {
        JsonObject result = new JsonObject();
        result.addProperty("x", pos.getX());
        result.addProperty("y", pos.getY());
        result.addProperty("z", pos.getZ());
        result.addProperty("dimension", dimension);
        return result;
    }

    private static String dimension(LocalPlayer player) {
        return player.level().dimension().location().toString();
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
