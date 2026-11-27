# Supply chain

| Control | Where | What it gives |
|---|---|---|
| **CodeQL** (`security-extended` queries) | `.github/workflows/codeql.yml`, on pull requests, on main and weekly | Static analysis for common vulnerability classes in the Java code |
| **Dependency review** | `.github/workflows/dependency-review.yml` | A pull request that adds a dependency with a known high-severity vulnerability fails |
| **Dependabot** | `.github/dependabot.yml` | Weekly updates of Maven dependencies and workflow actions, as pull requests that run the full build |
| **Pinned toolchain** | `pom.xml` (Spring Boot BOM, explicit plugin and library versions); the enforcer requires Java 21 and convergent dependencies | The same inputs give the same build |
| **SBOM** | `./mvnw -Psbom package` writes `target/lob-engine-bom.json` (CycloneDX 1.6) | Every component, with versions, of the build |
| **Signed provenance** | `.github/workflows/release.yml`, on a `v*` tag | GitHub attestations (Sigstore) that these files were built by this workflow from this commit (SLSA build provenance), plus the SBOM attested to the jar |

## Verifying a release

```bash
gh attestation verify api-0.1.0.jar --repo Ahmed-Berrada/Order_Book
gh attestation verify benchmarks.jar --repo Ahmed-Berrada/Order_Book
sha256sum -c SHA256SUMS
```

## What it does not give

No reproducible-build proof (a rebuild is not bit-for-bit identical), no signed commits requirement, no
protection against a compromised maintainer account, and no vulnerability scan of a container image (there is
none yet, phase 9). The attestation proves *where* an artifact came from, not that the code is free of flaws.
