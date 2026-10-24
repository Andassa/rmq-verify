# Extending rmq-verify

Checkers and nemeses are service providers discovered with `java.util.ServiceLoader`: adding one
means implementing an interface, registering it in `META-INF/services` and proving it with tests.

## Adding a checker

1. Write the definition first, in [METHODOLOGY.md](METHODOLOGY.md): what is a violation, what is
   only a note, and when the history is inconclusive.
2. Implement `io.github.andassa.rmqverify.checker.Checker` in `rmq-verify-checker`. A checker is a
   pure function of the history: no I/O, no clock, no randomness.

   ```java
   public final class NoEmptyRunChecker implements Checker {
     public static final String NAME = "NoEmptyRun";

     @Override
     public String name() {
       return NAME;
     }

     @Override
     public Verdict check(History history, CheckerContext context) {
       MessageLedger ledger = MessageLedger.of(history);
       Findings findings = new Findings(NAME);
       if (ledger.messages().stream().noneMatch(r -> r.outcome() == OpType.OK)) {
         findings.violation("NOTHING_CONFIRMED", "no publish was ever confirmed",
             history.operations().subList(0, Math.min(1, history.size())));
       }
       return findings.verdict(ledger.hasPublications(), "...");
     }
   }
   ```

   `MessageLedger` indexes publications, confirms, deliveries, acks, drains and fault windows; use
   it rather than scanning the history yourself. Every violation must cite the operations that
   prove it.
3. Register the class in
   `rmq-verify-checker/src/main/resources/META-INF/services/io.github.andassa.rmqverify.checker.Checker`.
4. Test it the way the built-in checkers are tested (ADR-0006):
   - the property `correctRunsAreValidForEveryChecker` already runs your checker on hundreds of
     correct synthetic histories: it must say `VALID`;
   - add one property that injects your anomaly into a generated run (`SyntheticRun`, events
     `Ev`) and asserts that your checker finds it, with the injected operations as evidence;
   - add edge cases (empty history, inconclusive history) to `CheckerEdgeCasesTest`;
   - `make mutation` must stay above 80 %.
5. If the checker produces metrics that belong in the matrix, extend `MatrixCell`.

## Adding a nemesis

1. Implement `io.github.andassa.rmqverify.nemesis.Nemesis` in `rmq-verify-nemesis`:
   - `choose(random, nodes)` makes every random decision (targets, parameters) and returns them;
     they are written to `plan.json` so that replays are identical;
   - `inject(event, cluster)` and `heal(event, cluster)` act only through `ClusterControl`, whose
     methods all go through the safety guard. `heal` must be idempotent and work after a partial
     injection.
2. If you need a new kind of action, add it to `ClusterControl` and implement it in
   `DockerClusterControl` with structured `docker` arguments (ADR-0012); `healAll()` must undo it.
3. Register the class in
   `rmq-verify-nemesis/src/main/resources/META-INF/services/io.github.andassa.rmqverify.nemesis.Nemesis`.
4. Test `choose`, `inject` and `heal` against `RecordingCluster` (see `NemesesTest`), and run
   a real scenario: `rmq-verify run --nemesis your-nemesis --duration 60`.
5. Document the fault mechanism in [ADR-0011](adr/0011-fault-injection-mechanisms.md) if it
   introduces a new one.
