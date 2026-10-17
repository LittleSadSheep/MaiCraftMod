// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.worldmemory;

import java.util.List;

/**
 * 读记住区域的只读接缝：给保护判断等使用方一份"角色记住了哪些玩家的地盘"。
 *
 * <p>世界记忆实现它；使用方只认这个接缝，好用自己的替身测试。
 */
public interface RemembersRegions {

    /** 全部记住的区域；同名区域只留一条。 */
    List<RememberedRegion> regions();
}
