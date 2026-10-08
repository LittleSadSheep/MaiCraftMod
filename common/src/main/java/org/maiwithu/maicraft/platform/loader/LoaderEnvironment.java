// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.platform.loader;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 加载器提供给公共代码的环境事实：是哪个加载器、装了哪些模组、游戏与配置目录在哪。
 * 公共代码只通过它了解加载器，不直接调用 FabricLoader 或 ModList，这样两个加载器共用同一份业务代码。
 * 实例由加载器入口创建，经启动入口一路传给需要它的服务，不提供全局取用的单例。
 */
public interface LoaderEnvironment {

    /** 加载器名字："fabric" 或 "neoforge"，只用于日志和知识库标注安装环境。 */
    String loaderName();

    /** 某个模组是否已安装；联动模块据此决定是否注册，能力据此判断是否可用。 */
    boolean isModLoaded(String modId);

    /** 已安装模组的版本号；未安装时为空。引用联动模组源码结论前，要和这里的实际版本核对。 */
    Optional<String> modVersion(String modId);

    /** 游戏实例目录（.minecraft 或开发环境的 runs 目录）。 */
    Path gameDirectory();

    /** 配置目录；MaiCraft 的数据与配置放在其下的 maicraft 子目录。 */
    Path configDirectory();

    /** 是否运行在开发环境（IDE 或 Gradle 启动项），用于打开只在开发时需要的诊断。 */
    boolean isDevelopment();
}
