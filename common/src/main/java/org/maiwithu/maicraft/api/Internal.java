// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 标记内部实现，不作为稳定对外兼容接口；标记保存在 class 文件中，不靠它控制游戏执行权限。 */
@Retention(RetentionPolicy.CLASS)
@Target({
        ElementType.TYPE,
        ElementType.METHOD,
        ElementType.CONSTRUCTOR,
        ElementType.FIELD
})
public @interface Internal {}
