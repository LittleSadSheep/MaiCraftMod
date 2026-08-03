/**
 * LLM-facing tools grouped by intent domain. Root-level classes are read-only planners and shared
 * parsers; wrappers either answer from the current loaded client snapshot or emit one task record.
 *
 * <p>World, inventory and menu mutations are never performed by a wrapper. Their task owns native
 * action/menu receipts across ticks and reports success only after synchronized client facts confirm
 * the outcome. Long goals remain semantic tasks: the model supplies the goal and constraints, while
 * the runtime owns movement, targeting, retries within one attempt and concrete actions.
 */
package org.maiwithu.maicraft.core.tools;
