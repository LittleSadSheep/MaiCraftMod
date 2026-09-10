// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.create;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 保存经过调查的真实端点和每一格的放置位置、支撑面与站位；路线标识用于辨认这份计划，实际世界状态仍需另行复查。
 */
record CreateMechanicalPlan(
        KineticEndpoint source,
        KineticEndpoint destinationMachine,
        BlockPos receiver,
        List<RouteCell> cells,
        String geometry,
        String routeHash,
        boolean progressive,
        float sourceSpeedAtSurvey,
        boolean destinationPoweredAtSurvey) {

    record KineticEndpoint(
            BlockPos position, BlockState state, Direction shaftFace, float speed, boolean network) {
        KineticEndpoint {
            position = position.immutable();
        }
    }

    record RouteCell(BlockPos position, BlockPos support, Direction supportFace, BlockPos stand) {
        RouteCell {
            position = position.immutable();
            support = support.immutable();
            stand = stand.immutable();
        }
    }

    record Result(CreateMechanicalPlan plan, String failureCode, Map<String, Object> facts) {
        Result {
            facts = Map.copyOf(facts);
        }

        static Result ok(CreateMechanicalPlan plan, Map<String, Object> facts) {
            return new Result(plan, null, facts);
        }

        static Result fail(String code, Map<String, Object> facts) {
            return new Result(null, code, facts);
        }
    }

    CreateMechanicalPlan {
        // 这句原值自赋值不改变记录内容，当前没有提供额外校验。
        source = source;
        receiver = receiver.immutable();
        cells = List.copyOf(cells);
    }

    BlockPos destinationPosition() {
        return destinationMachine == null ? receiver : destinationMachine.position();
    }

    // 把已确认格数与路线顺序一起编码；这个标识不能证明那些方块现在仍是原状态。
    String prefixHash(int confirmedCells) {
        int safe = Math.max(0, Math.min(confirmedCells, cells.size()));
        StringBuilder value = new StringBuilder(routeHash).append('|').append(safe);
        for (int i = 0; i < safe; i++) {
            BlockPos p = cells.get(i).position();
            value.append('|').append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ());
        }
        return sha256(value.toString());
    }

    static String hashRoute(List<BlockPos> positions, String geometry) {
        StringBuilder value = new StringBuilder(geometry);
        for (BlockPos p : positions) {
            value.append('|').append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ());
        }
        return sha256(value.toString());
    }

    static String geometry(List<BlockPos> positions) {
        if (positions.isEmpty()) return "empty";
        List<String> runs = new ArrayList<>();
        BlockPos runStart = positions.get(0);
        Direction prior = null;
        for (int i = 1; i < positions.size(); i++) {
            Direction now = between(positions.get(i - 1), positions.get(i));
            if (prior != null && now != prior) {
                runs.add(runStart.toShortString() + "->" + positions.get(i - 1).toShortString());
                runStart = positions.get(i - 1);
            }
            prior = now;
        }
        runs.add(runStart.toShortString() + "->" + positions.get(positions.size() - 1).toShortString());
        return String.join(";", runs);
    }

    static Direction between(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        for (Direction direction : Direction.values()) {
            if (direction.getStepX() == dx && direction.getStepY() == dy
                    && direction.getStepZ() == dz) return direction;
        }
        return null;
    }

    static Map<String, Object> facts() {
        return new LinkedHashMap<>();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
