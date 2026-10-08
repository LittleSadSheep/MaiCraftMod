// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel;

import java.util.Optional;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 读观察编号的只读接缝：按观察编号（e12、f3、b5）查"当时看到的东西在哪"。
 * 感知模型建起场景表后由它实现；在接上之前，出行对 seen 目标以"东西不在了"如实收场。
 */
public interface ReadsSeenTargets {

    /** 查一个观察编号对应的位置；编号已经失效（走远、被拆、会话过期）返回空。 */
    Optional<WorldPosition> positionOf(String observationId);
}
