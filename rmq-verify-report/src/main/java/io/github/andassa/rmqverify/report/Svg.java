package io.github.andassa.rmqverify.report;

import java.util.List;
import java.util.Locale;

/**
 * Minimal SVG charts rendered as strings, so that reports are a single offline file: no JavaScript,
 * no CDN, no fonts.
 */
public final class Svg {

  private static final int WIDTH = 860;
  private static final int HEIGHT = 240;
  private static final int LEFT = 56;
  private static final int RIGHT = 16;
  private static final int TOP = 28;
  private static final int BOTTOM = 32;

  /**
   * One plotted series.
   *
   * @param label legend text
   * @param color CSS color
   * @param values one value per second
   */
  public record Line(String label, String color, List<Double> values) {
    /** Copies the values. */
    public Line {
      values = List.copyOf(values);
    }

    /**
     * Builds a line from primitive values.
     *
     * @param label legend text
     * @param color CSS color
     * @param values one value per second
     * @return the line
     */
    public static Line of(String label, String color, double[] values) {
      return new Line(label, color, java.util.Arrays.stream(values).boxed().toList());
    }

    /**
     * Builds a line from counts.
     *
     * @param label legend text
     * @param color CSS color
     * @param values one count per second
     * @return the line
     */
    public static Line of(String label, String color, long[] values) {
      return new Line(
          label, color, java.util.Arrays.stream(values).mapToDouble(v -> v).boxed().toList());
    }
  }

  /** Embedded in every SVG so that the files also render standalone (report.md, browsers). */
  private static final String STYLE =
      "<style>.title{font:600 13px sans-serif;fill:#212529}.axis,.legend{font:11px sans-serif;fill:#495057}"
          + ".axis-line{stroke:#868e96}.grid{stroke:#e9ecef}.fault{fill:#ffa8a8;opacity:.35}.bar{fill:#4c6ef5}"
          + "</style>";

  private Svg() {}

  /**
   * Renders a line chart over time, with fault windows shaded.
   *
   * @param title chart title
   * @param unit unit of the y axis
   * @param seconds length of the x axis
   * @param lines series to plot
   * @param faults windows to shade
   * @return an {@code <svg>} element
   */
  public static String lineChart(
      String title, String unit, int seconds, List<Line> lines, List<Series.Window> faults) {
    double max = 1;
    for (Line line : lines) {
      for (double v : line.values()) {
        max = Double.isNaN(v) ? max : Math.max(max, v);
      }
    }
    max = niceCeiling(max);
    int span = Math.max(seconds, 1);
    StringBuilder svg = open(title);
    for (Series.Window w : faults) {
      double x1 = x(w.startSeconds(), span);
      double x2 = Math.max(x(w.endSeconds(), span), x1 + 1);
      svg.append(
          String.format(
              Locale.ROOT,
              "<rect class=\"fault\" x=\"%.1f\" y=\"%d\" width=\"%.1f\" height=\"%d\"><title>%s</title></rect>",
              x1,
              TOP,
              x2 - x1,
              HEIGHT - TOP - BOTTOM,
              Html.escape("fault #" + w.index() + " " + w.nemesis())));
    }
    axes(svg, max, unit, span);
    int legendX = LEFT;
    for (Line line : lines) {
      for (String points : segments(line.values(), span, max)) {
        svg.append(
            String.format(
                Locale.ROOT,
                "<polyline fill=\"none\" stroke=\"%s\" stroke-width=\"1.6\" points=\"%s\"/>",
                Html.escape(line.color()),
                points));
      }
      svg.append(
          String.format(
              Locale.ROOT,
              "<rect x=\"%d\" y=\"%d\" width=\"10\" height=\"10\" fill=\"%s\"/>",
              legendX,
              HEIGHT - 12,
              Html.escape(line.color())));
      svg.append(
          String.format(
              Locale.ROOT,
              "<text x=\"%d\" y=\"%d\" class=\"legend\">%s</text>",
              legendX + 14,
              HEIGHT - 3,
              Html.escape(line.label())));
      legendX += 18 + 7 * line.label().length() + 20;
    }
    return svg.append("</svg>").toString();
  }

