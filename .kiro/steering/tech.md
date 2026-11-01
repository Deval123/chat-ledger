# Tech steering

- Java 21, Spring Boot 3.x, Maven multi-module, PostgreSQL, Flyway migrations.
- UI: Thymeleaf with HTMX. No single-page application framework.
- Tests: JUnit 5, AssertJ, jqwik for property-based tests, Testcontainers for PostgreSQL.
- Package root: `dev.chatledger`. One module per responsibility; `ledger-core` has no web dependency.
- Prefer small classes, constructor injection, immutable value objects (records).
- Every public behavior has a test. Every requirement is referenced by at least one test or task.
- Commit messages: imperative, English, reference the task number. Commits carry only the author's own name.
- Do not add the `.kiro` directory to `.gitignore`.
