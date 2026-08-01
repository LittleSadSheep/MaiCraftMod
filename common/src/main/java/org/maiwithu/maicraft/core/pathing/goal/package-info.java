/**
 * The intent boundary of the pathing package: tasks say WHAT they want
 * ({@link org.maiwithu.maicraft.core.pathing.goal.GoalCompiler#interact interact
 * with this block}, {@link org.maiwithu.maicraft.core.pathing.goal.GoalCompiler#standOn
 * stand on this cell}, {@link org.maiwithu.maicraft.core.pathing.goal.GoalCompiler#mineField
 * mine this field}) and the compiler translates that — in ONE place — into the
 * things a navigation and its task must agree on: the search goal
 * (a {@link org.maiwithu.maicraft.core.pathing.calc.NavGoal}) and the sacred cells the
 * route may neither break nor bury (threaded into
 * {@link org.maiwithu.maicraft.core.pathing.moves.CalculationContext#sacred}).
 *
 * <p>Before this layer existed each task hand-picked its NavGoal, and the
 * fallback for "go to a block" was a Euclidean sphere that admitted elevated
 * cells — the geometry behind approaches that finished by pillaring beside
 * their target, and nothing marked the target itself as untouchable, so routes
 * could break or bury the very block they were travelling to.
 *
 * <p>Package contract: pathing's public front door is
 * {@code PlayerNav} + {@code GoalCompiler} (+ the {@code NavGoal} vocabulary
 * for custom goals like {@code runAway}). Task code should not assemble
 * goal/sacred pairs by hand.
 */
package org.maiwithu.maicraft.core.pathing.goal;
