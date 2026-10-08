// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

/**
 * 出行进展的去处：出行任务逐刻把行程观察交给它，宿主侧拿去更新任务面板或直播解说。
 * 这是接缝不是存储；收到的进展要去哪、留几条，由实现自己决定，中间层不悄悄截断。
 */
public interface TravelProgressListener {

    /** 收到一刻的出行进展。 */
    void onTravelProgress(TravelProgress progress);
}
