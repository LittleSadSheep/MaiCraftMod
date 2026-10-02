package org.maiwithu.maicraft.core.integration.physics.balance;

import java.util.function.BooleanSupplier;

/** 复杂船体的候选试算在后台有界执行，取消和预算不足都返回未完成，不能伪装成无解。 */
public final class PhysicsComputation implements AutoCloseable {
    private static final ThreadLocal<PhysicsComputation> ACTIVE=new ThreadLocal<>();
    private final long deadline; private long remaining;
    private final BooleanSupplier cancelled;
    private PhysicsComputation(long terms,long milliseconds,BooleanSupplier cancelled) {
        remaining=terms; deadline=System.nanoTime()+milliseconds*1_000_000; this.cancelled=cancelled; ACTIVE.set(this);
    }
    public static PhysicsComputation begin(BooleanSupplier cancelled) { return new PhysicsComputation(4_000_000,5000,cancelled); }
    static void spend(int terms) {
        var budget=ACTIVE.get(); if(budget==null) return;
        budget.remaining-=terms;
        if(budget.cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()||budget.remaining<0||System.nanoTime()>budget.deadline)
            throw new Limit("物理试算已达预算或被取消，当前未证明存在或不存在完整配平方案");
    }
    @Override public void close() { ACTIVE.remove(); }
    public static final class Limit extends RuntimeException { private Limit(String message) { super(message); } }
}
