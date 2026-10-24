package io.github.andassa.rmqverify.cluster;

import java.util.List;

/**
 * One node as seen by the management API of some node.
 *
 * @param name host part of the Erlang node name ({@code rabbit1})
 * @param running whether the observer believes the node runs
 * @param partitions nodes this node reports as partitioned away
 */
public record NodeView(String name, boolean running, List<String> partitions) {

  /** Copies the partition list. */
  public NodeView {
    partitions = List.copyOf(partitions);
  }
}
