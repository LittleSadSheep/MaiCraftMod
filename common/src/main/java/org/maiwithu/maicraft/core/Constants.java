package org.maiwithu.maicraft.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Identity constants shared by the single MaiCraft mod runtime. */
// 保存 Mod 的固定标识、显示名称与共用日志对象，避免各处写出不同名字。
public final class Constants {

    public static final String MOD_ID = "maicraft";
    public static final String MOD_NAME = "MaiCraft";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    private Constants() {}
}
