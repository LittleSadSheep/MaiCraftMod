// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game;

import net.minecraft.resources.ResourceLocation;

/**
 * MaiCraft 的身份常量。所有层和两个加载器模块都从这里取 mod id，不各自手写字符串，
 * 避免改名或拼写不一致时，资源、网络通道和能力 ID 指向不同的命名空间。
 */
public final class ModIdentity {
    /** mod id，同时也是能力 ID、资源路径和网络通道的命名空间。 */
    public static final String MOD_ID = "maicraft";
    /** 展示给玩家和日志的名字。 */
    public static final String NAME = "MaiCraft";

    private ModIdentity() {}

    /** 在 MaiCraft 命名空间下拼一个资源位置，例如网络通道或能力说明资源文件。 */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
