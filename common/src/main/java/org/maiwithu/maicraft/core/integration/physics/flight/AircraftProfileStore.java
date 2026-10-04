package org.maiwithu.maicraft.core.integration.physics.flight;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.maiwithu.maicraft.intent.persistence.MemoryDatabase;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 操纵声明按存档、维度和结构身份保存，travel 可复用；后台写入不阻塞正在运行的飞控刻。 */
public final class AircraftProfileStore {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(job->{var thread=new Thread(job,"maicraft-aircraft-profiles");thread.setDaemon(true);return thread;});
    private final StateIdentity identity;
    private final MemoryDatabase database;
    public AircraftProfileStore(StateIdentity identity){this.identity=identity;database=new MemoryDatabase(identity.databaseFile());}
    public CompletableFuture<Map<UUID,AircraftProfile>> read(String dimension){return background(()->readNow(dimension));}
    public CompletableFuture<AircraftProfile> save(String dimension,UUID id,AircraftProfile profile) {
        return background(()->{
            try {
                database.updateRecord(identity.scope()+"/aircraft",identity.key(),dimension,2_097_152,raw->{
                    JsonObject doc=raw==null?new JsonObject():JsonParser.parseString(raw).getAsJsonObject();
                    doc.add(id.toString(),profile.json());return doc.toString();
                });
                return profile;
            } catch(IOException failed){throw new UncheckedIOException(failed);}
        });
    }
    private Map<UUID,AircraftProfile> readNow(String dimension) {
        try {
            String raw=database.readRecord(identity.scope()+"/aircraft",identity.key(),dimension,2_097_152);
            if(raw==null)return Map.of();
            var result=new LinkedHashMap<UUID,AircraftProfile>();
            JsonParser.parseString(raw).getAsJsonObject().entrySet().forEach(entry->result.put(UUID.fromString(entry.getKey()),AircraftProfile.parse(entry.getValue().getAsJsonObject())));
            return Map.copyOf(result);
        } catch(IOException failed){throw new UncheckedIOException(failed);}
    }
    private static <T> CompletableFuture<T> background(Supplier<T> work){return CompletableFuture.supplyAsync(work,IO);}
}
