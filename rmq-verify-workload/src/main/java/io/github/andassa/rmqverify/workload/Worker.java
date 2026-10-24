package io.github.andassa.rmqverify.workload;

/** A client loop (publisher or consumer) run on its own virtual thread. */
public interface Worker extends Runnable {

  /**
   * Returns the logical process name used in the history.
   *
   * @return the process name
   */
  String process();

  /** Asks the loop to finish; idempotent and non-blocking. */
  void stop();
}
