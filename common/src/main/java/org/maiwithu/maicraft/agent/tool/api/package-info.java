/**
 * <strong>Public API.</strong> {@link ToolContext} — the per-call context (the
 * tool-call id plus a deadline helper) a server-side tool uses when building a
 * task record.
 *
 * <p>The reflective {@code @MaiCraftAction} / {@code @Arg} authoring layer that
 * used to live here has been removed: a tool is just a
 * {@link org.maiwithu.maicraft.agent.tool.MaiCraftTool} (name, description, schema,
 * {@code invoke}). maicraft-core provides optional authoring sugar (a {@code Schema}
 * builder and a {@code TaskDispatch} helpers) for packs that want them.
 */
package org.maiwithu.maicraft.agent.tool.api;
