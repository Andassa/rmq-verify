package io.github.andassa.rmqverify.nemesis.faults;

import io.github.andassa.rmqverify.cluster.ClusterControl.Link;
import io.github.andassa.rmqverify.nemesis.Nemesis;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.SplittableRandom;
import java.util.TreeMap;

/** Seeded selection helpers shared by the nemeses. */
final class Choices {

  private Choices() {}

  static List<String> shuffled(SplittableRandom random, List<String> nodes) {
    List<String> copy = new ArrayList<>(nodes);
    for (int i = copy.size() - 1; i > 0; i--) {
      Collections.swap(copy, i, random.nextInt(i + 1));
    }
    return copy;
  }

  static Nemesis.Choice oneNode(
      SplittableRandom random, List<String> nodes, SortedMap<String, String> params) {
    return new Nemesis.Choice(List.of(nodes.get(random.nextInt(nodes.size()))), params);
  }

  static SortedMap<String, String> params(String... keyValues) {
    SortedMap<String, String> params = new TreeMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      params.put(keyValues[i], keyValues[i + 1]);
    }
    return params;
  }

  /** Every link between a node of {@code side} and a node outside it. */
  static List<Link> across(List<String> side, List<String> all) {
    List<Link> links = new ArrayList<>();
    for (String inside : side) {
      for (String outside : all) {
        if (!side.contains(outside)) {
          links.add(new Link(inside, outside));
        }
      }
    }
    return links;
  }
}
