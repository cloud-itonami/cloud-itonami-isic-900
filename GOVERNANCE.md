# Governance

This project is governed by the cloud-itonami fleet principles outlined in
ADR-2607121000 and sibling ADRs (ADR-2607152700, ADR-2607153800).

## Three HARD Governor Checks (Permanent, Un-Overridable)

1. **Venue/booking record unverified**: the target venue or booking must exist
   in the store AND be independently `:registered?`/`:verified?` before any
   proposal may commit or escalate.

2. **Effect is :propose**: any proposal whose `:effect` is not `:propose` is
   rejected outright. This is a hard check, not a confidence threshold.

3. **Scope exclusion**: any proposal touching creative/artistic decisions,
   casting, programming/curatorial choices, pricing-policy decisions, or
   safety-authority overrides is permanently blocked.

## Allowed Operations (Closed Allowlist)

- `:schedule-venue-booking` — venue/rehearsal-space booking scheduling
- `:coordinate-performer-schedule-proposal` — administrative schedule coordination (call times, rehearsal logistics)
- `:coordinate-supply-request` — non-creative consumables (office/FOH supplies, cleaning)
- `:coordinate-ticketing-logistics` — box-office/ticketing operational logistics
- `:flag-safety-concern` — venue/facility safety concerns (ALWAYS escalates)

## Scope Exclusions (Never In Scope)

- Creative/artistic decisions or content
- Casting decisions
- Programming/curatorial choices
- Pricing/revenue policy
- Safety-authority overrides or mandatory reporting

## Escalation Policy

The following always escalate to human approval:

- `:flag-safety-concern` (regardless of confidence)
- Proposals with low advisor confidence (< 0.6)

## Rollout Phases

**Phase 0**: Read-only access only.
**Phase 1**: Venue booking + performer schedule proposals (approval-gated).
**Phase 2**: + Supply coordination + ticketing logistics (approval-gated).
**Phase 3**: Auto-commits clean, high-confidence proposals (safety concerns always escalate).
