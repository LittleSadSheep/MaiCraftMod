package org.maiwithu.maicraft.core.pathing.transport;

import java.util.Map;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;

/** 一段实际交通动作的共同接口，例如飞到落点或乘梯到某层；总导航负责把这些段接成完整旅行。 */
public interface TransportSession {
    enum State { RUNNING, SUCCEEDED, FAILED }

    record Result(State state, String code, String detail, boolean effectsStarted, boolean uncertain) {
        public boolean terminal() { return state != State.RUNNING; }
        public static Result running(String phase) {
            return new Result(State.RUNNING, phase, "", false, false);
        }
        public static Result success(String detail) {
            return new Result(State.SUCCEEDED, "arrived", detail, true, false);
        }
        public static Result failed(String code, String detail, boolean effectsStarted, boolean uncertain) {
            return new Result(State.FAILED, code, detail, effectsStarted, uncertain);
        }
    }

    /** 每个游戏刻做一步，返回仍在进行、成功或失败；具体操作必须通过当前玩家的动作接口。 */
    Result tick(LocalPlayerContext context);

    /** 请求安全停止；提出请求后可能还要继续几刻落地或出梯，不能立即丢弃控制。 */
    void requestStop();

    /** 人工接管或玩家消失时直接释放旧输入，不再为旧身体做更多游戏操作。 */
    void abandon();

    /** 此刻是否能安全把玩家交给另一任务，例如空中尚未落地时通常不行。 */
    boolean safeToInterrupt();

    /** 是否仍有应继续等待的交通进展，供上层决定是否延长执行时间。 */
    boolean livenessActive();

    String phase();

    /** Current measured state and limitations; an estimate must not claim a completed journey. */
    Map<String, Object> diagnostics();

    /** Selected route for the optional developer overlay; null means no route is active. */
    default org.maiwithu.maicraft.core.pathing.debug.NavigationPathSnapshot debugPath() { return null; }

    /** 普通界面会暂停交通；实现可为自己正在处理的物品栏操作开放例外。 */
    default boolean allowsCurrentScreen(LocalPlayerContext context) {
        return org.maiwithu.maicraft.client.actor.DefaultBodyControlPort.permitsWorldMovement(context.minecraft().screen);
    }
}
