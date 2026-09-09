package org.maiwithu.maicraft.core.pathing.moves;

import org.maiwithu.maicraft.core.pathing.moves.movements.MovementAscend;
import org.maiwithu.maicraft.core.pathing.moves.movements.MovementDescend;
import org.maiwithu.maicraft.core.pathing.moves.movements.MovementDiagonal;
import org.maiwithu.maicraft.core.pathing.moves.movements.MovementDownward;
import org.maiwithu.maicraft.core.pathing.moves.movements.MovementFall;
import org.maiwithu.maicraft.core.pathing.moves.movements.MovementParkour;
import org.maiwithu.maicraft.core.pathing.moves.movements.MovementPillar;
import org.maiwithu.maicraft.core.pathing.moves.movements.MovementTraverse;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 保留的一套旧移动动作表：每个方向可以尝试直走、上台阶、下落或跳远，并生成对应执行对象。
 * 当前仓库没有调用这份表的入口；现用 Baritone 搜索器使用 baritone.pathing.movement.Moves，同名但不是本文件。
 */
public enum Moves {

    // 向正下方一格走和原地向上搭高各只有一个方向；接下来按四个水平方向列出普通行走和上台阶。
    DOWNWARD(0, -1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return new MovementDownward(context.player, src, src.below());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementDownward.cost(context, x, y, z);
        }
    },

    PILLAR(0, +1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return new MovementPillar(context.player, src, src.above());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementPillar.cost(context, x, y, z);
        }
    },

    TRAVERSE_NORTH(0, 0, -1) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return new MovementTraverse(context.player, src, src.north());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementTraverse.cost(context, x, y, z, x, z - 1);
        }
    },

    TRAVERSE_SOUTH(0, 0, +1) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return new MovementTraverse(context.player, src, src.south());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementTraverse.cost(context, x, y, z, x, z + 1);
        }
    },

    TRAVERSE_EAST(+1, 0, 0) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return new MovementTraverse(context.player, src, src.east());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementTraverse.cost(context, x, y, z, x + 1, z);
        }
    },

    TRAVERSE_WEST(-1, 0, 0) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return new MovementTraverse(context.player, src, src.west());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementTraverse.cost(context, x, y, z, x - 1, z);
        }
    },

    ASCEND_NORTH(0, +1, -1) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return new MovementAscend(context.player, src, src.offset(0, 1, -1));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementAscend.cost(context, x, y, z, x, z - 1);
        }
    },

    ASCEND_SOUTH(0, +1, +1) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return new MovementAscend(context.player, src, src.offset(0, 1, 1));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementAscend.cost(context, x, y, z, x, z + 1);
        }
    },

    ASCEND_EAST(+1, +1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return new MovementAscend(context.player, src, src.offset(1, 1, 0));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementAscend.cost(context, x, y, z, x + 1, z);
        }
    },

    ASCEND_WEST(-1, +1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return new MovementAscend(context.player, src, src.offset(-1, 1, 0));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementAscend.cost(context, x, y, z, x - 1, z);
        }
    },

    // 向下、斜走和跳远的落点可能随地形变化，不能只把枚举中的偏移量直接加到起点。
    DESCEND_EAST(+1, -1, 0, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return descendOrFall(context, src, this);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDescend.cost(context, x, y, z, x + 1, z, result);
        }
    },

    DESCEND_WEST(-1, -1, 0, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return descendOrFall(context, src, this);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDescend.cost(context, x, y, z, x - 1, z, result);
        }
    },

    DESCEND_NORTH(0, -1, -1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return descendOrFall(context, src, this);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDescend.cost(context, x, y, z, x, z - 1, result);
        }
    },

    DESCEND_SOUTH(0, -1, +1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return descendOrFall(context, src, this);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDescend.cost(context, x, y, z, x, z + 1, result);
        }
    },

    DIAGONAL_NORTHEAST(+1, 0, -1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return diagonal(context, src, this);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDiagonal.cost(context, x, y, z, x + 1, z - 1, result);
        }
    },

    DIAGONAL_NORTHWEST(-1, 0, -1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return diagonal(context, src, this);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDiagonal.cost(context, x, y, z, x - 1, z - 1, result);
        }
    },

    DIAGONAL_SOUTHEAST(+1, 0, +1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return diagonal(context, src, this);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDiagonal.cost(context, x, y, z, x + 1, z + 1, result);
        }
    },

    DIAGONAL_SOUTHWEST(-1, 0, +1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return diagonal(context, src, this);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDiagonal.cost(context, x, y, z, x - 1, z + 1, result);
        }
    },

    PARKOUR_NORTH(0, 0, -4, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return parkour(context, src, Direction.NORTH);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkour.cost(context, x, y, z, Direction.NORTH, result);
        }
    },

    PARKOUR_SOUTH(0, 0, +4, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return parkour(context, src, Direction.SOUTH);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkour.cost(context, x, y, z, Direction.SOUTH, result);
        }
    },

    PARKOUR_EAST(+4, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return parkour(context, src, Direction.EAST);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkour.cost(context, x, y, z, Direction.EAST, result);
        }
    },

    PARKOUR_WEST(-4, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BlockPos src) {
            return parkour(context, src, Direction.WEST);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkour.cost(context, x, y, z, Direction.WEST, result);
        }
    };

    /** 落点是否随地形变化(跑酷距离可变)。 */
    public final boolean dynamicXZ;
    /** 落点高度是否随地形变化(下降可能变坠落、对角可 ±1)。 */
    public final boolean dynamicY;

    public final int xOffset;
    public final int yOffset;
    public final int zOffset;

    Moves(int x, int y, int z, boolean dynamicXZ, boolean dynamicY) {
        this.xOffset = x;
        this.yOffset = y;
        this.zOffset = z;
        this.dynamicXZ = dynamicXZ;
        this.dynamicY = dynamicY;
    }

    Moves(int x, int y, int z) {
        this(x, y, z, false, false);
    }

    /**
     * 从 src 构造可执行的移动实例(路径装配期用);
     * 动态成员在成本不可行时返回 null。
     */
    public abstract Movement apply0(CalculationContext context, BlockPos src);

    /**
     * 计算从 (x,y,z) 走本方向的落点与成本。静态成员直接按偏移填
     * 结果;动态成员覆写并由成本函数写实际落点。
     */
    public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
        if (dynamicXZ || dynamicY) {
            throw new UnsupportedOperationException("动态偏移的移动必须覆写 apply");
        }
        result.x = x + xOffset;
        result.y = y + yOffset;
        result.z = z + zOffset;
        result.cost = cost(context, x, y, z);
    }

    public double cost(CalculationContext context, int x, int y, int z) {
        throw new UnsupportedOperationException("移动必须覆写 cost 或 apply");
    }

    /** 下降方向的分派:落点恰低一格是下降,更低是坠落,不可行为 null。 */
    private static Movement descendOrFall(CalculationContext context, BlockPos src, Moves move) {
        MutableMoveResult res = new MutableMoveResult();
        move.apply(context, src.getX(), src.getY(), src.getZ(), res);
        if (res.cost >= ActionCosts.COST_INF) {
            return null;
        }
        BlockPos dest = new BlockPos(res.x, res.y, res.z);
        if (res.y == src.getY() - 1) {
            return new MovementDescend(context.player, src, dest);
        }
        return new MovementFall(context.player, src, dest);
    }

    private static Movement diagonal(CalculationContext context, BlockPos src, Moves move) {
        MutableMoveResult res = new MutableMoveResult();
        move.apply(context, src.getX(), src.getY(), src.getZ(), res);
        if (res.cost >= ActionCosts.COST_INF) {
            return null;
        }
        return new MovementDiagonal(context.player, src, new BlockPos(res.x, res.y, res.z));
    }

    private static Movement parkour(CalculationContext context, BlockPos src, Direction direction) {
        MutableMoveResult res = new MutableMoveResult();
        MovementParkour.cost(context, src.getX(), src.getY(), src.getZ(), direction, res);
        if (res.cost >= ActionCosts.COST_INF) {
            return null;
        }
        return new MovementParkour(context.player, src, new BlockPos(res.x, res.y, res.z));
    }
}
