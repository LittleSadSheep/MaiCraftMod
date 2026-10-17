// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import java.util.Optional;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 读记过地点的只读接缝：按名字查"记过的地点在哪"，保护判断用它定位额外保护的地标。
 *
 * <p>记住地点的写入口在 {@code RemembersPlaces}，世界记忆两边都实现；这里只要读，
 * 好让保护判断用自己的替身测试。
 */
public interface ReadsRememberedPlaces {

    /** 查一个按名字记的地点；没记过返回空。 */
    Optional<WorldPosition> place(String name);
}
