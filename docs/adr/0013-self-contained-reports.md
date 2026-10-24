# ADR-0013: Self-contained reports with server-side SVG

- Status: accepted
- Date: 2026-10-24

## Context

Reports are archived as CI artefacts, attached to bug reports and opened years later, often
offline. Chart libraries loaded from a CDN break that, add JavaScript to review and leak the
reader's IP address to a third party.

## Decision

- Each run produces `report.json` (the machine-readable source of truth), `report.md` (charts as
  sibling `.svg` files) and `report.html` (one file: inline CSS, inline SVG, no JavaScript, no
  external URL besides the SVG namespace).
- Charts are generated in Java (`Svg`): throughput with fault windows shaded, publish-to-confirm
  latency p50/p99 (seconds without any confirm are gaps, not zeros), and recovery time per fault.
- Every string from data is escaped: `Html.escape` in HTML and SVG, `RunReport.text` in Markdown
  (GitHub renders inline HTML in Markdown).
- `RESULTS.md` and the matrix are computed from the `report.json` files only, so they can be
  regenerated from archived results without rerunning anything.
- Each report states what is proven (violations with evidence), what is indeterminate (`UNKNOWN`
  verdicts, publishes without confirm) and the limits of the experiment.

## Consequences

- No dependency for charting; the HTML report is a few hundred kilobytes.
- Charts are deliberately simple (no zoom, no tooltips beyond SVG `<title>`).
