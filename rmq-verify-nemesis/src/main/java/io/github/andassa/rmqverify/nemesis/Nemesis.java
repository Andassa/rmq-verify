package io.github.andassa.rmqverify.nemesis;

import io.github.andassa.rmqverify.cluster.ClusterControl;
import io.github.andassa.rmqverify.cluster.ClusterException;
import java.util.List;
import java.util.SortedMap;
import java.util.SplittableRandom;

/**
 * Service provider interface of a fault. Implementations are registered in {@code
 * META-INF/services/io.github.andassa.rmqverify.nemesis.Nemesis}. Random choices happen only in
 * {@link #choose}; {@link #inject} and {@link #heal} must be deterministic functions of the event.
 */
public interface Nemesis {

  /**
   * Returns the stable name used in plans.
   *
   * @return a kebab-case name, for example {@code kill-node}
   */
  String name();

  /**
   * Chooses targets and parameters for one occurrence of the fault.
   *
   * @param random seeded source of randomness owned by the planner
   * @param nodes nodes of the cluster
   * @return targets and parameters
   */
  Choice choose(SplittableRandom random, List<String> nodes);

  /**
   * Injects the fault.
   *
   * @param event the planned fault
   * @param cluster cluster control; checks every target with the safety guard
   * @throws ClusterException if the fault could not be fully injected
   */
  void inject(FaultEvent event, ClusterControl cluster) throws ClusterException;

  /**
   * Heals the fault. Must be idempotent and must succeed even after a partial injection.
   *
   * @param event the planned fault
   * @param cluster cluster control
   * @throws ClusterException if the fault could not be healed
   */
  void heal(FaultEvent event, ClusterControl cluster) throws ClusterException;

  /**
   * Targets and parameters chosen for one fault.
   *
   * @param targets affected nodes
   * @param params nemesis-specific parameters
   */
  record Choice(List<String> targets, SortedMap<String, String> params) {
    /** Copies the collections. */
    public Choice {
      targets = List.copyOf(targets);
      params = java.util.Collections.unmodifiableSortedMap(new java.util.TreeMap<>(params));
    }
  }
}
