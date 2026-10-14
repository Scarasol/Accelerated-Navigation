package an.budget.mixin;

import an.budget.BudgetAccess;
import an.budget.BudgetPrototype;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.util.Map;
import java.util.function.LongSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = NavigationScheduler.class, remap = false)
public abstract class BudgetSchedulerMixin implements BudgetAccess {
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final private Map<?, ?> pending;
    @Shadow private long tickStartedNanos;
    @Shadow private long tokensNanos;
    @Shadow private LongSupplier clock;
    @Shadow public abstract NavigationScheduler.TimeAccount account();
    @Shadow private void requireServerThread() { throw new AssertionError(); }
    @Shadow private long availableTokens() { throw new AssertionError(); }
    @Shadow private void drain(long deadline, int maxSlices) { throw new AssertionError(); }

    @Override public void budgetBegin(long started, long grant) {
        requireServerThread();
        tickStartedNanos = started;
        tokensNanos = Math.min(grant, tokensNanos + grant);
    }

    @Inject(method = "beginTick", at = @At("HEAD"), cancellable = true)
    private void begin(CallbackInfo callback) {
        requireServerThread();
        BudgetPrototype.State state = BudgetPrototype.state(server);
        if (state == null || !state.inTick) throw new IllegalStateException("Missing full tick envelope");
        state.sawStart = true;
        callback.cancel();
    }

    @Inject(method = "takeNext", at = @At("HEAD"), cancellable = true)
    private void recheckTime(long now, CallbackInfoReturnable<Object> callback) {
        BudgetPrototype.State state = BudgetPrototype.state(server);
        if (state != null && !state.haveTime.getAsBoolean()) {
            state.stopReason = "SERVER_NO_TIME";
            callback.setReturnValue(null);
        }
    }

    @Inject(method = "endTick", at = @At("HEAD"), cancellable = true)
    private void end(boolean haveTime, CallbackInfo callback) {
        requireServerThread();
        BudgetPrototype.State state = BudgetPrototype.state(server);
        if (state == null || !state.inTick) throw new IllegalStateException("Missing full tick envelope");
        state.sawEnd = true;
        state.pendingBefore = pending.size();
        state.balanceBeforeDrain = availableTokens();
        try (var ignored = account()) {
            long deadline = state.started + 45_000_000L - state.tailReserve;
            long allowance = Math.min(Math.max(0, availableTokens()), deadline - clock.getAsLong());
            if (!haveTime || !state.haveTime.getAsBoolean()) state.stopReason = "SERVER_NO_TIME";
            else if (pending.isEmpty()) state.stopReason = "EMPTY";
            else if (availableTokens() < 500_000L) state.stopReason = "BALANCE";
            else if (allowance < 500_000L) state.stopReason = "HEADROOM";
            else {
                state.stopReason = "DRAINED";
                drain(Math.min(clock.getAsLong() + allowance, deadline), Integer.MAX_VALUE);
                if (!state.stopReason.equals("SERVER_NO_TIME")) {
                    state.stopReason = pending.isEmpty() ? "EMPTY"
                            : availableTokens() < 500_000L ? "BALANCE"
                            : deadline - clock.getAsLong() < 500_000L ? "HEADROOM" : "DEPENDENCY_OR_THRESHOLD";
                }
            }
        } finally {
            tickStartedNanos = 0;
            state.drainEnded = clock.getAsLong();
            state.chargeAtDrainEnd = ((NavigationScheduler)(Object)this).metrics().chargedNanos();
        }
        callback.cancel();
    }
}
