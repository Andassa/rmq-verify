package io.github.andassa.rmqverify.cluster;

import java.util.List;

/**
 * A queue as seen by the management API of some node.
 *
 * @param name queue name
 * @param type {@code classic}, {@code quorum} or {@code stream}
 * @param leader node hosting the leader (or the only replica of a classic queue)
 * @param members replica nodes, empty for classic queues
 * @param messages message count reported by the node
 */
public record QueueView(
    String name, String type, String leader, List<String> members, long messages) {

  /** Copies the member list. */
  public QueueView {
    members = List.copyOf(members);
  }
}
