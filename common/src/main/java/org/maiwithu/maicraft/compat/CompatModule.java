// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 联动入口：一个联动模组的唯一入口。说明自己是哪个模组，把实现的 spi 接口交给联动登记表，
 * 并给模组读写端的每次调用包一层：装的模组和编译时用的版本不一样时，调用会抛 LinkageError，
 * 这里把它转成普通异常 ModApiMismatch、写日志，并停用这个模组的联动；停用后再调用直接抛 ModApiMismatch 并说明原因。
 * 必须包这一层：内核只接 RuntimeException，LinkageError 漏出去会让游戏崩溃。
 *
 * <p>实现 spi 的类拿到 ModApiMismatch 时要如实回答"用不了"——物品来源回答不支持、随身背包回答放不下、
 * 知识来源回答没有条目——不能让任务以内部错误收场：模组装错版本不是程序错误。
 * 物品来源与知识来源这一层由登记表统一包好；随身背包现在还没有调用方，接上调用方时同样要包。
 */
public abstract class CompatModule {
    private static final Logger LOG = LoggerFactory.getLogger(CompatModule.class);

    private final String modId;
    private final String name;
    /** 停用发生在客户端线程，知识来源却在 MCP 的线程上读 active()：volatile 让另一边立刻看到。 */
    private volatile String disabledReason;

    /**
     * @param modId 模组 ID，例如 sophisticatedbackpacks
     * @param name  给日志与说明看的名字，例如"精妙背包"
     */
    protected CompatModule(String modId, String name) {
        this.modId = Objects.requireNonNull(modId, "modId");
        this.name = Objects.requireNonNull(name, "name");
    }

    /** 模组 ID；登记表和日志按它认模组。 */
    public final String modId() {
        return modId;
    }

    /** 给日志与说明看的名字。 */
    public final String name() {
        return name;
    }

    /** 把实现的 spi 接口交给登记表；登记表只在确认装了、版本在范围内、创建成功之后才调它，交的时候带上自己。 */
    public abstract void contribute(CompatRegistry registry);

    /** 这个模组的联动还开着没有：停用过一次就一直停到下次启动。 */
    public final boolean active() {
        return disabledReason == null;
    }

    /** 停用的原因；没停用时为空。 */
    public final Optional<String> disabledReason() {
        return Optional.ofNullable(disabledReason);
    }

    /**
     * 经模组读写端做一次读写。读写端里碰到 LinkageError 就停用这个模组并抛 ModApiMismatch；已经停用的直接抛。
     *
     * @param what 在做什么，例如"认背包物品"；写进日志与异常，让人一眼看出是哪一步对不上
     * @param call 真正碰模组的那一下
     */
    public final <T> T call(String what, Supplier<T> call) {
        if (disabledReason != null) {
            throw new ModApiMismatch(name + "的联动已停用（" + disabledReason + "），不能再" + what);
        }
        try {
            return call.get();
        } catch (LinkageError broken) {
            disabledReason = what + "时模组的类或方法对不上：" + broken;
            LOG.error("{}（{}）{}时模组接口对不上，这个模组的联动停用，装的版本和编译时用的不一样", name, modId, what, broken);
            throw new ModApiMismatch(name + "的联动停用：" + disabledReason, broken);
        }
    }

    /** 同 call，用于没有返回值的读写。 */
    public final void run(String what, Runnable call) {
        call(what, () -> {
            call.run();
            return null;
        });
    }
}
