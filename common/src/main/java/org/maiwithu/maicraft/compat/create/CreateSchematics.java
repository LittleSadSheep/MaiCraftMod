// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.create;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * 机械动力蓝图桌与蓝图加农炮的模组读写接缝：蓝图文件能不能传、照蓝图桌的上传按钮开传、
 * 一件蓝图写的是哪份文件与摆在哪、照蓝图工具摆好时那样把蓝图摆到一处、加农炮的读数与界面上的按钮。
 * 只用 Minecraft 与 Java 的类型；读写端在 NeoForge 模块里，测试里用替身。
 */
public interface CreateSchematics {

    /** 这份蓝图文件（游戏目录 schematics 下）能不能拿去上传：不在、不是压缩过的 .nbt、超过服务器上限时给原因。 */
    Optional<String> uploadProblem(String file);

    /** 开传：蓝图桌界面开着、输入格有空白蓝图时，照界面上的上传按钮那样开始，之后客户端每半秒传一段。 */
    void startUpload(String file);

    /** 这件是不是写好的蓝图，写的是哪份文件；不是为空。 */
    Optional<String> schematicFile(ItemStack stack);

    /** 这件蓝图摆在哪：摆好了给原点与转角；没摆为空。 */
    Optional<Placement> placement(ItemStack stack);

    /** 把背包第 slot 格（0~35）的蓝图摆到 anchor、转 rotation 度：蓝图工具摆好时发给服务器的那一包。 */
    void deploy(int slot, BlockPos anchor, int rotation);

    /** 蓝图加农炮的读数；不是加农炮为空。 */
    Optional<CannonReading> cannon(ClientLevel level, BlockPos at);

    /** 按加农炮界面上的一个按钮：play 开动、pause 暂停、stop 停止。界面要开着。 */
    void pressCannon(String button);

    /** 蓝图桌正在上传时传了多少（0~1）；没在上传或不是蓝图桌为空。 */
    Optional<Float> uploading(ClientLevel level, BlockPos at);

    /** 蓝图摆在哪：原点与转角（0、90、180、270）。 */
    record Placement(BlockPos anchor, int rotation) {
        public Placement {
            anchor = Objects.requireNonNull(anchor, "anchor").immutable();
        }
    }

    /**
     * 蓝图加农炮的读数。
     *
     * @param running  开着（在放或在等料）
     * @param paused   暂停着
     * @param status   加农炮界面上那一行状态的原名：finished、missingBlock、noGunpowder、targetOutsideRange……
     * @param placed   这份蓝图放了几格
     * @param toPlace  这份蓝图一共要放几格（读不到为 0）
     * @param missing  卡在缺哪样东西；不缺为空
     * @param fuel     剩下的火药能打几发
     */
    record CannonReading(boolean running, boolean paused, String status, int placed, int toPlace,
                         Optional<String> missing, int fuel) {
        public CannonReading {
            Objects.requireNonNull(status, "status");
            missing = missing == null ? Optional.empty() : missing;
        }
    }
}
