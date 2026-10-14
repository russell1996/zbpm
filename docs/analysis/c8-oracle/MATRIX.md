# Conformance matrix — 8.9 docs | Zeebe 8.5 live | ours | verdict

Generated/appended by `node compare.js <scenario-id> --matrix` (WO-OPS-30).
Verdict: `match` / `docs!=8.5` / `ours differs`; F0 rows are `measured-8.5 (ours pending)`.

## _template — TEMPLATE — worker completion var reaches root; dump captures the final state

`Completion variables are written to the containing scope (root here) and visible to subsequent elements; the final dump job therefore carries result=42 and the start var greeting | greeting="camunda", result=42 | (pending) | measured-8.5 (ours pending)`
