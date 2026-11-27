# Contributor notes

- Use JDK 21 and Maven; run the full suite with `mvn test` when making changes.
- JavaFX UI tests use TestFX and need a real window under a display server plus window
  manager. CI runs them on Ubuntu 24.04 in Xvfb with Openbox; do not skip UI tests.
- Keep database and application data behavior covered by the existing tests. Windows
  packaging is built with `powershell -File scripts/package-windows.ps1 -Type msi`;
  the installed app is smoke-tested with `scripts/smoke-windows.ps1`.
- CI artifacts contain Maven Surefire/dependency reports and the Windows MSI. CI does
  not publish releases.