  /**
   * Renders a horizontal bar per value.
   *
   * @param title chart title
   * @param unit unit of the values
   * @param labels one label per bar
   * @param values one value per bar
   * @return an {@code <svg>} element
   */
  public static String barChart(String title, String unit, List<String> labels, double[] values) {
    int rowHeight = 22;
    int height = TOP + rowHeight * Math.max(labels.size(), 1) + 12;
    double max = 1;
    for (double v : values) {
      max = Math.max(max, v);
    }
    StringBuilder svg =
        new StringBuilder(
            String.format(
                Locale.ROOT,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" role=\"img\" aria-label=\"%s\">",
                WIDTH,
                height,
                Html.escape(title)));
    svg.append(STYLE);
    svg.append(
        String.format(
            Locale.ROOT,
            "<text x=\"%d\" y=\"18\" class=\"title\">%s</text>",
            LEFT,
            Html.escape(title)));
    int labelWidth = 220;
    for (int i = 0; i < labels.size(); i++) {
      int top = TOP + i * rowHeight;
      double w = (WIDTH - labelWidth - 120) * values[i] / max;
      svg.append(
          String.format(
              Locale.ROOT,
              "<text x=\"%d\" y=\"%d\" class=\"axis\">%s</text>",
              8,
              top + 14,
              Html.escape(labels.get(i))));
      svg.append(
          String.format(
              Locale.ROOT,
              "<rect class=\"bar\" x=\"%d\" y=\"%d\" width=\"%.1f\" height=\"14\"/>",
              labelWidth,
              top + 3,
              Math.max(w, 1)));
      svg.append(
          String.format(
              Locale.ROOT,
              "<text x=\"%.1f\" y=\"%d\" class=\"axis\">%s</text>",
              labelWidth + Math.max(w, 1) + 6,
              top + 14,
              Html.escape(String.format(Locale.ROOT, "%.0f %s", values[i], unit))));
    }
    return svg.append("</svg>").toString();
  }

  private static StringBuilder open(String title) {
    StringBuilder svg =
        new StringBuilder(
            String.format(
                Locale.ROOT,
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" role=\"img\" aria-label=\"%s\">",
                WIDTH,
                HEIGHT,
                Html.escape(title)));
    svg.append(STYLE);
    svg.append(
        String.format(
            Locale.ROOT,
            "<text x=\"%d\" y=\"18\" class=\"title\">%s</text>",
            LEFT,
            Html.escape(title)));
    return svg;
  }

  private static void axes(StringBuilder svg, double max, String unit, int span) {
    int bottom = HEIGHT - BOTTOM;
    svg.append(
        String.format(
            Locale.ROOT,
            "<line class=\"axis-line\" x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\"/>",
            LEFT,
            bottom,
            WIDTH - RIGHT,
            bottom));
    svg.append(
        String.format(
            Locale.ROOT,
            "<line class=\"axis-line\" x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\"/>",
            LEFT,
            TOP,
            LEFT,
            bottom));
    for (int i = 0; i <= 4; i++) {
      double value = max * i / 4;
      double y = y(value, max);
      svg.append(
          String.format(
              Locale.ROOT,
              "<text x=\"%d\" y=\"%.1f\" class=\"axis\" text-anchor=\"end\">%s</text>",
              LEFT - 6,
              y + 4,
              Html.escape(String.format(Locale.ROOT, "%.0f", value))));
      svg.append(
          String.format(
              Locale.ROOT,
              "<line class=\"grid\" x1=\"%d\" y1=\"%.1f\" x2=\"%d\" y2=\"%.1f\"/>",
              LEFT,
              y,
              WIDTH - RIGHT,
              y));
    }
    svg.append(
        String.format(
            Locale.ROOT,
            "<text x=\"%d\" y=\"%d\" class=\"axis\">%s</text>",
            4,
            TOP - 6,
            Html.escape(unit)));
    int step = Math.max(1, (int) niceCeiling(span / 8.0));
    for (int s = 0; s <= span; s += step) {
      svg.append(
          String.format(
              Locale.ROOT,
              "<text x=\"%.1f\" y=\"%d\" class=\"axis\" text-anchor=\"middle\">%ds</text>",
              x(s, span),
              bottom + 14,
              s));
    }
  }

  /** Splits a series into polylines at missing values (NaN), which are gaps, not zeros. */
  private static List<String> segments(List<Double> values, int span, double max) {
    List<String> segments = new java.util.ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      double value = values.get(i);
      if (Double.isNaN(value)) {
        if (current.length() > 0) {
          segments.add(current.toString().trim());
          current.setLength(0);
        }
        continue;
      }
      current.append(String.format(Locale.ROOT, "%.1f,%.1f ", x(i + 0.5, span), y(value, max)));
    }
    if (current.length() > 0) {
      segments.add(current.toString().trim());
    }
    return segments;
  }

  static double niceCeiling(double value) {
    double magnitude = Math.pow(10, Math.floor(Math.log10(value)));
    for (double factor : new double[] {1, 2, 5, 10}) {
      if (factor * magnitude >= value) {
        return factor * magnitude;
      }
    }
    return 10 * magnitude;
  }

  private static double x(double second, int span) {
    return LEFT + (WIDTH - LEFT - RIGHT) * second / span;
  }

  private static double y(double value, double max) {
    return HEIGHT - BOTTOM - (HEIGHT - TOP - BOTTOM) * value / max;
  }
}
