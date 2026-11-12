# Data tools

AG Grid Community enhances the wish review table with local sorting, filtering,
keyboard navigation and selection. The table and its forms remain usable without
JavaScript or when the CDN fails. Stated wishes are never orders or purchases.

## Resources

The only external asset host is `cdn.jsdelivr.net`. Versions are exact:

| Tool | Resource |
|---|---|
| AG Grid Community 36.3.0 | `https://cdn.jsdelivr.net/npm/ag-grid-community@36.3.0/dist/ag-grid-community.min.noStyle.js` |
| AG Charts Community 14.3.0 | `https://cdn.jsdelivr.net/npm/ag-charts-community@14.3.0/dist/umd/ag-charts-community.min.js` |

Use `~{fragments/data-tools :: grid}` or `~{fragments/data-tools :: charts}` once
on a consuming page, before its deferred local script. Neither asset belongs in
the global layout. The Grid bundle registers Community modules; the current theme generates its
styles without legacy icon fonts; its global is `agGrid`. Charts exposes `agCharts.AgCharts.create`.
No framework, enterprise bundle, license key, integrated charts, enterprise
grouping or enterprise status bar is used. The count summary is computed from
distinct wish rows. Standalone chart consumers must supply code-computed data
and a server-rendered equivalent.

The workbench uses Community `createGrid`, client-side row data, sortable text
columns, multi-row selection, `getSelectedRows`, `forEachNode`,
`setGridOption`, `applyColumnState`, `deselectAll`, `getDisplayedRowCount`,
`isRowSelectable`, and `getRowId`. Wish content is plain text; custom renderers
must create DOM nodes and assign `textContent`, never return content as HTML.
External scripts are optional enhancements, never authorization boundaries.
All bulk mutations are checked again by the server and require CSRF tokens.

## Maker review

The proposal page embeds the workbench; the same table is available at
`GET /w/{token}/maker/proposal/wishes?run={runId}`. Both views retain the selected
immutable analysis run. Label and option filters pair the verdict with its
option; totals count distinct source wishes and never extracted quantities.
A machine gloss is a reading aid that may be inaccurate; it does not change
verdicts or counts. Language is unknown when no language metadata was collected.
Displayed quotes must be exact substrings of the original wish, including its
whitespace. Nonmatching quotes are unavailable; they are never rewritten to
look like original evidence.
Only flags with an explicit wish UUID are attached to a row. Batch-local numeric
indices cannot identify a wish after merging or reshuffling; these flags remain
visible in the run-level validation list.

`POST /w/{token}/maker/proposal/wishes/review` accepts `run`, `optionId`, repeated
`wishIds`, `verdict` (`COMPATIBLE` or `INCOMPATIBLE`), and `reason` (one to 500
characters after trimming). The server validates the whole selection before
recording any decision, deduplicates IDs, and records each decision through the
review service within one transaction. History retains its source, option,
reason, revision and time; effective labels and counts change without rewriting
the original readings. An unresolved second-reading disagreement remains marked
for attention until a decision for that wish and option is recorded.

The adjacent hide action delegates to the workspace service, also after checking
the entire workspace/run/option scope. New wishes outside the run remain visible
as unanalyzed rows and cannot be selected for that run's bulk actions. Hiding does
not rewrite an earlier analysis snapshot. Both actions support ordinary server
forms when scripts are unavailable.

The CSP adds only the two complete script URLs above to `script-src`. It does
not allow the CDN origin generally or change PayPal, connection, frame or image
permissions. Grid theme styles are generated under the existing inline
style policy; there is no external stylesheet or font request.

## Evidence filter

A chart can filter the workbench with:

```javascript
window.dispatchEvent(new CustomEvent('wishbatch:filter-wishes', {
    detail: { wishIds: ['a-wish-uuid', 'another-wish-uuid'] }
}));
```

The workbench intersects these IDs with its source rows, shows an active evidence
filter with a clear button, and combines it with the option, label and text
filters. An empty list matches no rows. Clearing the evidence filter preserves
the other filters. Selection is cleared for rows no longer visible; bulk actions
never silently act on filtered-out rows.

## Sources

Checked against official vanilla JavaScript documentation:

- [Grid installation](https://www.ag-grid.com/javascript-data-grid/installation/)
- [Community modules](https://www.ag-grid.com/javascript-data-grid/modules/)
- [Multiple row selection](https://www.ag-grid.com/javascript-data-grid/row-selection-multi-row/)
- [Charts installation](https://www.ag-grid.com/charts/javascript/installation/)
