# Daymark contributor notes

## Build and test

- Use JDK 21 from the repository root. Check `java -version` and `mvn --version`.
  Confirm Maven reports Java 21 when multiple JDKs are installed.
- Launch the desktop app with `mvn javafx:run`. Run the full JUnit 5 suite after
  code, test, or build changes with `mvn --batch-mode --no-transfer-progress test`.
  For a focused persistence run in PowerShell, use
  `mvn '-Dtest=DatabaseManagerTest,DatabaseStartupTest,SQLiteTaskRepositoryTest' test`.
- Use `Clock.fixed` and `InMemoryTaskRepository` for service tests that depend on
  time and storage. TestFX requires a display and a window manager. CI uses Xvfb
  and Openbox on Ubuntu 24.04. On Windows, use an interactive desktop session.
  Check `target/surefire-reports/` and diagnose display or focus failures.
  Never disable tests merely to make a build pass.

## Architecture and code style

- `app` wires the layers together. `ui` handles JavaFX interactions and calls
  `TaskService`. Keep task validation and business rules in `service`, and access
  storage there through `TaskRepository`. Keep JDBC and migrations in
  `persistence`, and CSV and iCalendar conversion in `io`.
- Keep `domain` independent of JavaFX and SQLite. Update immutable `Task` records
  with their existing `with...` methods. Match the surrounding Java style with
  four-space indentation and descriptive names. Bind SQL values with
  `PreparedStatement` and close owned JDBC resources with try-with-resources.

## Database safety

- Every database-sensitive test must use synthetic SQLite fixtures in
  `:memory:` or JUnit `@TempDir`. Use an isolated child JVM with a temporary home,
  app-data directory, and working directory to test default startup. Never open
  or modify the live database in tests or smoke checks.
- Keep the platform data locations in the README, including
  `%LOCALAPPDATA%\Daymark` on Windows. An existing app-data database wins even when
  empty. Preserve the legacy source, snapshot through SQLite to include committed
  WAL data, validate before publishing, and protect any existing destination.
  Open existing and migrated files without create mode. Cover failure recovery
  and reopening from a different working directory.

## Git and CI

- Run `git status --short --branch` before editing and preserve unrelated changes.
  Check `git diff --check` and review the staged diff before any authorized commit.
  Commit, push, or publish releases only when requested.
- Validate `.github/workflows/ci.yml` edits with `actionlint`. Keep repository
  permissions read-only and retain Surefire, dependency, and display diagnostics.
- On Windows, build the MSI with `./scripts/package-windows.ps1 -Type msi` and
  smoke-test the installed launcher with `./scripts/smoke-windows.ps1 -Launcher $launcher`.
  CI uploads the installer after the smoke check succeeds.

## Parallel agent work

- Automatically parallelize independent work when it saves time or usage. Use the
  same model for comparable work and a lower-cost model for bounded checks.
  Assign distinct files or build directories and run one TestFX suite per desktop.
- Consult a higher-capability model for a focused second opinion on data-loss risks,
  difficult design choices, or persistent failures. The primary agent integrates
  changes, reviews the diff, and verifies the combined result.
