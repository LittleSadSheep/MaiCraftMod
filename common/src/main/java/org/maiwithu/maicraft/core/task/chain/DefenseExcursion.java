// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.chain;

import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 一段自卫插曲：从第一次接管身体到把身体交还工作为止，中间可能接连打好几场。
 * 只记观察事实——接管时站在哪（工位）、打断了哪个任务、离工位最远多远、最后停在哪——
 * 供开始与结束通知、被打断任务的回执共用；不保存路线、实体编号或其他内部句柄。
 */
final class DefenseExcursion {
    private final BlockPos workSite;
    private final Vec3 workSiteExact;
    private final String dimension;
    private final long startedTick;
    private final float startHealth;
    private final int initialThreats;
    private final TaskRecord interrupted;
    private int fights;
    private double farthest;

    // 第一场自卫开打时建立插曲：此刻脚下就是工位，此刻当前任务槽里的任务就是被打断的工作。
    DefenseExcursion(LocalPlayer player, int threats) {
        workSite = player.blockPosition().immutable();
        workSiteExact = player.position();
        dimension = dimension(player);
        startedTick = player.level().getGameTime();
        startHealth = player.getHealth();
        initialThreats = threats;
        interrupted = CompanionTickDispatcher.current();
    }

    // 同一段插曲里打完一只又来一只时只累计场次，不重复发开始通知，也不换工位。
    void fightStarted() {
        fights++;
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

    /** 被打断的任务此刻的状态：仍在当前槽且未暂停才算继续，其余如实报告暂停、被替换或已结束。 */
    String taskState() {
        if (interrupted == null) return "none";
        if (interrupted.getState().isTerminal()) return "finished";
        if (CompanionTickDispatcher.current() != interrupted) return "replaced";
        if (interrupted instanceof IntentTaskRecord intent && intent.paused()) return "paused";
        return "continuing";
    }

    // 开始通知：接管处（之后回位的工位）和被打断的任务，模型一收到就知道角色是从哪项工作里被拉走的。
    JsonObject startedFacts() {
        JsonObject facts = new JsonObject();
        facts.add("work_site", position(workSite, dimension));
        if (interrupted != null) facts.add("interrupted_task", describeTask());
        return facts;
    }

    // 结束通知：工位、现位置、现距离、最远距离和场次，再附上被打断任务现在是否继续执行。
    JsonObject finishedFacts(LocalPlayer player) {
        JsonObject facts = new JsonObject();
        facts.add("work_site", position(workSite, dimension));
        facts.add("position", position(player.blockPosition(), dimension(player)));
        double distance = distance(player);
        if (Double.isNaN(distance)) facts.addProperty("same_dimension", false);
        else facts.addProperty("distance_from_work_site", round(distance));
        facts.addProperty("max_distance_from_work_site", round(farthest));
        facts.addProperty("fights", fights);
        if (interrupted != null) {
            JsonObject task = describeTask();
            task.addProperty("state", taskState());
            facts.add("interrupted_task", task);
        }
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
