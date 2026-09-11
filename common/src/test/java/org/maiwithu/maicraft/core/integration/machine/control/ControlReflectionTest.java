package org.maiwithu.maicraft.core.integration.machine.control;

public final class ControlReflectionTest {
    public static class Parent { private int hidden=7; public String value(int n) { return "integer:"+n; } }
    public static class Child extends Parent { public String value(String s) { return s; } }
    public static void main(String[] args) {
        var child=new Child();
        check(ControlReflection.call(child,"value",3).equals("integer:3"),"select the actual typed overload");
        check(ControlReflection.call(child,"value","observed").equals("observed"),"string overload");
        check(ControlReflection.field(child,"hidden").equals(7),"read inherited synchronized fields");
        check(ControlReflection.is(child,Parent.class.getName()),"recognize native subclasses");
        try { ControlReflection.call(child,"unknown"); throw new AssertionError("missing API looked successful"); }
        catch(IllegalStateException expected) { }
        System.out.println("ControlReflectionTest: passed");
    }
    private static void check(boolean result,String reason) { if(!result) throw new AssertionError(reason); }
}
