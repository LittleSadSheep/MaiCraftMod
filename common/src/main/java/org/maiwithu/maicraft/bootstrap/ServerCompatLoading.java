// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.compat.SupportedMod;
import org.maiwithu.maicraft.game.loader.LoaderEnvironment;
import org.maiwithu.maicraft.server.compat.ServerCompatModule;
import org.maiwithu.maicraft.server.compat.ServerCompatRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端联动清单的逐行检查：规矩和客户端的联动登记表一样——没装的跳过，版本不在验证过的范围内不登记，
 * 创建或交接出错不登记（它登记的操作一个都不留），每行写一行日志，一个模组出问题不影响别的。
 * 放在启动这一层，是因为清单的一行（SupportedMod）在客户端联动的包里，服务端不许依赖它。
 */
final class ServerCompatLoading {
    private static final Logger LOG = LoggerFactory.getLogger(ServerCompatLoading.class);

    private ServerCompatLoading() {}

    /** 逐行检查服务端清单，通过的入口交给服务端联动登记表；返回每行的结论，和日志里写的一样。 */
    static List<String> load(List<SupportedMod<ServerCompatModule>> catalog, LoaderEnvironment loader,
            ServerCompatRegistry registry) {
        Objects.requireNonNull(loader, "loader");
        return catalog.stream().map(mod -> check(mod, loader, registry)).toList();
    }

    private static String check(SupportedMod<ServerCompatModule> mod, LoaderEnvironment loader,
            ServerCompatRegistry registry) {
        Optional<String> skip = mod.skipReason(loader);
        // 创建与交接可能抛 LinkageError（读写端一碰模组类就对不上），也一并当作这一行出错，不让它炸掉服务器启动。
        String conclusion;
        if (skip.isPresent()) {
            conclusion = skip.get();
        } else {
            try {
                registry.add(Objects.requireNonNull(mod.create().get(), "创建返回了 null"));
                conclusion = "已登记，版本 " + mod.installedVersion(loader);
            } catch (RuntimeException | LinkageError failure) {
                conclusion = "创建时出错，不登记：" + failure;
            }
        }
        String line = mod.name() + "（" + mod.modId() + "）：" + conclusion;
        LOG.info("服务端联动 {}", line);
        return line;
    }
}
