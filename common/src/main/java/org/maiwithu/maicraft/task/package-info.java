/**
 * The single client-side task runtime for the active {@code LocalPlayer}.
 *
 * <p>{@code TaskRecord} carries typed inputs and lifecycle state,
 * {@code TaskFactory} maps records to executors, {@code TaskSelector} chooses
 * one body owner per client tick, and {@code CompanionTickDispatcher} exposes
 * the runtime to loader and MCP boundaries.</p>
 */
package org.maiwithu.maicraft.task;
