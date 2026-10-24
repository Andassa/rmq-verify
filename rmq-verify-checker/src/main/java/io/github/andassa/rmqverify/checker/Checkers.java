package io.github.andassa.rmqverify.checker;

import io.github.andassa.rmqverify.model.History;
import io.github.andassa.rmqverify.model.Verdict;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/** Registry of the checkers available through {@link ServiceLoader}. */
public final class Checkers {

  private final Map<String, Checker> byName;

  private Checkers(List<Checker> checkers) {
    Map<String, Checker> map = new LinkedHashMap<>();
    for (Checker checker : checkers) {
      if (map.putIfAbsent(checker.name(), checker) != null) {
        throw new IllegalStateException("two checkers are named " + checker.name());
      }
    }
    this.byName = map;
  }

  /**
   * Loads every checker registered on the class path, sorted by name.
   *
   * @return the registry
   */
  public static Checkers load() {
    List<Checker> found = new ArrayList<>();
    ServiceLoader.load(Checker.class, Checkers.class.getClassLoader()).forEach(found::add);
    found.sort(Comparator.comparing(Checker::name));
    return new Checkers(found);
  }

  /**
   * Builds a registry from explicit instances, in the given order.
   *
   * @param checkers the checkers
   * @return the registry
   */
  public static Checkers of(List<Checker> checkers) {
    return new Checkers(List.copyOf(checkers));
  }

  /**
   * Returns the registered checkers.
   *
   * @return the checkers in registry order
   */
  public List<Checker> all() {
    return List.copyOf(byName.values());
  }

  /**
   * Looks a checker up by name.
   *
   * @param name checker name
   * @return the checker, empty if unknown
   */
  public Optional<Checker> byName(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  /**
   * Runs every registered checker on a history.
   *
   * @param history the history
   * @param context thresholds
   * @return one verdict per checker, in registry order
   */
  public List<Verdict> checkAll(History history, CheckerContext context) {
    return byName.values().stream().map(c -> c.check(history, context)).toList();
  }
}
