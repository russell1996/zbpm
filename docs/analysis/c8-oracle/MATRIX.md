# Conformance matrix — 8.9 docs | Zeebe 8.5 live | ours | verdict

Generated/appended by `node compare.js <scenario-id> --matrix` (WO-OPS-30).
Verdict: `match` / `docs!=8.5` / `ours differs`; F0 rows are `measured-8.5 (ours pending)`.

## _template — TEMPLATE — worker completion var reaches root; dump captures the final state

`Completion variables are written to the containing scope (root here) and visible to subsequent elements; the final dump job therefore carries result=42 and the start var greeting | greeting="camunda", result=42 | (pending) | measured-8.5 (ours pending)`

## a11-errend-unhandled — F-01 — error end event WITHOUT any error boundary / event subprocess: what does Zeebe 8.5 do?

`8.9 docs: a BPMN error that is not caught by a boundary event or error event sub-process raises an incident on the element that threw the error; the instance stays alive (the scope is not terminated) so an operator can resolve/retry the incident | incident raised (+1, pending +1) | ошибка не поймана → элемент error-end остаётся в ERROR, на него поднимается ОТКРЫТЫЙ инцидент, инстанс жив (completed_at пуст), resolve ПЕРЕИСПОЛНЯЕТ элемент (новая попытка → снова ERROR + новый открытый инцидент) — не тихое закрытие | measured-8.5 (ours = match)`

## a11-fork-scope-boundary — F-09 — parallel gateway INSIDE an embedded subprocess; the error end in one branch must be caught by the error boundary on the ENCLOSING subprocess

`8.9 docs: a BPMN error thrown inside a scope propagates to the enclosing scope; an error boundary event on the enclosing embedded sub-process catches it, the sub-process scope is cancelled and the boundary's outgoing flow is taken | no incident, a11AfterBoundary=afterBoundary, a11Sibling=none | граница объемлющего subprocess ЛОВИТ ошибку из ветки форка: инцидентов 0, контейнер подпроцесса CANCELLED, сестринская ветка не активируется, поток границы продолжен (job после границы создан) | measured-8.5 (ours = match)`
