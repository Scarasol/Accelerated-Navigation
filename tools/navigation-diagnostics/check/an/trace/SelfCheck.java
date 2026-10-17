package an.trace;

import java.util.concurrent.*;

public final class SelfCheck {
    static void nested() { long[] value = new long[100]; value[0] = System.nanoTime(); }
    static void failure() { throw new IllegalStateException("expected"); }
    static Object root() {
        nested();
        try { failure(); } catch (IllegalStateException expected) {}
        return new Object();
    }
    public static void main(String[] args) throws Exception {
        Trace.warm(); Trace.clock(); Trace.trial=1;
        Object result=root();
        Thread worker=new Thread(SelfCheck::root,"selfcheck-worker");worker.start();worker.join();
        if(result==null)throw new AssertionError();
        Trace.trial=0;
        System.out.println("TIMELINE_SELF_CHECK_OK");
    }
}
