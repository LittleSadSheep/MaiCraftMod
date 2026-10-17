// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端的联动入口：一个模组在服务端要读什么，经这里登记只读操作；碰模组读写端的每一下都包一层，
 * 装的模组和编译时用的版本不一样时（LinkageError）转成 ServerModApiMismatch、写日志，并停用这个模组的服务端联动。
 *
 * <p>和客户端的联动入口 compat.CompatModule 做的是同一件事。分层不许服务端依赖客户端联动的包，
 * 所以这几十行重复一份；改一边时另一边一起看。服务端只回答网络级的汇总、机器的权威读数，不替角色动手。
 */
public abstract class ServerCompatModule {
    private static final Logger LOG = LoggerFactory.getLogger(ServerCompatModule.class);

    private final String modId;
    private final String name;
    /** 停用发生在服务端主线程，读的也是主线程；volatile 让登记表在别处查状态时也看得到。 */
    private volatile String disabledReason;

    /**
     * @param modId 模组 ID，例如 ae2
     * @param name  给日志与说明看的名字，例如"应用能源2"
     */
    protected ServerCompatModule(String modId, String name) {
        this.modId = Objects.requireNonNull(modId, "modId");
        this.name = Objects.requireNonNull(name, "name");
    }

    /** 模组 ID。 */
    public final String modId() {
        return modId;
    }

    /** 给日志与说明看的名字。 */
    public final String name() {
        return name;
    }

    /** 把要回答的只读操作交给服务端登记表；确认装了、版本在范围内、创建成功之后才调它。 */
    public abstract void contribute(ServerCompatRegistry registry);

    /** 这个模组的服务端联动还开着没有：停用过一次就一直停到服务器重开。 */
    public final boolean active() {
        return disabledReason == null;
    }

    /** 停用的原因；没停用时为空。 */
    public final Optional<String> disabledReason() {
        return Optional.ofNullable(disabledReason);
    }

    /**
     * 经模组读写端做一次读。读写端里碰到 LinkageError 就停用并抛 ServerModApiMismatch；已经停用的直接抛。
     *
     * @param what 在做什么，例如"读 ME 网络摘要"；写进日志与异常
     * @param call 真正碰模组的那一下
     */
    public final <T> T call(String what, Supplier<T> call) {
        if (disabledReason != null) {
            throw new ServerModApiMismatch(name + "的服务端联动已停用（" + disabledReason + "），不能再" + what);
        }
        try {
            return call.get();
        } catch (LinkageError broken) {
            disabledReason = what + "时模组的类或方法对不上：" + broken;
            LOG.error("{}（{}）{}时模组接口对不上，这个模组的服务端联动停用，装的版本和编译时用的不一样",
                    name, modId, what, broken);
            throw new ServerModApiMismatch(name + "的服务端联动停用：" + disabledReason, broken);
        }
    }
}
