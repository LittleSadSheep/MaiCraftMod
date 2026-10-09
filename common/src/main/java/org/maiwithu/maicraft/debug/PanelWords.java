// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.util.Locale;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.goal.GoalRunState;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 面板上的说法：枚举值、时长、目标对象在面板上怎么写。用的词和术语表一致，
 * 问题种类与事件种类的中文跟对外接口里写给 LLM 的说明是同一套意思。
 */
final class PanelWords {
    private PanelWords() {}

    /** 能力 ID 去掉本模组的前缀；联动模组的能力保留自己的前缀，免得和本体的同名能力混在一起。 */
    static String ability(String id) {
        String prefix = ModIdentity.MOD_ID + ":";
        return id.startsWith(prefix) ? id.substring(prefix.length()) : id;
    }

    /** 生存需求多快必须处理。 */
    static String urgency(Urgency urgency) {
        if (urgency == null) return "";
        return switch (urgency) {
            case LATER -> "找空当处理";
            case SOON -> "尽快处理";
            case NOW -> "立刻处理";
        };
    }

    /** 目标的处境；结束了就看结果状态。 */
    static String goalState(GoalRunState state, TaskResult result) {
        return switch (state) {
            case RUNNING -> "进行中";
            case AWAITING_ANSWER -> "在等回答";
            case PAUSED -> "已暂停";
            case FINISHED -> result == null ? "已结束" : resultStatus(result.status());
        };
    }

    /** 目标达成得怎样。 */
    static String resultStatus(TaskResult.Status status) {
        return switch (status) {
            case DONE -> "完成";
            case PARTIAL -> "部分失败";
            case FAILED -> "失败";
            case CANCELLED -> "取消了";
        };
    }

    /** 卡在哪一类事情上。 */
    static String problemKind(Problem.Kind kind) {
        return switch (kind) {
            case INVALID_PARAMETER -> "参数不对";
            case NEED_ITEM -> "缺东西";
            case NEED_APPROVAL -> "要 LLM 同意";
            case UNREACHABLE -> "到不了";
            case NOT_FOUND -> "没找到";
            case TARGET_GONE -> "目标对象没了";
            case DANGER -> "危险";
            case REFUSED_BY_GAME -> "游戏拒绝了";
            case WRONG_TIME -> "时间不对";
            case NOT_POSSIBLE_HERE -> "这里做不到";
            case INVENTORY_FULL -> "背包满了";
            case STUCK -> "卡住了";
            case UNSUPPORTED -> "还不支持";
            case INTERNAL_ERROR -> "程序出错";
        };
    }

    /** 任务事件的种类。 */
    static String eventKind(TaskEvent.Kind kind) {
        return switch (kind) {
            case STARTED -> "开始";
            case ASKED -> "提问";
            case PAUSED -> "暂停";
            case RESUMED -> "恢复";
            case STEP_FINISHED -> "一步做完";
            case FINISHED -> "结束";
            case TEMPORARY_TASK_STARTED -> "临时任务开始";
            case TEMPORARY_TASK_FINISHED -> "临时任务结束";
            case NEED_UNHANDLED -> "处理不了";
            case CHARACTER_DIED -> "角色死了";
            case DEATH_RECOVERY_APPLIED -> "死亡恢复";
        };
    }

    /** 没写 purpose 时，用目标对象说明这件事冲着什么去。 */
    static String target(Target target) {
        return switch (target) {
            case null -> "";
            case Target.Here here -> "就在原地";
            case Target.Seen seen -> "看到的 " + seen.id();
            case Target.Landmark landmark -> "地点「" + landmark.name() + "」";
            case Target.Position position -> "(" + position.x() + ", "
                    + (position.y() == null ? "?" : position.y()) + ", " + position.z() + ")";
            case Target.Player player -> "玩家 " + player.name();
            case Target.Direction direction -> "往" + toward(direction.toward()) + " " + direction.distance() + " 格";
            case Target.Previous previous -> previous.step() == null ? "上一步的位置" : "第 " + previous.step() + " 步的位置";
        };
    }

    private static String toward(Target.Toward toward) {
        return switch (toward) {
            case FORWARD -> "前";
            case BACKWARD -> "后";
            case LEFT -> "左";
            case RIGHT -> "右";
            case NORTH -> "北";
            case SOUTH -> "南";
            case EAST -> "东";
            case WEST -> "西";
        };
    }

    /** 时长：一分钟内写秒，一小时内写分秒（秒补成两位），再长写时分。 */
    static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m" + String.format(Locale.ROOT, "%02d", seconds % 60) + "s";
        return (seconds / 3600) + "h" + String.format(Locale.ROOT, "%02d", seconds % 3600 / 60) + "m";
    }

    /** 游戏刻换成时长（每秒 20 刻）。 */
    static String ticks(long ticks) {
        return duration(ticks * 50);
    }
}
