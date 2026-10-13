# OrderFlow

A Java 21 / Spring Boot order and simulated payment workflow that demonstrates what happens when an order is accepted but its payment response fails. Two independently running applications keep separate databases and communicate over HTTP.

The useful demo is a **lost payment response**: the simulator records a capture and returns an error; the order worker retries with the same idempotency key, receives the original payment ID, and confirms the order with one simulated capture.

## What it demonstrates

- Create orders with server-side prices, stock reservation and request idempotency.
- Commit an order, its inventory reservation and a durable payment command in one database transaction.
- Process commands asynchronously with leases, bounded retries and recovery after a worker restart.
- Preserve uncertain outcomes for review instead of releasing stock while a payment may have succeeded.
- Restore inventory once after a definite payment decline.
- Inspect order state, attempts, simulated captures and operational metrics.

This is an original personal portfolio project. Payments use a deterministic simulator and sample products. No real money or employer code/data is involved.

## Stack and layout

| Part | Implementation |
|---|---|
| Runtime | Java 21, Spring Boot 4.1.1, Maven wrapper |
| Order API and worker | Spring MVC, JDBC transactions, H2 |
| Payment simulator | Separate Spring Boot application and H2 database |
| Verification | JUnit, Spring Boot tests, JaCoCo |
| Operations | Actuator, order-service Prometheus endpoint, Docker Compose |

```text
order-service/       Order API, stock reservation, durable command worker
payment-simulator/   Idempotent simulated payment API and failure scenarios
docs/                Architecture and interview walkthrough
compose.yaml         Two containers with separate persistent volumes
```

## Build and run

Install JDK 21 and set `JAVA_HOME`. The included Maven wrapper downloads its pinned Maven distribution and dependencies on the first build.

From this directory in PowerShell:

```powershell
.\mvnw.cmd -B -ntp verify
```

Start these in **two separate terminals**, both from this directory:

```powershell
java -jar payment-simulator/target/payment-simulator-0.1.0.jar
```

```powershell
java -jar order-service/target/order-service-0.1.0.jar
```

On macOS/Linux, use `chmod +x mvnw` followed by `./mvnw -B -ntp verify`; the Java commands are the same. Both applications bind to loopback by default. Wait until these health checks return `UP`:

```powershell
Invoke-RestMethod http://127.0.0.1:8091/actuator/health
Invoke-RestMethod http://127.0.0.1:8090/actuator/health
```

Local database files are kept under `data/` with separate names. Restarting an application keeps orders, inventory, pending commands and simulated payments.

## Five-minute demo

Use a third PowerShell terminal. Each run creates a new key so the demo can be repeated against an existing database.

### 1. Create an order whose first payment response is lost

```powershell
$orderHeaders = @{ 'X-API-Key' = 'local-order-key' }
$paymentHeaders = @{ 'X-API-Key' = 'local-payment-key' }
$requestHeaders = @{
    'X-API-Key' = 'local-order-key'
    'Idempotency-Key' = [guid]::NewGuid().ToString()
}
$body = @{
    customerReference = 'demo-customer'
    items = @(@{ sku = 'BOOK'; quantity = 1 })
    paymentScenario = 'LOST_RESPONSE'
} | ConvertTo-Json -Depth 5

Invoke-RestMethod http://127.0.0.1:8090/api/products -Headers $orderHeaders
$order = Invoke-RestMethod http://127.0.0.1:8090/api/orders `
    -Method Post -Headers $requestHeaders -ContentType 'application/json' -Body $body
