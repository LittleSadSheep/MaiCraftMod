package org.maiwithu.maicraft.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * The only boundary between MCP and the in-process game runtime.
 *
 * <p>Implementations must delegate to the application's existing task, skill and
 * tool registries. They must not create a second scheduler or task state store.
 * Methods may be called from MCP daemon threads, so an implementation that reads
 * game state must marshal work to the game client thread.</p>
 */
public interface RuntimeFacade {
    /**
     * Cancellation state for work marshalled onto the Minecraft client thread.
     * Transport timeouts must distinguish work that was withdrawn before it
     * touched game state from work whose outcome is no longer knowable.
     */
    enum CancellationDisposition {
        CANCELLED_BEFORE_START,
        CANCELLED_WHILE_WAITING,
        ALREADY_STARTED,
        SETTLED
    }

    /** Optional marker implemented by runtime stages with an authoritative cancel gate. */
    interface ManagedCall {
        CancellationDisposition cancelCall();
    }

    CompletionStage<JsonElement> perceive(JsonObject arguments);

    CompletionStage<JsonElement> plan(JsonObject arguments);

    CompletionStage<JsonElement> execute(JsonObject arguments);

    CompletionStage<JsonElement> task(JsonObject arguments);

    CompletionStage<JsonElement> readAttention();

    /** Read-only documentation requests; implementations may inspect registries but need no player world. */
    default CompletionStage<JsonElement> knowledge(JsonObject arguments) {
        return java.util.concurrent.CompletableFuture.completedFuture(
                org.maiwithu.maicraft.mcp.knowledge.KnowledgeLibrary.offline().request(arguments));
    }

    /**
     * Registers a listener for important-event changes. Closing the returned
     * handle must detach only this listener and must be safe to call repeatedly.
     */
    AutoCloseable subscribeAttention(Consumer<JsonElement> listener);
}
