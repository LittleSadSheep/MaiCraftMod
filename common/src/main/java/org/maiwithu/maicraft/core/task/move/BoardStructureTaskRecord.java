package org.maiwithu.maicraft.core.task.move;

import java.util.UUID;
import org.maiwithu.maicraft.task.TaskFactory;
import org.maiwithu.maicraft.task.TaskRecord;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.List;
import org.maiwithu.maicraft.core.task.physics.StructureSeatTask;

/**
 * 记录要登上的移动结构编号。结构会移动，所以成功表示在它的甲板站稳，不保存一个固定世界坐标冒充登船结果。
 */
public final class BoardStructureTaskRecord extends TaskRecord {
    // 明确指定座位时执行原生入座；普通登艇仍以真实甲板支撑为完成条件。
    static { TaskFactory.register(BoardStructureTaskRecord.class, (player,record)->record.seatOffset==null
            ?new BoardStructureTask(player,record):new StructureSeatTask(player,record)); }
    public final UUID structureId;
    public final Vec3 interactionFocus;
    public final BlockPos seatOffset;
    public BoardStructureTaskRecord(String callId, long deadline, UUID structureId) {
        this(callId,deadline,structureId,null);
    }
    public BoardStructureTaskRecord(String callId,long deadline,UUID structureId,Vec3 interactionFocus) {
        this(callId,deadline,structureId,interactionFocus,null);
    }
    public BoardStructureTaskRecord(String callId,long deadline,UUID structureId,Vec3 interactionFocus,BlockPos seatOffset) {
        super("board_structure",callId,deadline);
        this.structureId = Objects.requireNonNull(structureId);
        this.interactionFocus=interactionFocus;
        this.seatOffset=seatOffset==null?null:seatOffset.immutable();
    }
    // 座位使用相对船体原点的整数偏移；拒绝含糊高度，避免误把移动后的世界坐标当成座位。
    public static BlockPos seatPosition(JsonObject input) {
        if(!input.has("seat_position"))return null;
        if(!input.has("structure_id")||!input.get("seat_position").isJsonObject())throw new IllegalArgumentException("seat_position requires structure_id and integer {x,y,z}");
        var p=input.getAsJsonObject("seat_position");
        if(!p.keySet().equals(Set.of("x","y","z")))throw new IllegalArgumentException("seat_position needs exactly x, y, z");
        int[] xyz=new int[3];int i=0;
        for(String axis:List.of("x","y","z")) {
            if(!p.get(axis).isJsonPrimitive()||!p.getAsJsonPrimitive(axis).isNumber())throw new IllegalArgumentException("seat_position must use integers");
            try {xyz[i++]=p.get(axis).getAsBigDecimal().intValueExact();}
            catch(ArithmeticException invalid){throw new IllegalArgumentException("seat_position must use integers",invalid);}
        }
        return new BlockPos(xyz[0],xyz[1],xyz[2]);
    }
    @Override public String describe() { return seatOffset==null?"登上目标物理结构并在支撑面站稳":"登上目标物理结构并原生坐入指定座位"; }
}
