package io.github.andassa.rmqverify.nemesis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.TreeMap;

/** Nemeses available through {@link ServiceLoader}, by name. */
public final class NemesisRegistry {

  private final Map<String, Nemesis> byName;

  private NemesisRegistry(Collection<Nemesis> nemeses) {
    Map<String, Nemesis> map = new TreeMap<>();
    for (Nemesis nemesis : nemeses) {
      if (map.putIfAbsent(nemesis.name(), nemesis) != null) {
        throw new IllegalStateException("two nemeses are named " + nemesis.name());
      }
    }
    this.byName = map;
  }

  /**
   * Loads every registered nemesis.
   *
   * @return the registry
   */
  public static NemesisRegistry load() {
    List<Nemesis> found = new ArrayList<>();
    ServiceLoader.load(Nemesis.class, NemesisRegistry.class.getClassLoader()).forEach(found::add);
    return new NemesisRegistry(found);
  }

  /**
   * Builds a registry from explicit instances.
   *
   * @param nemeses the nemeses
   * @return the registry
   */
  public static NemesisRegistry of(Collection<Nemesis> nemeses) {
    return new NemesisRegistry(nemeses);
  }

  /**
   * Looks a nemesis up.
   *
   * @param name nemesis name
   * @return the nemesis, empty if unknown
   */
  public Optional<Nemesis> byName(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  /**
   * Returns the registered names, sorted.
   *
   * @return the names
   */
  public List<String> names() {
    return List.copyOf(byName.keySet());
  }
}
