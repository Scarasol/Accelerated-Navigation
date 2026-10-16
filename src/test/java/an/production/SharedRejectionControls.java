package an.production;

/** Fault injection state belongs to the verification mod only. */
public final class SharedRejectionControls {
    public static volatile boolean rejectSubmission, rejectUnavailable;
    public static int submissionHits, unavailableHits;
    private SharedRejectionControls() {}
}
