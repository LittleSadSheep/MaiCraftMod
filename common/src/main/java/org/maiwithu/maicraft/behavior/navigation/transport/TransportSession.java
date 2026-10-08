package org.maiwithu.maicraft.behavior.navigation.transport;

import java.util.Map;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.DefaultBodyControlPort;
import org.maiwithu.maicraft.behavior.navigation.debug.NavigationPathSnapshot;

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
    /** 受伤要求停止时，能控制空中移动的交通先寻找避开威胁的出口；其他交通沿用安全停止流程。 */
    default void requestDamageStop() { requestStop(); }

    /** 人工接管或玩家消失时直接释放旧输入，不再为旧身体做更多游戏操作。 */
    void abandon();

    /** 此刻是否能安全把玩家交给另一任务，例如空中尚未落地时通常不行。 */
    boolean safeToInterrupt();

    /** 交通会话是否仍在等待动作或维持运行；补时另取下方已确认的新进展时刻。 */
    boolean livenessActive();
    /** 只有已确认的操作或新计算事实才更新时间；单纯保持会话打开不能续期。 */
    default long lastVerifiedProgressTick() { return Long.MIN_VALUE; }

    String phase();

    /** 当前实测状态和限制；估算不得声称旅程已完成。 */
    Map<String, Object> diagnostics();

    /** 供可选开发者覆盖层显示的已选路线；null 表示当前没有活动路线。 */
    default NavigationPathSnapshot debugPath() { return null; }

    /** 普通界面会暂停交通；实现可为自己正在处理的物品栏操作开放例外。 */
    default boolean allowsCurrentScreen(LocalPlayerContext context) {
        return DefaultBodyControlPort.permitsWorldMovement(context.minecraft().screen);
    }
}
