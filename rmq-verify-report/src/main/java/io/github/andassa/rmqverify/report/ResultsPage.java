package io.github.andassa.rmqverify.report;

import io.github.andassa.rmqverify.model.Anomaly;
import io.github.andassa.rmqverify.model.Verdict;
import io.github.andassa.rmqverify.model.VerdictStatus;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Renders {@code RESULTS.md} from a matrix. Every number on the page comes from the {@code
 * report.json} files of real runs; nothing is typed by hand.
 *
 * @param generatedAt ISO-8601 generation time
 * @param gitCommit commit of the code that produced the runs
 * @param images container images used, by role
 * @param host description of the machine
 */
public record ResultsPage(
    String generatedAt, String gitCommit, Map<String, String> images, String host) {

  /** Copies the image map. */
  public ResultsPage {
    images = Map.copyOf(new java.util.TreeMap<>(images));
  }

  /**
   * Renders the page.
   *
   * @param matrix the runs
   * @return Markdown
   */
  public String markdown(Matrix matrix) {
    StringBuilder md = new StringBuilder("# Résultats\n\n");
    md.append("> Généré par `make bench` le ")
        .append(generatedAt)
        .append(" à partir du commit `")
        .append(gitCommit)
        .append("`. Ne pas modifier à la main : relancer `make bench`.\n\n");
    md.append("- Hôte : ").append(host).append('\n');
    md.append("- Exécutions : ")
        .append(matrix.runs().size())
        .append(" (historiques bruts dans `results/`)\n");
    new java.util.TreeMap<>(images)
        .forEach(
            (role, image) ->
                md.append("- Image ").append(role).append(": `").append(image).append("`\n"));
    appendMatrix(md, matrix);
    appendDetails(md, matrix);
    appendCounterexamples(md, matrix);
    md.append("\n## Comment lire ces résultats\n\n");
    for (String line : limits()) {
      md.append("- ").append(line).append('\n');
    }
    return md.toString();
  }

  private static void appendMatrix(StringBuilder md, Matrix matrix) {
    md.append("\n## Matrice\n\n");
    md.append("Chaque cellule : exécutions avec une violation / répétitions, puis les totaux.\n\n");
    md.append("| Scénario |");
    matrix.versions().forEach(v -> md.append(" RabbitMQ ").append(v).append(" |"));
    md.append("\n|---|");
    matrix.versions().forEach(v -> md.append("---|"));
    md.append('\n');
    for (String scenario : matrix.scenarios()) {
      md.append("| `").append(scenario).append("` |");
      for (String version : matrix.versions()) {
        md.append(' ')
            .append(
                matrix.cell(scenario, version).map(ResultsPage::summaryCell).orElse("pas exécuté"))
            .append(" |");
      }
      md.append('\n');
    }
  }

  private static void appendDetails(StringBuilder md, Matrix matrix) {
    md.append("\n## Détail\n\n");
    md.append(
            "| Scénario | Version | File | Exécutions | Invalides | Inconnues | Confirmés | Indéterminés | Perdus | ")
        .append(
            "Absents après reprise | Doublons inattendus | Violations d'ordre | Inattendus | Reprise max (s) | ")
        .append("Succès min |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
    for (String scenario : matrix.scenarios()) {
      for (String version : matrix.versions()) {
        matrix
            .cell(scenario, version)
            .ifPresent(
                c ->
                    md.append(
                        String.format(
                            Locale.ROOT,
                            "| `%s` | %s | %s | %d | %d | %d | %d | %d | %d | %d | %d | %d | %d | %.1f | %.1f%% |%n",
                            c.scenario(),
                            c.version(),
                            c.queueKind(),
                            c.runs(),
                            c.invalidRuns(),
                            c.unknownRuns(),
                            c.confirmed(),
                            c.indeterminate(),
                            c.lostConfirmed(),
                            c.missingAfterRecovery(),
                            c.unexpectedDuplicates(),
                            c.orderViolations(),
                            c.unexpectedMessages(),
                            c.maxRecoveryMs() / 1000,
                            100 * c.minSuccessRate())));
      }
    }
  }

  private static void appendCounterexamples(StringBuilder md, Matrix matrix) {
    md.append("\n## Contre-exemples\n\n");
    boolean any = false;
    for (RunSummary run : matrix.runs()) {
      for (Verdict v : run.verdicts()) {
        Optional<Anomaly> first = v.anomalies().stream().filter(Anomaly::isViolation).findFirst();
        if (v.status() == VerdictStatus.INVALID && first.isPresent()) {
          any = true;
          md.append("- `")
              .append(run.scenario())
              .append("` sur ")
              .append(run.rabbitmqVersion())
              .append(", exécution `")
              .append(run.runId())
              .append("`, ")
              .append(v.checker())
              .append(" : ")
              .append(v.violationCount())
              .append(" violation(s), p. ex. ")
              .append(first.get().description().replace("|", "\\|"))
              .append(" (opérations ")
              .append(first.get().evidence())
              .append(")\n");
          break;
        }
      }
    }
    if (!any) {
      md.append("Aucune violation dans ces exécutions.\n");
    }
  }

  /** Markers of the README block refreshed by {@link #updateBlock(String, Matrix)}. */
  public static final String START = "<!-- results:start -->";

  /** End marker of the README block. */
  public static final String END = "<!-- results:end -->";

  /**
   * Replaces the text between {@link #START} and {@link #END} with the current matrix table, so
   * that the README never carries hand-written numbers.
   *
   * @param document a document containing both markers
   * @param matrix the runs
   * @return the document with a fresh table
   */
  public String updateBlock(String document, Matrix matrix) {
    int start = document.indexOf(START);
    int end = document.indexOf(END);
    if (start < 0 || end < start) {
      throw new IllegalArgumentException("the document lacks the results markers");
    }
    StringBuilder block = new StringBuilder(START).append("\n");
    block
        .append("_Généré par `make bench` le ")
        .append(generatedAt)
        .append(" (commit `")
        .append(gitCommit)
        .append("`) ; le détail est dans [RESULTS.md](RESULTS.md)._\n\n");
    StringBuilder table = new StringBuilder();
    appendMatrix(table, matrix);
    block.append(table.toString().replaceFirst("^\n## Matrice\n\n", "")).append('\n');
    return document.substring(0, start) + block + document.substring(end);
  }

  static String summaryCell(MatrixCell c) {
    return String.format(
        Locale.ROOT,
        "**%d/%d** invalides · perdus %d · absents après reprise %d · dup %d · ordre %d · "
            + "reprise max %.1f s",
        c.invalidRuns(),
        c.runs(),
        c.lostConfirmed(),
        c.missingAfterRecovery(),
        c.unexpectedDuplicates(),
        c.orderViolations(),
        c.maxRecoveryMs() / 1000);
  }

  static List<String> limits() {
    return List.of(
        "Une violation est une preuve : ses opérations sont dans l'historique archivé et `rmq-verify check` la retrouve.",
        "Une cellule propre ne prouve pas la correction : elle couvre ces plans, ces durées et ces répétitions.",
        "La graine reproduit le plan de fautes et la charge, pas le timing du broker ; les répétitions mesurent "
            + "la fréquence d'une anomalie.",
        "Le scénario `classic-maxlength-control` jette des messages confirmés exprès (`x-max-length` et "
            + "`drop-head`) : il montre que les checkers voient les pertes. Ce n'est pas un défaut de RabbitMQ.",
        "Trois nœuds sur une machine (Docker dans une VM). Le client joint chaque nœud via Toxiproxy, donc il "
            + "n'est pas coincé d'un seul côté d'une partition.",
        "Un publish sans confirm est indéterminé, jamais compté comme perdu. Voir docs/METHODOLOGY.md.");
  }
}
