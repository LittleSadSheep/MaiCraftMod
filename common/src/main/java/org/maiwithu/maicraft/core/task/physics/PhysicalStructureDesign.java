package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 游戏线程冻结身份与补丁，后台恢复并登记完整设计；完成持久化后才能开始本次原生施工。 */
final class PhysicalStructureDesign {
    private static final ThreadPoolExecutor WRITER=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4),run->{var thread=new Thread(run,"maicraft-physical-design");thread.setDaemon(true);return thread;});
    private PhysicalStructureDesign() {}
    // 世界设计与船体设计共用有界后台队列，读取大工程时不阻塞游戏刻。
    static <T> CompletableFuture<T> background(Supplier<T> work) {return CompletableFuture.supplyAsync(work,WRITER);}
    static CompletableFuture<PhysicalStructureDesignStore.Registration> merge(LocalPlayer player,UUID id,JsonArray edits) {
        JsonArray frozen=edits.deepCopy();
        try {
            var minecraft=Minecraft.getInstance();
            if(minecraft.player!=player||minecraft.level!=player.level()) throw new IllegalStateException("physical_design_world_not_bound");
            var identity=StateIdentity.resolve(minecraft).orElseThrow(()->new IllegalStateException("physical_design_world_identity_unavailable"));
            String dimension=player.level().dimension().location().toString();
            // 后台只访问不可变身份和 JSON，不持有世界对象、不读取方块，更不会替玩家改世界。
            return CompletableFuture.supplyAsync(()->new PhysicalStructureDesignStore(identity).merge(dimension,id,frozen,
                    ()->PhysicalStructureDesignRecovery.load(identity,dimension,id)),WRITER);
        } catch(RuntimeException unavailable) {
            var evidence=new JsonObject();evidence.addProperty("persistence_status","unavailable");
            evidence.addProperty("prior_history_complete",false);evidence.addProperty("storage_error",unavailable.toString());
            return CompletableFuture.completedFuture(new PhysicalStructureDesignStore.Registration(frozen,evidence));
        }
    }
}
