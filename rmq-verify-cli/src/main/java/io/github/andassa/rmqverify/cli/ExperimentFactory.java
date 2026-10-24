package io.github.andassa.rmqverify.cli;

import io.github.andassa.rmqverify.cluster.ClusterException;
import io.github.andassa.rmqverify.workload.WorkloadException;
import java.io.PrintWriter;

/** Builds the experiment of a command; the real one talks to Docker and RabbitMQ. */
@FunctionalInterface
interface ExperimentFactory {

  Experiment create(RunOptions options, PrintWriter out) throws ClusterException, WorkloadException;
}
