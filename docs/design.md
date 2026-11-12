# Design system

Use `/styleguide` as the rendered component reference. Its content and counts
are synthetic. The system uses local CSS, system fonts, and original inline
SVG; no external assets or scripts are needed.

## Page shell

Render pages with the shared Thymeleaf layout:

```html
<html lang="en" xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout/base :: page('Page title', ~{::main})}">
<body>
<main id="main" class="shell">
    <!-- Page content -->
</main>
</body>
</html>
```

`LayoutModel` supplies `productName` to controllers in `app.web`, reading
`PRODUCT_NAME` with a `Wishbatch` default. Use that model value for the name.
The layout supplies the title, skip link, header, sandbox notice, stylesheet,
and footer. Keep one `main` with `id="main"` and one `h1` per page.

## Tokens

All tokens live in `static/css/product.css`. Dark mode follows the operating
system through `prefers-color-scheme`. Use token pairs, not hardcoded colors.

| Tokens | Use |
|---|---|
| `--canvas`, `--surface`, `--surface-soft` | Page, cards, quiet regions |
| `--ink`, `--muted` | Main and supporting text on neutral surfaces |
| `--accent`, `--on-accent`, `--accent-soft` | Primary action and completed payment |
| `--hold`, `--hold-soft` | Authorization and pending states |
| `--info`, `--info-soft` | Informational notice |
| `--danger`, `--danger-soft` | Error and destructive action |
| `--line`, `--control-line`, `--focus` | Dividers, input boundaries, keyboard focus |
| `--space-1/2/3/4/6/8/12/16/24` | 4/8/12/16/24/32/48/64/96px spacing |
| `--radius`, `--radius-small` | 16px panels and 8px controls |

Typography uses `--font`, a system sans-serif stack. Headings scale with the
viewport. Body text is 16px with 1.6 line height. Use `.eyebrow` sparingly for
short section labels. `.shell` limits content width; grids stack on phones.
Use `.two-column` for paired panels and `.card` for a contained surface.

## Components

- Give a screen one primary action: `.button.button-primary`. Secondary and
  destructive actions use `.button-secondary` and `.button-danger`. Use a
  button for mutations and an anchor for navigation. Disable unavailable
  actions with the native `disabled` attribute.
- Wrap labeled inputs in `.field`. Connect `.field-help` or `.field-error`
  with `aria-describedby`; set `aria-invalid="true"` for invalid inputs. Use
  `.checkbox-field` for a checkbox and its text. Quantity starts at 1.
- Wrap tables in `.table-scroll` with `tabindex="0"`, `role="region"`, and an
  accessible label. Supply a caption and scoped header cells.
- Use `.badge` with `.badge-neutral`, `.badge-hold`, `.badge-pending`,
  `.badge-success`, or `.badge-error`. Always include a textual state; color
  alone carries no meaning. Authorized minimum and funded are distinct states.
- Use `.notice` plus `.notice-info`, `.notice-warning`, or `.notice-error`.
  Use `role="status"` for live updates and `role="alert"` for a new actionable
  error. Preserve the last valid result when analysis fails. Empty states use
  `.empty-state` with an explanation and a relevant next action.
- A timeline is `ol.timeline` with one `li` per event, a `time` carrying an ISO
  `datetime`, a short `strong` label, and details. Payment events include the
  endpoint, provider ID, status, and time. Render server-provided facts only.

Reusable fragments in `fragments/components.html`:

```html
<div th:replace="~{fragments/components :: counts(interest, authorized, paid, minimum)}"></div>
<div th:replace="~{fragments/components :: steps(3)}"></div>
<div th:replace="~{fragments/components :: evidence(quote, sourceLabel, sourceUrl)}"></div>
<div th:replace="~{fragments/components :: moneyTerms}"></div>
```

Pass model expressions such as `${counts.interest}` instead of bare names
when using dynamic values. Counts are nonnegative server-computed units and
`minimum` is positive. The interest count never has progress. Authorized and
paid units each have a separate native progress element and an explicit
numerator and minimum. Browsers cap the visual fill at the minimum; the full
count stays visible. Templates never decide whether production can proceed.

The step rail takes a 1-based current step: Options, Wishes, Proposal, Offer,
Campaign, Settlement. It marks `aria-current="step"` without assuming routes.
Evidence quotes must link directly to their source. Only provide trusted,
application-owned source URLs; the quote and source label are escaped text.

## Money and edge states

Use message keys `pledge.AUTHORIZED`, `pledge.CAPTURED`,
`pledge.CAPTURE_PENDING`, `pledge.CAPTURE_FAILED`, `pledge.VOIDED`,
`pledge.REFUNDED`, and `pledge.REFUND_PENDING` for pledge sentences. Present the
campaign result separately. Show the `moneyTerms` fragment, backed by
`money.term.1` through `money.term.6`, before the PayPal action.

Do not describe wishes or holds as completed payments. Explain release and
refund timing without promising immediate availability. The maker chooses
the offer and delivery terms; AI groups wishes. Production requires enough
completed payments, and capture can fail.

Cancelled approval must explain that no pledge counted and allow a retry.
When capacity or closing prevents a pledge, display the server's hold-release
outcome. An ended offer shows its result and has no pledge action. Show
missing wishes as an empty state, and keep prior evidence visible alongside
an analysis error. Do not add urgency beyond the actual server deadline.
