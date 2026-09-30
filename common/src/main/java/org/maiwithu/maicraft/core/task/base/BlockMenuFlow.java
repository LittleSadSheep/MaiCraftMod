// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.base;

import java.util.Map;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.core.FailureType;
import org.maiwithu.maicraft.task.Task;
import org.maiwithu.maicraft.task.TaskState;

/**
 * 方块菜单流的统一操作面：伴生任务只驱动流程与读取观察数据，不关心是附魔台还是切石机的实现。
 * 生命周期约定：tick 推进到终态；外部前提失效时基类先 abort 再继续 tick 到 FAILED；
 * 暂停走 stop（保留现场），任务终局走 cleanup（安全归还或如实保留）。
 */
public interface BlockMenuFlow {
    /** 推进菜单流一步；进行中返回 RUNNING，结束返回终态。返回 FAILED 前必须已记录失败码与类型。 */
    TaskState tick(LocalPlayerContext context);

    /** 外部前提失效（如目标方块消失）时请求安全终止：只记第一个失败码，不覆盖既有失败；随后的 tick 决定归还余料还是保留现场。 */
    void abort(String code, FailureType type);

    /** 最近一次失败的语义失败码，未失败时返回兜底码；基类在 tick 返回 FAILED 后读取并写入回执。 */
    String failure();

    /** 是否已记录失败；基类用它保证 abort 只通知一次，不反复打断进行中的安全归还。 */
    boolean hasFailure();

    /** 最近一次失败的失败类型，与 {@link #failure()} 同源同窗。 */
    FailureType failureType();

    /** 暂停或被替换时即刻让位：只暂停飞行中的子操作，不推进收尾、不关闭界面——收尾是 cleanup 的职责。 */
    void stop(Task.StopReason reason);

    /**
     * 任务终局清理：结算未完成的子操作；仅在菜单仍是本任务的、内容归属可判定、且无待核验的
     * 按钮回执时，才请原生归还并关闭界面；否则如实保留现场并记入 cleanup_status，供人工核验。
     */
    void cleanup();

    /** 只读观察数据（phase、outcome_uncertain、item_return_verified、gui_closed、cleanup_status 等），被并入 progress() 与回执；调用不得执行任何游戏操作。 */
    Map<String, Object> data();
}
