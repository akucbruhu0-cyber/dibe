# Security Policy

## Reporting a vulnerability

Please report security issues privately through GitHub's **Security → Report a vulnerability** on this repository. Do not open a public issue for a security report.

Include what you found, how to reproduce it, and the DIBE version (Settings → About). I will reply with next steps.

If the problem is in code DIBE shares with [Vibe](https://github.com/thewh1teagle/vibe), please report it to Vibe as well. If it is in a third-party dependency, report it to that project's maintainers too.

## Supported versions

Only the latest commit on `main` receives security fixes.

## Security-relevant design

- The local transcription API listens on `127.0.0.1` only, requires a bearer token generated at every server start, and rejects any request that carries a browser `Origin` header.
- The token and port are written to `server.json` in the app data folder with owner-only permissions (`0600`) and removed when the server stops.
- DIBE sends no analytics and performs no automatic updates.

## Known dependency advisories

Checked with `cargo audit` and `pnpm audit --prod` on 2026-10-28. The npm runtime dependencies have no known advisories. These Rust advisories remain because fixing them needs a new major version from the upstream crate that pulls them in:

| Crate | Advisories | Pulled in by | Why DIBE is not exposed |
|---|---|---|---|
| `quick-xml` 0.37–0.39 | RUSTSEC-2026-0194, -0195 (slow or memory-heavy parsing of hostile XML) | `plist` (Tauri, `netdev`) | Only parses property lists from the app bundle and macOS, never XML from the network or the user |
| `zip` 4.6.1 | RUSTSEC-2026-0367, -0368, -0369 (unsafe permissions and symlinks on extraction) | `tauri-plugin-updater` | The updater has no endpoints, so it never downloads or extracts an archive |
| `zip` 3.0.0 (server) | Same three | `utoipa-swagger-ui` build script | Used only at build time to unpack the bundled Swagger UI; not in the shipped app |
