// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Set;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/** Cable centres and face plates expose multipart layout without inventing block-entity models. */
final class PreviewPartGeometry {
    private PreviewPartGeometry() {}

    static void emit(PreviewPart part, BlockPos origin, Set<BlockPos> centres,
                     VertexConsumer fill, VertexConsumer lines) {
        AABB box = localBox(part.side());
        BlockPos offset = part.position().subtract(origin);
        emitBox(box.move(offset.getX(), offset.getY(), offset.getZ()), fill, lines);
        if (!part.side().equals("center")) return;
        for (Direction direction : Direction.values()) {
            if (!centres.contains(part.position().relative(direction))) continue;
            AABB connector = switch (direction) {
                case DOWN -> new AABB(.375, 0, .375, .625, .5, .625);
                case UP -> new AABB(.375, .5, .375, .625, 1, .625);
                case NORTH -> new AABB(.375, .375, 0, .625, .625, .5);
                case SOUTH -> new AABB(.375, .375, .5, .625, .625, 1);
                case WEST -> new AABB(0, .375, .375, .5, .625, .625);
                case EAST -> new AABB(.5, .375, .375, 1, .625, .625);
            };
            emitBox(connector.move(offset.getX(), offset.getY(), offset.getZ()), fill, lines);
        }
    }

    static AABB localBox(String side) {
        return switch (side) {
            case "down" -> new AABB(.2, 0, .2, .8, .15, .8);
            case "up" -> new AABB(.2, .85, .2, .8, 1, .8);
            case "north" -> new AABB(.2, .2, 0, .8, .8, .15);
            case "south" -> new AABB(.2, .2, .85, .8, .8, 1);
            case "west" -> new AABB(0, .2, .2, .15, .8, .8);
            case "east" -> new AABB(.85, .2, .2, 1, .8, .8);
            default -> new AABB(.375, .375, .375, .625, .625, .625);
        };
    }

    private static void emitBox(AABB box, VertexConsumer fill, VertexConsumer lines) {
        float x = (float) box.minX, y = (float) box.minY, z = (float) box.minZ;
        float a = (float) box.maxX, b = (float) box.maxY, c = (float) box.maxZ;
        float[][] corners = {{x,y,z},{a,y,z},{a,b,z},{x,b,z},{x,y,c},{a,y,c},{a,b,c},{x,b,c}};
        int[][] faces = {{0,1,2,3},{5,4,7,6},{4,0,3,7},{1,5,6,2},{4,5,1,0},{3,2,6,7}};
        for (int[] face : faces) for (int index : face) {
            float[] point = corners[index];
            fill.addVertex(point[0], point[1], point[2]).setColor(.35f, .75f, 1, .4f);
        }
        LevelRenderer.renderLineBox(new PoseStack(), lines, box, .35f, .75f, 1, .9f);
    }
}
