# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in this project, please email
jun784@gmail.com with the subject line `[SECURITY] cloud-itonami-isic-900`.

Please include:
- Description of the vulnerability
- Steps to reproduce (if applicable)
- Potential impact
- Suggested fix (if you have one)

We ask that you do not publicly disclose the vulnerability until we have had
a chance to address it.

## Security Considerations

This project implements a governance layer that enforces three hard, permanent
checks on all proposals:

1. **Resident/venue record verification** — all targets must be verified before
   any action
2. **Effect validation** — only `:propose` effects are allowed
3. **Scope exclusion** — proposals outside the closed allowlist are permanently blocked

These checks are not mere defaults or best-practices; they are structural
requirements of the system. No amount of user approval or confidence can
override them.

## Testing

Security-critical checks are tested directly in the test suite. All contributions
that touch the governor or hard checks must include corresponding test coverage.

```bash
clojure -M:test
```

## Dependencies

Dependencies are managed via `deps.edn`. Keep dependencies up to date and review
security advisories regularly.
