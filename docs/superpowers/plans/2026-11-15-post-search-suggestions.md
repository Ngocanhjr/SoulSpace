# Post search suggestions implementation plan

> **For agentic workers:** Execute inline with superpowers:executing-plans.

**Goal:** Show up to 8 semantic post suggestions while typing and up to 50 posts on Enter, as confirmed by the user.

**Architecture:** Extend GET /api/v1/search with a bounded limit parameter (default/max 50). SearchPage requests 8 suggestions after a 600 ms pause while focused, cancels stale requests and keeps suggestions separate from submitted results. People search remains available alongside Posts.

**Spec:** The user's confirmed choice of Posts in this chat.

**Constraints:** Reuse Jina and PostgreSQL search; no new dependencies/schema changes; preserve public-content/hash filters and authentication. Suggestion calls must not fire after submitting, clearing or navigating away. No unbounded result lists.

## Tasks

- [x] Add a failing HTTP test for invalid result limits before external calls.
- [x] Add the controller limit and bind it in the repository SQL; update callers/tests.
- [x] Add independent post suggestions and a shared post result renderer.
- [x] Verify backend tests, frontend lint/build and query parameter binding without calling Jina.
- [x] Update API/search documentation and record verification limits. Do not commit or push.

## Verification

- Invalid-limit HTTP test failed with 200 before implementation, then passed with 400 for 0, 51 and non-numeric input.
- 33 related backend tests passed, including suggestion limit 8 and default limit 50 binding through MockMvc.
- ESLint for SearchPage and Vite production build passed. The existing bundle-size warning remains.
- Reviewed all Java callers of SearchRepository.search and updated them to pass the bounded limit.
- No live Jina request or browser UI verification was performed for this extension. The browser tool failed to initialize during the preceding People task.
