package an.production;

import com.scarasol.acceleratednavigation.api.ResumableSearch;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/** Deterministic API-level controls of the actual production scheduler. */
final class ProductionBudgetChecks {
    private static Field field(String name) throws Exception {
        Field result=NavigationScheduler.class.getDeclaredField(name);result.setAccessible(true);return result;
    }
    private static void require(boolean passed,String name) {
        if(!passed)throw new AssertionError("production budget: "+name);
    }
    static void run(MinecraftServer server) throws Exception {
        var scheduler=NavigationScheduler.forServer(server);
        AtomicLong clock=new AtomicLong(1000000000);
        Field clockField=field("clock"),tokens=field("tokensNanos"),charged=field("chargedNanos"),started=field("tickStartedNanos");
        Object oldClock=clockField.get(scheduler);
        long oldTokens=tokens.getLong(scheduler),oldCharged=charged.getLong(scheduler),oldStarted=started.getLong(scheduler);
        try {
            clockField.set(scheduler,(java.util.function.LongSupplier)clock::get);tokens.setLong(scheduler,0);
            NavigationScheduler.beginServerTick(server);
            AtomicInteger calls=new AtomicInteger();
            var first=scheduler.submitSoft(Level.OVERWORLD,UUID.randomUUID(),NavigationScheduler.Priority.ACTIVE,500000,()-> {
                calls.incrementAndGet();clock.addAndGet(4700000);
                try(var ignored=scheduler.account()) { clock.addAndGet(200000); }
                return 7;
            });
            first.thenAccept(value->clock.addAndGet(800000));
            UUID secondOwner=UUID.randomUUID();
            var second=scheduler.submitSoft(Level.NETHER,secondOwner,NavigationScheduler.Priority.ACTIVE,500000,()->calls.incrementAndGet());
            NavigationScheduler.endServerTick(server,false);
            require(calls.get()==0,"haveTime prevents dispatch");
            NavigationScheduler.beginServerTick(server);
            require(scheduler.metrics().availableNanos()==4000000,"positive cap is 4ms");
            NavigationScheduler.endServerTick(server,true);
            require(first.join()==7&&calls.get()==1&&!second.isDone(),"one atomic overshoot halts further dispatch");
            require(scheduler.metrics().availableNanos()==-1700000,"search and callback counted as one union");
            require(scheduler.metrics().chargedNanos()-oldCharged==5700000,"nested account does not double count");
            NavigationScheduler.beginServerTick(server);
            require(scheduler.metrics().availableNanos()==2300000,"refill keeps debt");
            NavigationScheduler.endServerTick(server,true);
            require(second.isDone()&&calls.get()==2,"other dimension makes progress after debt");
            scheduler.cancel(Level.NETHER,secondOwner);
            UUID replacementOwner=UUID.randomUUID();
            var replaced=scheduler.submitSoft(Level.OVERWORLD,replacementOwner,NavigationScheduler.Priority.ACTIVE,500000,()->1);
            var replacement=scheduler.submitSoft(Level.OVERWORLD,replacementOwner,NavigationScheduler.Priority.ACTIVE,500000,()->2);
            require(replaced.isCancelled()&&!replacement.isDone(),"same key replaces old request");
            scheduler.cancel(Level.OVERWORLD,replacementOwner);
            require(replacement.isCancelled(),"cancel prevents execution");
            Parking search=new Parking();
            var parked=scheduler.submitStrict(Level.OVERWORLD,UUID.randomUUID(),NavigationScheduler.Priority.ACTIVE,search);
            NavigationScheduler.beginServerTick(server);NavigationScheduler.endServerTick(server,true);
            require(search.steps==1&&!parked.isDone(),"strict work parks after step");
            search.wake.run();
            require(search.steps==1,"wake only enqueues");
            NavigationScheduler.beginServerTick(server);NavigationScheduler.endServerTick(server,true);
            require(parked.join()==9&&search.steps==2,"wake runs in subsequent END");
            require(scheduler.metrics().pendingRequests()==0,"zero pending after controls");
            System.out.println("PRODUCTION_BUDGET_CONTROLS 13 passed");
        } finally {
            clockField.set(scheduler,oldClock);tokens.setLong(scheduler,oldTokens);
            charged.setLong(scheduler,oldCharged);started.setLong(scheduler,oldStarted);
        }
    }
    private static final class Parking implements ResumableSearch<Integer> {
        int steps;
        Runnable wake;
        public Status step(int budget,long deadline) { return ++steps==1?Status.RUNNING:Status.SUCCEEDED; }
        public Status status() { return steps<2?Status.RUNNING:Status.SUCCEEDED; }
        public Integer result() { return 9; }
        public boolean park(Runnable wake) { this.wake=wake;return steps==1; }
        public void cancel() {}
    }
}
