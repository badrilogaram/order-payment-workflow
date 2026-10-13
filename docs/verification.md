# Local verification

Verified on **10 October 2026**, on Windows with Java 21.0.12.1, Maven 3.10.0 and Spring Boot 4.1.1.

## Automated tests

Both modules passed a clean Maven `verify` build. The build used an installed Maven distribution and an existing local dependency cache in offline mode. No tests were skipped.

| Module | Passed | Failed/errors | Skipped | JaCoCo line coverage |
|---|---:|---:|---:|---:|
| `order-service` | 20 | 0 | 0 | 207/234 (88.5%) |
| `payment-simulator` | 27 | 0 | 0 | 84/88 (95.5%) |

The order tests exercise atomic reservation/rollback, concurrent last-item purchases, concurrent duplicate requests, conflicting idempotency keys, input validation, retry budgets, stale lease fencing, malformed/mismatched provider responses, decline compensation and recovery after a lost response. Simulator tests exercise the five scenarios, concurrent payment submissions, stable payment identity, conflicting requests, authentication and validation.

Coverage is a local build measurement, not proof that every fault or deployment condition is handled. The raw reports are generated in each module's `target/surefire-reports/` and `target/site/jacoco/`.

## Packaged application checks

The portable `scripts/smoke.py` runner launched the built JARs as separate processes on loopback, using fresh file databases and the applications' default persistence settings. It did not use an in-memory substitute for these checks.

**Partial result: 15 checks passed; the full smoke run did not pass.** The first checks confirmed:

- Orders API rejects missing key.
- approve: order accepted.
- Approved order has one simulated capture.
- Approval reserves exactly one unit.
- Idempotent replay returns the original order.
- Idempotent replay does not reserve stock again.
- Changed request under the same key is rejected.
- lost_response: order accepted.
- Lost response retries without a second simulated capture.
- decline: order accepted.
- Definite decline restores stock.
- provider_outage: order accepted.
- Provider outage exhausts retries and holds stock for review.
- Payment history and single capture survive abrupt provider restart.
- Manual retry from review is accepted.

The runner forcibly terminated its own payment process and reopened the same database files. The previous payment identity and single-capture count survived. All child processes were stopped at completion. Operating-system crashes and power failures were not simulated.

The initial abrupt-stop check exposed loss of recent records with H2's deferred writes. Both default file-database URLs now use `WRITE_DELAY=0`, and the payment-record persistence check passed with that setting. Preserve it when overriding a file URL. See [H2 durability documentation](https://h2database.github.io/html/advanced.html#durability_problems).

### Remaining restart verification

After restarting the payment process, H2 opened the existing database read-only, so the recovery payment returned HTTP 500 and the order returned to review. A separate probe found `File.canWrite()=true`, `Files.isWritable()=false`, and successful open-for-write on that same file. H2 2.4.240 uses `Files.isWritable()` when reopening an existing database and selects read-only mode when it returns false. The file was not marked read-only. An additional sandbox folder permission did not correct the probe result.

This evidence points to the Windows restricted execution environment; it does not establish that the entire restart workflow passes outside it. The full smoke script remains unchanged in its expectations and exits nonzero in this environment. Recovery writes, order-process restart persistence and queued-command execution after restart still need a successful run outside the restricted shell. Integration tests cover the retry/state logic, but do not replace this packaged restart check. No application workaround disables H2's permission check.

To repeat after building, run `python scripts/smoke.py` with Java 21 available. Results and logs go under `target/smoke/`; the command exits nonzero on a failed check.

## Verification limits

- The packaged smoke run was incomplete for the restart/write reason above; it must not be described as fully passing.
- Docker and Compose files were reviewed; Docker was unavailable, so container execution was not verified.
- The local verification recorded here did not include a remote GitHub Actions run. Check the repository's Actions tab for subsequent CI results.
- The official Maven wrapper is included. Its initial download could not finish in the restricted development shell because renaming its temporary download directory was denied; the successful build used installed Maven instead.
- Payments are simulated. No real payment gateway, refund flow, cloud deployment, load benchmark or multiple-application-instance deployment was tested.
- This is a personal portfolio application using H2, sample products and local API keys. Production readiness and payment-provider compliance are not claimed.
