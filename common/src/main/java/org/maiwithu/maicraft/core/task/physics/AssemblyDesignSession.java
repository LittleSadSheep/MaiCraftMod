package org.maiwithu.maicraft.core.task.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.blueprint.BuildProjectStore;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 冻结世界身份后在后台继承/保存完整设计，原生动作与保存结果分别交付。 */
final class AssemblyDesignSession {
    private AssemblyDesignSession() {}
    static CompletableFuture<PhysicalStructureDesignStore.Registration> prepare(LocalPlayer player,PhysicalAssemblyParameters parameters,BlockPos anchor,String dimension) {
        try {
            var identity=identity(player);boolean write=parameters.operation()!=PhysicalAssemblyParameters.Operation.INSPECT;
            return PhysicalStructureDesign.background(()->{
                JsonArray declarations=parameters.declarations();
                try {
                    declarations=AssemblyDesignMapping.declarations(parameters,anchor,dimension,new BuildProjectStore(identity));
                    if(parameters.structureId()==null)return new AssemblyWorldDesignStore(identity).merge(dimension,parameters.designId(),anchor,declarations,write);
                    var store=new PhysicalStructureDesignStore(identity);
                    return write?store.merge(dimension,parameters.structureId(),declarations,()->PhysicalStructureDesignRecovery.load(identity,dimension,parameters.structureId()))
                            :store.read(dimension,parameters.structureId(),declarations,()->PhysicalStructureDesignRecovery.load(identity,dimension,parameters.structureId()));
                } catch(Exception failed) {return failed(declarations,failed);}
            });
        } catch(RuntimeException failed) {return CompletableFuture.completedFuture(failed(parameters.declarations(),failed));}
    }
    static CompletableFuture<PhysicalStructureDesignStore.Registration> saveDisassembled(LocalPlayer player,BlockPos anchor,JsonArray absolute) {
        var targets=AssemblyDesignMapping.assembled(absolute,BlockPos.ZERO,BlockPos.ZERO,anchor);
        try {
            var identity=identity(player);String dimension=player.level().dimension().location().toString();
            return PhysicalStructureDesign.background(()->{
                try {return new AssemblyWorldDesignStore(identity).merge(dimension,null,anchor,targets,true);}
                catch(Exception failed) {return failed(targets,failed);}
            });
        } catch(RuntimeException failed) {return CompletableFuture.completedFuture(failed(targets,failed));}
    }
    private static StateIdentity identity(LocalPlayer player) {
        var minecraft=Minecraft.getInstance();
        if(minecraft.player!=player||minecraft.level!=player.level())throw new IllegalStateException("世界设计身份尚未绑定当前身体");
        return StateIdentity.resolve(minecraft).orElseThrow(()->new IllegalStateException("世界设计存储不可用"));
    }
    private static PhysicalStructureDesignStore.Registration failed(JsonArray declarations,Throwable failed) {
        var evidence=new JsonObject();evidence.addProperty("persistence_status","unavailable");evidence.addProperty("storage_error",failed.toString());
        evidence.addProperty("prior_history_complete",false);return new PhysicalStructureDesignStore.Registration(declarations.deepCopy(),evidence);
    }
}
