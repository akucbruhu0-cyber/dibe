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
