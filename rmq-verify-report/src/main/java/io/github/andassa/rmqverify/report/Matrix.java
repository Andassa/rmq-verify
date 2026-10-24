package io.github.andassa.rmqverify.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Scenario by RabbitMQ version comparison of many runs. */
public final class Matrix {

  private final Map<String, Map<String, MatrixCell>> cells;
  private final List<RunSummary> runs;

  private Matrix(List<RunSummary> runs) {
    this.runs = List.copyOf(runs);
    Map<String, Map<String, List<RunSummary>>> grouped = new TreeMap<>();
    for (RunSummary run : runs) {
      grouped
          .computeIfAbsent(run.scenario(), s -> new TreeMap<>())
          .computeIfAbsent(run.rabbitmqVersion(), v -> new ArrayList<>())
          .add(run);
    }
    Map<String, Map<String, MatrixCell>> built = new TreeMap<>();
    grouped.forEach(
        (scenario, byVersion) -> {
          Map<String, MatrixCell> row = new TreeMap<>();
          byVersion.forEach((version, list) -> row.put(version, MatrixCell.of(list)));
          built.put(scenario, row);
        });
    this.cells = built;
  }

  /**
   * Builds a matrix from summaries.
   *
   * @param runs run summaries
   * @return the matrix
   */
  public static Matrix of(List<RunSummary> runs) {
    return new Matrix(runs);
  }

  /**
   * Loads every {@code report.json} found under a directory.
   *
   * @param root directory to scan, recursively
   * @return the matrix
   * @throws IOException if a report cannot be read
   */
  public static Matrix load(Path root) throws IOException {
    ReportJson json = new ReportJson();
    List<RunSummary> runs = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root, 4)) {
      for (Path file : files.filter(Matrix::isReport).sorted().toList()) {
        runs.add(json.read(file));
      }
    }
    return new Matrix(runs);
  }

  private static boolean isReport(Path path) {
    Path name = path.getFileName();
    return name != null && "report.json".equals(name.toString());
  }

  /**
   * Returns the scenarios, sorted.
   *
   * @return scenario names
   */
  public List<String> scenarios() {
    return List.copyOf(cells.keySet());
  }

  /**
   * Returns every version that appears in at least one scenario, sorted.
   *
   * @return versions
   */
  public List<String> versions() {
    return cells.values().stream()
        .flatMap(row -> row.keySet().stream())
        .distinct()
        .sorted()
        .toList();
  }

  /**
   * Looks up one cell.
   *
   * @param scenario scenario name
   * @param version RabbitMQ version
   * @return the aggregate, empty if that combination was not run
   */
  public Optional<MatrixCell> cell(String scenario, String version) {
    return Optional.ofNullable(cells.getOrDefault(scenario, Map.of()).get(version));
  }

  /**
   * Returns the runs the matrix was built from.
   *
   * @return the summaries
   */
  public List<RunSummary> runs() {
    return runs;
  }
}
