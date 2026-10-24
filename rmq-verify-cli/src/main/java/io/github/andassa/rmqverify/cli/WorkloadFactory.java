package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.model.HistoryRecorder;
import io.github.andassa.rmqverify.workload.Workload;
import io.github.andassa.rmqverify.workload.WorkloadConfig;

/** Creates the workload of a run; a seam that lets tests run experiments without a broker. */
@FunctionalInterface
public interface WorkloadFactory {

  /**
   * Creates a workload.
   *
   * @param config workload configuration
   * @param recorder history recorder
   * @return the workload, not yet prepared
   */
  Workload create(WorkloadConfig config, HistoryRecorder recorder);
}
