// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 一回饿：从饿起来（或伤重想吃点回血）到不再需要吃为止。饥饿需求每次插进来都是新的吃饭任务，
 * 跨任务要记的两件事放在这里：这一回有没有跟 LLM 说过没吃上，弄吃的哪儿都没弄到之后歇到什么时候。
 *
 * <p>同一回饿只说一次：哪儿都弄不到吃的时，每次再试都是同一段上千字的说明，二十秒里刷三遍只会挤掉别的事实。
 * 处境变得更急（饿到不能疾跑、饿到掉血）是新的事实，照样再说一次。
 *
 * <p>弄不到就歇一阵：附近、箱子里、网络里都没有，几秒后再问一遍多半还是没有，只会把手上的活停停走走。
 * 歇一分钟再找；处境更急了马上再试。只在客户端线程读写。
 */
final class HungerBout {

    /** 弄吃的哪儿都没弄到之后歇多久再找（刻）：一分钟。 */
    static final long REST_AFTER_NOTHING_FOUND_TICKS = 20L * 60;

    /** 这一回已经把没吃上告诉 LLM 时有多急；还没说过为 null。 */
    private Urgency toldAt;
    /** 弄吃的哪儿都没弄到后歇到哪一刻；没在歇为 -1。 */
    private long restUntil = -1;
    /** 歇下时有多急：处境比这更急就不再歇。 */
    private Urgency restAt;

    /** 不饿了（吃饱、回过血线、重生）：这一回结束，下回饿了重新说、重新找。 */
    void over() {
        toldAt = null;
        restUntil = -1;
        restAt = null;
    }

    /**
     * 要不要把这次没吃上告诉 LLM：这一回还没说过，或处境比上次说时更急，才说并记下；否则不再重复。
     *
     * @param urgency 此刻的饥饿急迫程度；不明时按最缓算
     */
    boolean tell(Urgency urgency) {
        Urgency now = urgency == null ? Urgency.LATER : urgency;
        if (toldAt != null && now.compareTo(toldAt) <= 0) return false;
        toldAt = now;
        return true;
    }

    /** 弄吃的问遍各处都没弄到：从这一刻起歇一阵再找，记下当时有多急。 */
    void restFromFetching(long gameTick, Urgency urgency) {
        restUntil = gameTick + REST_AFTER_NOTHING_FOUND_TICKS;
        restAt = urgency == null ? Urgency.LATER : urgency;
    }

    /** 此刻还在歇：没到时候、处境也没更急。到了时候或更急了就不再歇。 */
    boolean resting(long gameTick, Urgency urgency) {
        if (restUntil < 0) return false;
        if (gameTick >= restUntil || urgency != null && urgency.compareTo(restAt) > 0) {
            restUntil = -1;
            restAt = null;
            return false;
        }
        return true;
    }
}
