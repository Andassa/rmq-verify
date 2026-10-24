package io.github.andassa.rmqverify.report;

/**
 * Inline stylesheet shared by the HTML reports; inlined so that each report is one offline file.
 */
final class HtmlStyle {

  static final String CSS =
      """
      body{font:14px/1.5 -apple-system,Segoe UI,Helvetica,Arial,sans-serif;color:#212529;background:#f8f9fa;margin:0}
      main{max-width:960px;margin:0 auto;padding:24px;background:#fff}
      h1{font-size:22px}h2{font-size:18px;margin-top:28px;border-bottom:1px solid #dee2e6}h3{font-size:15px}
      table{border-collapse:collapse;width:100%;margin:8px 0}th,td{border:1px solid #dee2e6;padding:4px 8px;text-align:left}
      th{background:#f1f3f5}.valid{color:#2b8a3e;font-weight:600}.invalid{color:#c92a2a;font-weight:600}
      .unknown{color:#e67700;font-weight:600}.note{color:#495057}.overall{font-size:18px}
      figure{margin:12px 0}svg{width:100%;height:auto;background:#fff}
      svg .title{font-size:13px;font-weight:600;fill:#212529}svg .axis,svg .legend{font-size:11px;fill:#495057}
      svg .axis-line{stroke:#868e96}svg .grid{stroke:#e9ecef}svg .fault{fill:#ffa8a8;opacity:.35}
      svg .bar{fill:#4c6ef5}
      """;

  private HtmlStyle() {}
}
