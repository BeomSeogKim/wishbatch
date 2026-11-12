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
