// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.create;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.schematics.cannon.ConfigureSchematicannonPacket;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.client.ClientSchematicLoader;
import com.simibubi.create.content.schematics.packet.SchematicSyncPacket;
import com.simibubi.create.content.schematics.table.SchematicTableBlockEntity;
import com.simibubi.create.foundation.utility.CreatePaths;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import org.maiwithu.maicraft.compat.create.CreateSchematics;

/**
 * 读写机械动力蓝图桌与蓝图加农炮的读写端：文件检查照 ClientSchematicLoader 的规矩（schematics 目录下、gzip、
 * 不超过服务器的 maxTotalSchematicSize），开传调它的 startNewUpload，摆蓝图发 SchematicSyncPacket，
 * 加农炮按钮发 ConfigureSchematicannonPacket，读数读方块实体同步过来的公开字段。只翻译，不判断。
 */
public final class CreateSchematicReads implements CreateSchematics {

    @Override public Optional<String> uploadProblem(String file) {
        Path base = CreatePaths.SCHEMATICS_DIR.normalize();
        Path path = base.resolve(file).normalize();
        if (!path.startsWith(base) || !file.endsWith(".nbt")) return Optional.of("蓝图文件要写 schematics 目录下的 .nbt 文件名");
        if (!Files.isRegularFile(path)) return Optional.of("schematics 目录下没有 " + file);
        if (!ClientSchematicLoader.isGZIPEncoded(path.toFile())) {
            return Optional.of(file + " 不是压缩过的结构文件：蓝图桌只收 gzip 压缩的 .nbt");
        }
        // 单人世界不限大小；连服务器时照 Create 的上限（KB）。
        if (!Minecraft.getInstance().hasSingleplayerServer()) {
            try {
                long limit = AllConfigs.server().schematics.maxTotalSchematicSize.get() * 1000L;
                if (Files.size(path) > limit) return Optional.of(file + " 超过服务器允许的蓝图大小 " + limit / 1000 + " KB");
            } catch (Exception unreadable) {
                // 读不到大小或配置时不拦，交给服务器判
            }
        }
        return Optional.empty();
    }

    @Override public void startUpload(String file) {
        CreateClient.SCHEMATIC_SENDER.startNewUpload(file);
    }

    @Override public Optional<String> schematicFile(ItemStack stack) {
        if (!schematic(stack)) return Optional.empty();
        return Optional.ofNullable(stack.get(AllDataComponents.SCHEMATIC_FILE));
    }

    @Override public Optional<Placement> placement(ItemStack stack) {
        if (!schematic(stack) || !Boolean.TRUE.equals(stack.get(AllDataComponents.SCHEMATIC_DEPLOYED))) {
            return Optional.empty();
        }
        BlockPos anchor = stack.get(AllDataComponents.SCHEMATIC_ANCHOR);
        Rotation rotation = stack.getOrDefault(AllDataComponents.SCHEMATIC_ROTATION, Rotation.NONE);
        return anchor == null ? Optional.empty() : Optional.of(new Placement(anchor, degrees(rotation)));
    }

    @Override public void deploy(int slot, BlockPos anchor, int rotation) {
        CatnipServices.NETWORK.sendToServer(new SchematicSyncPacket(slot, true, anchor, rotationOf(rotation), Mirror.NONE));
    }

    @Override public Optional<CannonReading> cannon(ClientLevel level, BlockPos at) {
        if (!(level.getBlockEntity(at) instanceof SchematicannonBlockEntity cannon)) return Optional.empty();
        Optional<String> missing = cannon.missingItem == null || cannon.missingItem.isEmpty() ? Optional.empty()
                : Optional.of(BuiltInRegistries.ITEM.getKey(cannon.missingItem.getItem()).toString());
        return Optional.of(new CannonReading(cannon.state == SchematicannonBlockEntity.State.RUNNING,
                cannon.state == SchematicannonBlockEntity.State.PAUSED,
                cannon.statusMsg == null ? "" : cannon.statusMsg, cannon.blocksPlaced, cannon.blocksToPlace,
                missing, cannon.remainingFuel));
    }

    @Override public void pressCannon(String button) {
        ConfigureSchematicannonPacket.Option option = switch (button) {
            case "play" -> ConfigureSchematicannonPacket.Option.PLAY;
            case "pause" -> ConfigureSchematicannonPacket.Option.PAUSE;
            default -> ConfigureSchematicannonPacket.Option.STOP;
        };
        CatnipServices.NETWORK.sendToServer(new ConfigureSchematicannonPacket(option, true));
    }

    @Override public Optional<Float> uploading(ClientLevel level, BlockPos at) {
        if (!(level.getBlockEntity(at) instanceof SchematicTableBlockEntity table)) return Optional.empty();
        return table.isUploading ? Optional.of(table.uploadingProgress) : Optional.empty();
    }

    // 写好的蓝图按注册 ID 认（Create 的物品表在 Registrate 里，读写端不碰它）。
    private static boolean schematic(ItemStack stack) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals("create:schematic");
    }

    private static int degrees(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> 90;
            case CLOCKWISE_180 -> 180;
            case COUNTERCLOCKWISE_90 -> 270;
            default -> 0;
        };
    }

    private static Rotation rotationOf(int degrees) {
        return switch (degrees) {
            case 90 -> Rotation.CLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }
}
