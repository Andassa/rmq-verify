# Security policy

## Supported versions

Security fixes are made on `main` and released in the next version. Only the latest release is
supported.

## Reporting a vulnerability

Please do **not** open a public issue. Use GitHub's private vulnerability reporting
("Security" tab → "Report a vulnerability") on
[Andassa/rmq-verify](https://github.com/Andassa/rmq-verify/security/advisories/new).

Include the affected version or commit, a description, reproduction steps and the impact you
expect. You will receive an acknowledgement within five working days and a status update at
least every two weeks until the issue is resolved. Coordinated disclosure: we agree on a
publication date once a fix is available; credit is given unless you prefer otherwise.

## Scope

In scope: the `rmq-verify` code, its build, its default deployment files under `deploy/` and the
CI workflows. Out of scope: vulnerabilities of RabbitMQ itself (report them to the RabbitMQ
team), and findings that require the attacker to already control the local Docker daemon.

The threat model is in [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md).
