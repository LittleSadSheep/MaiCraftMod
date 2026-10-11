// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.Objects;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.perception.spi.ModSight;
import org.maiwithu.maicraft.behavior.perception.spi.ModThing;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 物理结构在观察里的样子：领 s#，场景里列名字、动没动、每秒挪几格、几格方块、几个座位、有没有控制件
 * （有座位又有无线红石打字机、舵轮或油门拉杆的算载具，只有方块的就是结构）、角色是不是在上面；
 * 详情给大小、座位所在、控制件有哪些。没读全的写明。
 */
final class SableSight implements ModSight {

    private final SableCompat compat;

    SableSight(SableCompat compat) {
        this.compat = Objects.requireNonNull(compat, "compat");
    }

    @Override public void sight(ClientLevel level, Player viewer, AABB region, Consumer<ModThing> sink) {
        for (SableStructures.Structure structure : compat.structures(level, viewer, region)) {
            StructureGeometry.Scan scan = compat.scan(level, structure);
            JsonObject summary = new JsonObject();
            if (!structure.name().isBlank()) summary.addProperty("name", structure.name());
            summary.addProperty("moving", StructureGeometry.moving(structure));
            summary.addProperty("speed", Math.round(StructureGeometry.velocity(structure).length() * 20 * 10) / 10.0);
            summary.addProperty("blocks", scan.blocks());
            summary.addProperty("seats", scan.seats().size());
            summary.addProperty("vehicle", !scan.seats().isEmpty() && !scan.controls().isEmpty());
            summary.addProperty("riding", structure.carriesPlayer());
            if (!scan.complete()) summary.addProperty("read_complete", false);
            JsonObject detail = new JsonObject();
            BlockPos size = structure.localMax().subtract(structure.localMin()).offset(1, 1, 1);
            detail.addProperty("size", size.getX() + "×" + size.getY() + "×" + size.getZ());
            JsonArray seats = new JsonArray();
            for (BlockPos seat : scan.seats()) {
                Vec3 at = structure.pose().toWorld(Vec3.atCenterOf(seat));
                JsonObject entry = new JsonObject();
                entry.addProperty("x", Math.round(at.x * 10) / 10.0);
                entry.addProperty("y", Math.round(at.y * 10) / 10.0);
                entry.addProperty("z", Math.round(at.z * 10) / 10.0);
                seats.add(entry);
            }
            detail.add("seat_positions", seats);
            JsonArray controls = new JsonArray();
            scan.controls().stream().distinct().forEach(controls::add);
            detail.add("controls", controls);
            Vec3 center = structure.worldBounds().getCenter();
            WorldPosition at = new WorldPosition((int) Math.floor(center.x), (int) Math.floor(center.y),
                    (int) Math.floor(center.z), level.dimension().location().toString());
            sink.accept(new ModThing("structure", ModThing.Identity.STRUCTURE, structure.id(), at, summary, detail));
        }
    }
}
