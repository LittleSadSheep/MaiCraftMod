// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

/**
 * 模组接口对不上：运行中调用模组时出现 LinkageError（装的模组和编译时用的版本不一样，类没了、方法改了名）
 * 后转成的普通异常。出现后这个模组的联动整体停用；停用后再调用也抛它，并说明为什么停用。
 *
 * <p>它是 RuntimeException：内核的子任务运行与控制循环只接 RuntimeException，LinkageError 漏出去会让游戏崩溃。
 */
public final class ModApiMismatch extends RuntimeException {

    public ModApiMismatch(String message) {
        super(message);
    }

    public ModApiMismatch(String message, Throwable cause) {
        super(message, cause);
    }
}
