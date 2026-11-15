# People search implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan inline.

**Goal:** Search Neon users by username or fullname from the existing People section.

**Architecture:** Add public GET /api/v1/users?q=... using the current UserController, UserService and JPA repository. Return at most 20 public summaries. SearchPage requests suggestions after 300 ms and keeps submitted results separate. No new dependencies or schema changes.

**Tech stack:** Java 21, Spring Boot/JPA, React, Axios, Vite.

**Spec:** The user's People selection and the preceding proposal in this chat.

## Constraints and review focus

- Search username/fullname case-insensitively; blank queries return an empty list; reject over 2000 characters.
- Treat `%` and `_` literally; use query parameters rather than SQL interpolation.
- Return only id, username, fullname and userAvatarUrl; exclude birthday and password data.
- Abort stale requests; typing must not replace results for the last submitted query.
- People failures must not prevent semantic post results from loading.

## Tasks

- [x] Add failing HTTP tests for public access, blank input and oversized input.
- [x] Implement the bounded repository query, public DTO, service, controller and GET security rule.
- [x] Test mapping, normalization and public response fields.
- [x] Replace SearchPage's full-user download with debounced suggestions and independent submitted results.
- [x] Run relevant backend tests, frontend lint/build, and a read-only runtime check if the local server reloads.
- [x] Record verification results and any remaining runtime limitation. Do not commit or push.

## Verification

- Two initial HTTP tests failed with 401 before the endpoint/security rule was added.
- 31 related backend tests passed; includes three People HTTP tests.
- ESLint for SearchPage passed. Vite production build passed with the existing bundle-size warning.
- Live GETs through localhost:5173 returned 200: DEMO19 matched one user, demo returned the maximum 20, thi matched one, percent and blank queries returned no users. Responses contained exactly the four public fields.
- Browser UI verification could not start because the CUA runtime failed to initialize its kernel assets. Request cancellation and separate suggestion/submitted state were reviewed in source; no browser behavior claim is made.
- API documentation updated. No database writes, Jina requests, commits or pushes were performed for verification.
