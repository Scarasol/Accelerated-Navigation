package an.production;

import java.lang.reflect.InvocationTargetException;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Defers legacy probe setup and requests until the actual server is 30 seconds old. */
public final class ProductionProbeStartGate {
    private final Object probe;
    private ServerStartedEvent started;
    private long startedNanos;
    private boolean registered;

    public ProductionProbeStartGate(Object probe) { this.probe = probe; }

    @SubscribeEvent public void started(ServerStartedEvent event) {
        started = event;
        startedNanos = System.nanoTime();
    }

    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if (registered || started == null || event.getServer() != started.getServer()
                || event.phase != TickEvent.Phase.END
                || System.nanoTime() - startedNanos < 30_000_000_000L) return;
        registered = true;
        MinecraftForge.EVENT_BUS.register(probe);
        try {
            for (var method : probe.getClass().getMethods())
                if (method.isAnnotationPresent(SubscribeEvent.class)
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == ServerStartedEvent.class)
                    method.invoke(probe, started);
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure instanceof InvocationTargetException invoked
                    ? invoked.getCause() : failure;
            throw new IllegalStateException("Delayed production probe setup failed", cause);
        }
        MinecraftForge.EVENT_BUS.unregister(this);
    }
}
