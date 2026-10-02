package org.maiwithu.maicraft.server.physics;

import com.google.gson.JsonObject;
import java.util.UUID;
import org.maiwithu.maicraft.network.ProtocolJson;
import org.maiwithu.maicraft.network.ServerOperationException;
import org.maiwithu.maicraft.server.machine.NativeApi;

/** 重现服务端没有客户端 finalized 方法的原生契约，并验证反射失败和超限结果不会丢失具体原因。 */
public final class PhysicsSnapshotServiceTest {
    public static void run() {
        UUID id=UUID.randomUUID(); var ship=new ServerShip(); var container=new ServerContainer(id,ship);
        check(PhysicsSnapshotService.resolveServerStructure(container,id)==ship,"服务器结构必须不依赖客户端 finalized 标记");
        ship.removed=true;
        reject(()->PhysicsSnapshotService.resolveServerStructure(container,id),"structure_unavailable");
        ship.removed=false;
        reject(()->PhysicsSnapshotService.resolveServerStructure(container,UUID.randomUUID()),"structure_unavailable");
        var expected=ServerOperationException.notApplied("structure_out_of_range","outside");
        try { PhysicsSnapshotService.readBounded(id.toString(),()->{throw expected;});throw new AssertionError("原生前置拒绝被吞掉"); }
        catch(ServerOperationException actual) { check(actual==expected,"前置拒绝的原始代码应保留"); }
        try {
            PhysicsSnapshotService.readBounded(id.toString(),()->{throw new NativeApi.Unavailable("SubLevelContainer.getContainer",null);});
            throw new AssertionError("签名失败没有返回可定位原因");
        } catch(ServerOperationException failed) {
            check(failed.code().equals("physics_snapshot_failed")&&failed.getMessage().contains("SubLevelContainer.getContainer"),"不能退化成无原因的 handler_failed");
        }
        String longDetail="native_signature_context;".repeat(80)+"distinct_root_cause";
        try {
            PhysicsSnapshotService.readBounded(id.toString(),()->{throw new NativeApi.Unavailable(longDetail,null);});
            throw new AssertionError("长诊断原因被忽略");
        } catch(ServerOperationException failed) { check(failed.getMessage().endsWith(longDetail),"不能按固定字符数丢弃异常事实"); }
        reject(()->PhysicsSnapshotService.readBounded(id.toString(),()->{
            var large=new JsonObject();large.addProperty("oversized", "x".repeat(ProtocolJson.MAX_ENVELOPE_CHARS+1));return large;
        }),"physics_snapshot_failed");
        var success=new JsonObject();success.addProperty("state","sampling");
        check(PhysicsSnapshotService.readBounded(id.toString(),()->success)==success,"首次等待物理子步的合法响应必须照常返回");
        System.out.println("PhysicsSnapshotServiceTest: passed");
    }
    // Sable 2.0.3 的 ServerSubLevel 只提供 isRemoved；不在替身里补出实际不存在的客户端接口。
    public static final class ServerShip {
        boolean removed;
        public boolean isRemoved() { return removed; }
    }
    public static final class ServerContainer {
        private final UUID id;private final Object ship;
        ServerContainer(UUID id,Object ship) { this.id=id;this.ship=ship; }
        public Object getSubLevel(UUID requested) { return id.equals(requested)?ship:null; }
    }
    private static void reject(Runnable action,String code) {
        try { action.run();throw new AssertionError("expected "+code); }
        catch(ServerOperationException failed) { check(failed.code().equals(code),failed.getMessage()); }
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