$order | ConvertTo-Json -Depth 5
```

The API returns `201 Created`. Payment processing is asynchronous, so the initial response can still show a pending order.

### 2. Wait for confirmation and inspect the payment

```powershell
$deadline = (Get-Date).AddSeconds(20)
do {
    Start-Sleep -Milliseconds 300
    $current = Invoke-RestMethod "http://127.0.0.1:8090/api/orders/$($order.id)" `
        -Headers $orderHeaders
} while ($current.status -eq 'PAYMENT_PENDING' -and (Get-Date) -lt $deadline)

$current | ConvertTo-Json -Depth 5
Invoke-RestMethod "http://127.0.0.1:8091/payments/$($order.id)" `
    -Headers $paymentHeaders | Format-List
```

Expected: order `CONFIRMED`, payment `CAPTURED`, `captureCount` equal to `1`, and `requestAttempts` at least `2`. The simulator deliberately persists the capture before returning the first `503` response.

### 3. Replay the original order request

```powershell
$replayed = Invoke-RestMethod http://127.0.0.1:8090/api/orders `
    -Method Post -Headers $requestHeaders -ContentType 'application/json' -Body $body
$replayed.id -eq $order.id
```

Expected: `True`. An identical request with the same key returns `200 OK` and the original order. Changing the request while reusing its key returns `409 Conflict`.

### 4. Try a decline or an unavailable provider

Repeat step 1 with a **new idempotency key** and change `paymentScenario`. Then inspect the new order as in step 2.

| Scenario | Simulator behavior | Order outcome |
|---|---|---|
| `APPROVE` | Captures immediately | `CONFIRMED` |
| `DECLINE` | Definite decline | `PAYMENT_DECLINED`; reserved stock restored |
| `RETRY_ONCE` | First request returns unavailable; next captures | `CONFIRMED` after retry |
| `LOST_RESPONSE` | Capture persists before the first error response | `CONFIRMED`; one capture across retries |
| `ALWAYS_UNAVAILABLE` | Every request returns unavailable | `PAYMENT_REVIEW` after the attempt budget; stock stays reserved |

For an order in `PAYMENT_REVIEW`, explicitly retry its payment command:

```powershell
Invoke-RestMethod "http://127.0.0.1:8090/api/orders/$($current.id)/retry-payment" `
    -Method Post -Headers $orderHeaders
```

This returns `202 Accepted` and starts another bounded attempt cycle using the same payment identity. The order's `paymentAttempts` counter resets for the new cycle; the simulator's `requestAttempts` counts requests across cycles. An `ALWAYS_UNAVAILABLE` order returns to review because its configured scenario remains unavailable. To demonstrate recovery from an actual outage, stop the simulator, create an `APPROVE` order, wait for review, restart the simulator, then retry that order.

## API reference

All `/api/*` order routes and `/payments*` simulator routes require `X-API-Key`. Management routes other than health also require the corresponding key.

| Application | Method and path | Result |
|---|---|---|
| Orders | `GET /api/products` | Product prices and available stock |
| Orders | `POST /api/orders` | `201` new order; `200` matching idempotent replay |
| Orders | `GET /api/orders` | Recent orders |
| Orders | `GET /api/orders/{id}` | Order, lines, payment attempts and command state |
| Orders | `POST /api/orders/{id}/retry-payment` | `202` for an order awaiting payment review |
| Payments | `GET /payments/{orderId}` | Simulated payment ID, status, attempts and capture count |
| Payments | `POST /payments` | Internal simulator endpoint used by the worker |
| Both | `GET /actuator/health` | Public local health check |
| Orders | `GET /actuator/prometheus` | Prometheus-format metrics |

Order creation requires `Idempotency-Key` and a JSON body containing `customerReference` and `items`. The optional demo `paymentScenario` defaults to `APPROVE`. Prices and totals are calculated from the product catalogue in integer minor currency units. The sample catalogue uses USD: a `BOOK` costs `2500` cents.

Errors use HTTP status codes and problem responses: invalid input `400`, missing/incorrect API key `401`, unknown resource `404`, and incompatible request/state or insufficient stock `409`.

## Configuration

| Environment variable | Default | Application |
|---|---|---|
| `ORDERFLOW_API_KEY` | `local-order-key` | Orders API |
| `PAYMENT_BASE_URL` | `http://127.0.0.1:8091` | Orders worker |
| `PAYMENT_API_KEY` | `local-payment-key` | Orders worker's payment requests |
| `PAYMENT_SIMULATOR_API_KEY` | `local-payment-key` | Payment simulator API |
| `ORDERS_DB_URL` | File database at `./data/orders` | Orders |
| `PAYMENT_DB_URL` | File database at `./data/payment-simulator` | Payments |
| `ORDERS_DB_PASSWORD` / `PAYMENT_DB_PASSWORD` | Empty for local demo | Corresponding database |

The worker defaults to three attempts per cycle, a one-second request timeout, an exponential retry backoff starting at 500 ms and a five-second lease. See each application's `application.yml` and `OrderProperties` for validated configuration. You can override Spring properties on startup, for example `--orderflow.worker.enabled=false` or `--server.port=8092`. When changing the payment port, also update the orders application's `PAYMENT_BASE_URL`.

Only configured destinations receive payment requests. Local sample API keys are conveniences for the demo; configure your own values before sharing an accessible deployment.

## Containers

With Docker and Compose installed:

```sh
docker compose up --build
```

The images build and test both modules, then run with Java 21 as a non-root user. Containers listen on their internal network and expose ports `8090` and `8091` only on the host's loopback interface. Each application has a separate data volume. Compose starts the payment container first; use the health checks above to confirm both applications are ready.

`docker compose down` stops the applications while retaining their database volumes. Container configuration has been reviewed but not executed in the development environment; see the [verification record](docs/verification.md).

## Testing and CI

`mvnw verify` runs the module tests and generates JaCoCo reports under each module's `target/site/jacoco/`. Test result XML files are under each module's `target/surefire-reports/`. The GitHub Actions workflow repeats this build and retains test reports on pushes and pull requests when Actions is enabled. See the [verification record](docs/verification.md) for the completed local checks and their limits.

After the build, CI also runs `scripts/smoke.py` on Linux to check the packaged services and restart recovery, and retains its results and application logs. This check fails the workflow if a scenario does not pass; including the step does not establish a passing result.

The important behaviors to inspect are idempotent replay, conflicting requests, concurrent stock reservation, retry recovery, lease handling, decline compensation, and a capture followed by a lost response. Review the checked-in tests alongside the [architecture](docs/architecture.md) and [interview walkthrough](docs/interview-walkthrough.md).

### Packaged application smoke test

After building both JARs, run this with Python 3.10 or later:

```sh
python scripts/smoke.py
```

The script finds Java through `JAVA_HOME` or `PATH`. To select an executable explicitly:

```powershell
python scripts/smoke.py --java 'C:\path\to\jdk-21\bin\java.exe'
```

This starts its own two applications on temporary loopback ports and uses the **packaged default database URLs** in a fresh working directory under `target/smoke/`. It checks replay/conflict handling, lost-response idempotency, decline compensation, provider-outage recovery, and persistence of orders, inventory and queued commands across restarts.

Restart checks deliberately stop the child processes abruptly, without a graceful shutdown or a delay to flush writes. This guards against the regression where recently committed payment history disappeared after a provider process stopped. The test also checks that the old payment still has one simulated capture after restart and that final stock matches the confirmed orders.

The script stops every process it starts, writes `result.json` and application logs under `target/smoke/<run-id>/`, and prints the result path. An optional `--result-file path/to/result.json` changes the JSON destination. Application configuration environment variables are omitted from the child environment so the test retains its isolated local databases and configured loopback destinations.

## Resume value

OrderFlow complements ReleaseGuard: ReleaseGuard demonstrates deployment validation and operational tooling; OrderFlow demonstrates business transactions and recovery across service boundaries. For a Java backend role, choose the one most relevant to the job or give each a short entry if your two-page resume has space.

After running the scenarios and understanding the implementation, a factual project description is:

> **OrderFlow — Order and Payment Workflow | Personal Project**  
> Built a Java 21 / Spring Boot workflow with inventory reservation, idempotent APIs and a durable payment-command worker. Demonstrated retry recovery and prevention of duplicate simulated captures across lost payment responses using two independently running services and separate databases.

Keep this personal project separate from professional experience and use measured test results rather than invented business impact or throughput numbers.

## Design boundaries and references

The project implements an HTTP command outbox and local database transactions. It does not use Kafka, a distributed database transaction or a real payment gateway. The current H2 setup suits one instance of each application; a shared production deployment would need an appropriate database, deployment design and operational controls. Refunds, customer authentication, cart management and cloud deployment are outside this version.

- [Architecture and recovery behavior](docs/architecture.md)
- [Interview walkthrough](docs/interview-walkthrough.md)
- [Stripe: idempotent requests](https://docs.stripe.com/api/idempotent_requests) — design reference; no Stripe integration is included.
- [Spring: adapting to the transactional outbox pattern](https://spring.io/blog/2023/10/24/a-use-case-for-transactions-adapting-to-transactional-outbox-pattern/) — background on committing local work and a message together.

MIT licensed. Copyright 2026 Badri Logaram.
