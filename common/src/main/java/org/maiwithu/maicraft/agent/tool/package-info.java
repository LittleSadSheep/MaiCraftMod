/**
 * <strong>Public API.</strong> The raw tool contract the engine schedules. A
 * tool implements {@link MaiCraftTool} (name / description / schema / {@code invoke})
 * and is registered in the {@link ToolRegistry}; {@link ToolCall} is the handle
 * passed to {@link MaiCraftTool#invoke} — the tool does whatever it likes (on any
 * thread, sending its own packets, calling out to anything) and calls
 * {@link ToolCall#complete} when the result is ready. The engine is a scheduler,
 * not an executor.
 *
 * <p>Also public: {@link Schema} (the JSON-schema builder every tool's
 * {@code parameterSchema} uses) and {@link ToolArgs} (shared argument-parsing
 * helpers). The per-call {@code ToolContext} lives next door in
 * {@code agent.tool.api}; any server-side task execution ships in the tool pack
 * ({@code maicraft-core}) — not in the engine.
 *
 * <p>Internal members ({@link org.maiwithu.maicraft.api.Internal @Internal}):
 * {@link ToolInvocation} (the scheduler's per-call unit) and
 * {@link ClientToolContext} (the client-side context implementation).
 */
package org.maiwithu.maicraft.agent.tool;
