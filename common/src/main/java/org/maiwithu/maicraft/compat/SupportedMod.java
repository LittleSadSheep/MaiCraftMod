// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.maiwithu.maicraft.game.loader.LoaderEnvironment;

/**
 * 支持的模组，也就是联动清单的一行：哪个模组、验证过的版本范围、怎么创建它的联动入口。
 * 客户端清单的一行创建客户端的联动入口（CompatModule），服务端清单的一行创建服务端的联动入口；
 * 装没装、版本对不对的检查两边一样，写在这里。
 *
 * <p>创建写成 Supplier，是为了把引用模组类的代码推迟到确认装了、版本在范围内之后再加载：
 * 清单类自己的字段、方法签名、静态初始化里都不能出现模组的类，也不能出现直接引用模组类的读写端类型，
 * 只在 Supplier 的 lambda 体里 new。没装那个模组时，这些类一个都不会被加载。
 *
 * @param modId    模组 ID，例如 sophisticatedbackpacks；按它查装没装、装的什么版本
 * @param name     给日志与说明看的名字，例如"精妙背包"
 * @param verified 实测过的版本范围；装的版本不在范围内就不登记
 * @param create   创建联动入口（连同它的读写端）；只在前两项都通过之后调用一次
 * @param <M>      联动入口的种类：客户端的或服务端的
 */
public record SupportedMod<M>(String modId, String name, VerifiedVersions verified, Supplier<M> create) {

    public SupportedMod {
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(verified, "verified");
        Objects.requireNonNull(create, "create");
        if (modId.isBlank() || name.isBlank()) {
            throw new IllegalArgumentException("联动清单的一行要写模组 ID 和名字：" + modId + " / " + name);
        }
    }

    /**
     * 这一行能不能登记：没装、装的版本不在验证过的范围内时给一句结论（写进日志），能登记给空。
     * 只看加载器报的事实，不碰模组的类。
     */
    public Optional<String> skipReason(LoaderEnvironment loader) {
        if (!loader.isModLoaded(modId)) return Optional.of("没装，跳过");
        String version = installedVersion(loader);
        if (!verified.contains(version)) {
            return Optional.of("装的是 " + (version.isBlank() ? "不明版本" : version) + "，验证过的范围是 "
                    + verified.describe() + "，不登记");
        }
        return Optional.empty();
    }

    /** 装的版本；加载器说不出时为空字符串。 */
    public String installedVersion(LoaderEnvironment loader) {
        return loader.modVersion(modId).orElse("");
    }
}
