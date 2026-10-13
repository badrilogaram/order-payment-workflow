"""Exercise the packaged OrderFlow applications on loopback; retain local evidence."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import socket
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid


PROJECT = Path(__file__).resolve().parent.parent
RUN_DIR = PROJECT / "target" / "smoke" / uuid.uuid4().hex
ORDER_KEY = "local-order-key"
PAYMENT_KEY = "local-payment-key"
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
PROCESSES: list[dict] = []
CHECKS: list[dict] = []
ORDER_IDS: dict[str, str] = {}


def check(condition: bool, name: str, **evidence) -> None:
    if not condition:
        raise AssertionError(f"{name}: {evidence}")
    CHECKS.append({"name": name, "passed": True, **evidence})
    print(f"PASS: {name}", flush=True)


def unused_ports() -> tuple[int, int]:
    sockets = []
    try:
        for _ in range(2):
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.bind(("127.0.0.1", 0))
            sockets.append(sock)
        return tuple(sock.getsockname()[1] for sock in sockets)
    finally:
        for sock in sockets:
            sock.close()


def request(base: str, path: str, *, method="GET", key=None, body=None, idempotency=None):
    headers = {}
    if key is not None:
        headers["X-API-Key"] = key
    if idempotency is not None:
        headers["Idempotency-Key"] = idempotency
    raw = None
    if body is not None:
        raw = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(base + path, data=raw, headers=headers, method=method)
    try:
        with OPENER.open(req, timeout=3) as response:
            content = response.read()
            status = response.status
    except urllib.error.HTTPError as response:
        with response:
            status = response.code
            content = response.read()
    return status, json.loads(content) if content else None


def start(java: Path, module: str, port: int, payment_port: int, *, worker=True):
    jar = PROJECT / module / "target" / f"{module}-0.1.0.jar"
    if not jar.is_file():
        raise FileNotFoundError(f"Build the packaged application first: {jar}")
    # Use the packaged default database URLs unchanged. The private working
    # directory keeps their separate ./data/* files isolated from normal demos.
    cmd = [str(java), "-jar", str(jar), "--server.address=127.0.0.1", f"--server.port={port}"]
    if module == "order-service":
        cmd += [f"--orderflow.api-key={ORDER_KEY}",
                f"--orderflow.payment-base-url=http://127.0.0.1:{payment_port}",
                f"--orderflow.payment-api-key={PAYMENT_KEY}",
                "--orderflow.max-attempts=3", "--orderflow.retry-backoff=100ms",
                "--orderflow.request-timeout=400ms", "--orderflow.lease-duration=5s",
                "--orderflow.worker.poll-delay-ms=50",
                f"--orderflow.worker.enabled={str(worker).lower()}"]
    else:
        cmd.append(f"--payment-simulator.api-key={PAYMENT_KEY}")
    sequence = 1 + sum(entry["module"] == module for entry in PROCESSES)
    log_path = RUN_DIR / f"{module}-{sequence}.log"
    log = log_path.open("wb")
    # User application configuration must not redirect this smoke run to an
    # external database or replace the packaged default configuration.
    config_prefixes = ("SPRING_", "ORDERFLOW_", "ORDERS_", "PAYMENT_", "SERVER_", "MANAGEMENT_")
    child_env = {key: value for key, value in os.environ.items()
                 if not key.upper().startswith(config_prefixes)}
    try:
        child = subprocess.Popen(cmd, cwd=RUN_DIR, stdin=subprocess.DEVNULL,
                                 stdout=log, stderr=subprocess.STDOUT, env=child_env,
                                 creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    except BaseException:
        log.close()
        raise
    entry = {"module": module, "process": child, "log_handle": log, "log_path": log_path,
             "port": port, "sequence": sequence}
    PROCESSES.append(entry)
    print(f"Started {module} instance {sequence} on loopback port {port}", flush=True)
    return entry


def wait_health(entry) -> None:
    deadline = time.monotonic() + 60
    base = f"http://127.0.0.1:{entry['port']}"
    last = "No response"
    while time.monotonic() < deadline:
        if entry["process"].poll() is not None:
            raise RuntimeError(f"{entry['module']} exited before becoming healthy")
        try:
            status, body = request(base, "/actuator/health")
            if status == 200 and body.get("status") == "UP":
                print(f"Ready: {entry['module']} instance {entry['sequence']}", flush=True)
                return
            last = f"HTTP {status}: {body}"
        except (OSError, urllib.error.URLError, ValueError) as error:
            last = str(error)
        time.sleep(0.2)
    raise TimeoutError(f"{entry['module']} did not become healthy within 60 seconds: {last}")


def stop(entry) -> None:
    child = entry["process"]
    if child.poll() is None:
        # Abrupt termination is intentional: persistence must not depend on
        # a graceful shutdown hook or a sleep allowing delayed H2 writes.
        child.kill()
        child.wait(timeout=12)
    entry["log_handle"].close()


def order_body(scenario: str, quantity=1):
    return {"customerReference": "smoke-demo", "items": [{"sku": "BOOK", "quantity": quantity}],
            "paymentScenario": scenario}


def new_order(base: str, scenario: str, label: str):
    key = str(uuid.uuid4())
    body = order_body(scenario)
    status, order = request(base, "/api/orders", method="POST", key=ORDER_KEY,
                            body=body, idempotency=key)
    check(status == 201 and bool(order.get("id")), f"{label}: order accepted", httpStatus=status)
    ORDER_IDS[label] = order["id"]
    return order, key, body


def wait_order(base: str, order_id: str, expected: str, timeout=20):
    deadline = time.monotonic() + timeout
    latest = None
    while time.monotonic() < deadline:
        status, latest = request(base, f"/api/orders/{order_id}", key=ORDER_KEY)
        if status != 200:
            raise AssertionError(f"Order query returned HTTP {status}: {latest}")
        if latest["status"] == expected:
            return latest
        if latest["status"] != "PAYMENT_PENDING":
            raise AssertionError(f"Expected {expected}, observed {latest['status']}: {latest}")
        time.sleep(0.15)
    raise TimeoutError(f"Order did not reach {expected}: {latest}")


def stock(base: str) -> int:
    status, products = request(base, "/api/products", key=ORDER_KEY)
    if status != 200:
        raise AssertionError(f"Product query failed: HTTP {status}")
    return next(product["availableStock"] for product in products if product["sku"] == "BOOK")


def payment(base: str, order_id: str):
    status, view = request(base, f"/payments/{order_id}", key=PAYMENT_KEY)
    if status != 200:
        raise AssertionError(f"Payment query failed: HTTP {status}: {view}")
    return view


def exercise(java: Path) -> dict:
    for module in ("order-service", "payment-simulator"):
        jar = PROJECT / module / "target" / f"{module}-0.1.0.jar"
        if not jar.is_file():
            raise FileNotFoundError(f"Build both packaged applications with mvnw verify first: {jar}")
    order_port, payment_port = unused_ports()
    orders_url = f"http://127.0.0.1:{order_port}"
    payments_url = f"http://127.0.0.1:{payment_port}"

    provider = start(java, "payment-simulator", payment_port, payment_port)
    orders = start(java, "order-service", order_port, payment_port)
    wait_health(provider)
    wait_health(orders)

    status, _ = request(orders_url, "/api/products")
    check(status == 401, "Orders API rejects missing key", httpStatus=status)
    initial_stock = stock(orders_url)
    approved, idem, body = new_order(orders_url, "APPROVE", "approve")
    confirmed = wait_order(orders_url, approved["id"], "CONFIRMED")
    approved_payment = payment(payments_url, approved["id"])
    check(approved_payment["captureCount"] == 1 and approved_payment["status"] == "CAPTURED",
          "Approved order has one simulated capture", orderStatus=confirmed["status"],
          captureCount=approved_payment["captureCount"])
    after_approve = stock(orders_url)
    check(after_approve == initial_stock - 1, "Approval reserves exactly one unit",
          before=initial_stock, after=after_approve)

    status, replayed = request(orders_url, "/api/orders", method="POST", key=ORDER_KEY,
                               body=body, idempotency=idem)
    check(status == 200 and replayed["id"] == approved["id"], "Idempotent replay returns the original order",
          httpStatus=status, sameId=replayed.get("id") == approved["id"])
    check(stock(orders_url) == after_approve, "Idempotent replay does not reserve stock again")
    status, _ = request(orders_url, "/api/orders", method="POST", key=ORDER_KEY,
                        body=order_body("APPROVE", quantity=2), idempotency=idem)
    check(status == 409, "Changed request under the same key is rejected", httpStatus=status)

    lost, _, _ = new_order(orders_url, "LOST_RESPONSE", "lost_response")
    lost_confirmed = wait_order(orders_url, lost["id"], "CONFIRMED")
    lost_payment = payment(payments_url, lost["id"])
    check(lost_payment["captureCount"] == 1 and lost_payment["requestAttempts"] == 2
          and lost_confirmed["paymentId"] == lost_payment["paymentId"],
          "Lost response retries without a second simulated capture",
          orderStatus=lost_confirmed["status"], captureCount=lost_payment["captureCount"],
          requestAttempts=lost_payment["requestAttempts"])

    before_decline = stock(orders_url)
    declined, _, _ = new_order(orders_url, "DECLINE", "decline")
    decline_view = wait_order(orders_url, declined["id"], "PAYMENT_DECLINED")
    decline_payment = payment(payments_url, declined["id"])
    check(stock(orders_url) == before_decline and decline_payment["captureCount"] == 0,
          "Definite decline restores stock", orderStatus=decline_view["status"],
          availableStock=stock(orders_url), captureCount=decline_payment["captureCount"])

    stop(provider)
    before_outage = stock(orders_url)
    outage, _, _ = new_order(orders_url, "APPROVE", "provider_outage")
    review = wait_order(orders_url, outage["id"], "PAYMENT_REVIEW")
    check(review["paymentAttempts"] == 3 and review["outboxStatus"] == "DEAD"
          and stock(orders_url) == before_outage - 1,
          "Provider outage exhausts retries and holds stock for review",
          orderStatus=review["status"], paymentAttempts=review["paymentAttempts"],
          availableStock=stock(orders_url))

    provider = start(java, "payment-simulator", payment_port, payment_port)
    wait_health(provider)
    prior_payment = payment(payments_url, lost["id"])
    check(prior_payment["paymentId"] == lost_payment["paymentId"] and prior_payment["captureCount"] == 1
          and prior_payment["requestAttempts"] == lost_payment["requestAttempts"]
          and prior_payment["status"] == "CAPTURED",
          "Payment history and single capture survive abrupt provider restart",
          captureCount=prior_payment["captureCount"], requestAttempts=prior_payment["requestAttempts"])
    status, _ = request(orders_url, f"/api/orders/{outage['id']}/retry-payment", method="POST", key=ORDER_KEY)
    check(status == 202, "Manual retry from review is accepted", httpStatus=status)
    recovered = wait_order(orders_url, outage["id"], "CONFIRMED")
    recovered_payment = payment(payments_url, outage["id"])
    check(recovered_payment["captureCount"] == 1 and stock(orders_url) == before_outage - 1,
          "Provider recovery confirms order without another reservation",
          orderStatus=recovered["status"], captureCount=recovered_payment["captureCount"])

    stop(orders)
    orders = start(java, "order-service", order_port, payment_port, worker=False)
    wait_health(orders)
    status, persisted = request(orders_url, f"/api/orders/{approved['id']}", key=ORDER_KEY)
    check(status == 200 and persisted["status"] == "CONFIRMED"
          and persisted["paymentId"] == confirmed["paymentId"],
          "Order history survives application restart", orderStatus=persisted.get("status"))
    check(stock(orders_url) == initial_stock - 3,
          "Inventory reservations and decline compensation survive abrupt order restart",
          initialStock=initial_stock, persistedStock=stock(orders_url))
    queued, _, _ = new_order(orders_url, "APPROVE", "queued_restart")
    check(queued["status"] == "PAYMENT_PENDING" and queued["paymentAttempts"] == 0,
          "Disabled worker leaves a durable queued command", orderStatus=queued["status"])
    stop(orders)
    orders = start(java, "order-service", order_port, payment_port)
    wait_health(orders)
    resumed = wait_order(orders_url, queued["id"], "CONFIRMED")
    resumed_payment = payment(payments_url, queued["id"])
    check(resumed_payment["captureCount"] == 1,
          "Queued command is processed after application restart",
          orderStatus=resumed["status"], captureCount=resumed_payment["captureCount"])
    final_stock = stock(orders_url)
    check(final_stock == initial_stock - 4,
          "Final stock preserves exactly four confirmed reservations across restarts",
          initialStock=initial_stock, finalStock=final_stock)

    status_counts: dict[str, int] = {}
    for order_id in ORDER_IDS.values():
        status, view = request(orders_url, f"/api/orders/{order_id}", key=ORDER_KEY)
        if status != 200:
            raise AssertionError(f"Final order lookup failed: {order_id}")
        status_counts[view["status"]] = status_counts.get(view["status"], 0) + 1
    check(status_counts == {"CONFIRMED": 4, "PAYMENT_DECLINED": 1},
          "All five order outcomes persist after abrupt application restarts", statuses=status_counts)
    return {"orderCount": len(ORDER_IDS), "finalOrderStatuses": status_counts,
            "initialStock": initial_stock, "finalStock": final_stock,
            "observedIntermediateStatuses": ["PAYMENT_PENDING", "PAYMENT_REVIEW"],
            "ports": {"orders": order_port, "payments": payment_port}}


def sanitized(value: str) -> str:
    return value.replace(ORDER_KEY, "<demo-order-key>").replace(PAYMENT_KEY, "<demo-payment-key>")


def find_java(argument: str | None) -> Path:
    if argument:
        explicit = Path(argument).expanduser()
        if explicit.is_file():
            return explicit.resolve()
        from_path = shutil.which(argument)
        if from_path:
            return Path(from_path).resolve()
        raise FileNotFoundError(f"Java executable not found: {argument}")
    if os.environ.get("JAVA_HOME"):
        candidate = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.is_file():
            return candidate.resolve()
    from_path = shutil.which("java")
    if from_path:
        return Path(from_path).resolve()
    raise FileNotFoundError("JDK 21 is required: pass --java, set JAVA_HOME, or put java on PATH")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", help="Java 21 executable (defaults to JAVA_HOME/bin/java, then PATH)")
    parser.add_argument("--result-file", type=Path,
                        help="Optional result JSON destination; logs/databases stay under target/smoke/<run-id>")
    args = parser.parse_args()
    result_file = args.result_file.expanduser().resolve() if args.result_file else RUN_DIR / "result.json"
    RUN_DIR.mkdir(parents=True, exist_ok=False)
    result = {"success": False, "runDirectory": str(RUN_DIR), "checks": CHECKS, "orderIds": ORDER_IDS}
    started = time.monotonic()
    try:
        result.update(exercise(find_java(args.java)))
        result["success"] = True
    except BaseException as error:
        result["error"] = sanitized(f"{type(error).__name__}: {error}")
        print(result["error"], file=sys.stderr, flush=True)
    finally:
        cleanup_errors = []
        for entry in reversed(PROCESSES):
            try:
                stop(entry)
            except BaseException as error:
                cleanup_errors.append(sanitized(f"{entry['module']}: {type(error).__name__}: {error}"))
        result["processesStopped"] = all(entry["process"].poll() is not None for entry in PROCESSES)
        result["processCount"] = len(PROCESSES)
        if cleanup_errors:
            result["cleanupErrors"] = cleanup_errors
        if not result["processesStopped"] or cleanup_errors:
            result["success"] = False
        result["checkCount"] = len(CHECKS)
        result["elapsedSeconds"] = round(time.monotonic() - started, 2)
        result["logs"] = [str(entry["log_path"]) for entry in PROCESSES]
        if not result["success"]:
            result["logTails"] = {entry["log_path"].name:
                sanitized("\n".join(entry["log_path"].read_text(encoding="utf-8", errors="replace").splitlines()[-25:]))
                for entry in PROCESSES}
        result_file.parent.mkdir(parents=True, exist_ok=True)
        result_file.write_text(json.dumps(result, indent=2), encoding="utf-8")
        print(json.dumps({"success": result["success"], "checkCount": result["checkCount"],
                          "processesStopped": result["processesStopped"], "resultFile": str(result_file)}), flush=True)
    return 0 if result["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
