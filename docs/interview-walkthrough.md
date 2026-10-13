# Interview walkthrough

## A one-minute explanation

“OrderFlow is my personal Java backend project for studying order and payment reliability. The order service reserves stock and stores a payment command in one transaction. A worker delivers that command to an independent payment simulator. The simulator can capture a payment and deliberately lose the first response, which lets me demonstrate why retries need idempotency. Repeated delivery returns the same payment identity. Uncertain outcomes go to review with stock still reserved, while a definite decline restores inventory.”

Run the five-minute README demo before presenting this description. Be ready to open the tests and explain the code behind every claim.

## What to demonstrate

1. Show the product catalogue and create a `LOST_RESPONSE` order.
2. Show the order becoming `CONFIRMED` and the payment showing multiple attempts but one simulated capture.
3. Replay the original order request with its original key and show the same order ID.
4. Reuse the key with a changed request and show the conflict.
5. Compare a definite `DECLINE` with `ALWAYS_UNAVAILABLE`: one releases reserved stock; the other keeps it in review.
6. Stop the simulator, create an `APPROVE` order, then restart the simulator and retry the order from review.

## Questions worth practising

**Why store the command in the order database?**  
The application must preserve the intent to request payment whenever it accepts an order. A single local transaction commits both. Sending HTTP first or saving the command after a separate commit leaves a failure gap.

**Why not keep a database transaction open during the HTTP request?**  
The network call may be slow or fail. Holding database locks across it adds contention without creating an atomic transaction with the remote service. The worker uses a lease and conditionally records the result instead.

**What happens if the payment succeeds but the order service never receives the response?**  
The worker retries with the same payment identity. The simulator resolves that identity to its persisted payment rather than creating another capture. The `LOST_RESPONSE` scenario demonstrates this case.

**Does this guarantee exactly-once processing?**  
The transport can deliver a command more than once. The database constraints and idempotent payment handling make duplicate deliveries safe for the modeled workflow. That is a narrower and testable claim than exactly-once transport.

**Why keep stock reserved after retries run out?**  
An unavailable response leaves the outcome uncertain. A payment may have happened. Review preserves the reservation until a later attempt can establish the result. A definite decline is different and permits inventory compensation.

**What is the purpose of a lease token?**  
It identifies the current worker claim. An expired worker can still finish its network call; conditional completion prevents that stale worker from changing state after another claim has taken over.

**Why use two applications with H2?**  
Two applications create a real process and HTTP boundary while separate H2 databases make local setup small. The project demonstrates recovery semantics without requiring a broker or external database. It is not evidence of production load handling.

**What would you improve next?**  
Pick a concrete limitation: a PostgreSQL profile with migration scripts and database-specific concurrency tests; a provider adapter with reconciliation; or an administrative view of unresolved payments. Describe the additional failure cases you would test before claiming the feature works.

## Positioning beside ReleaseGuard

ReleaseGuard is strongest when discussing release confidence, production troubleshooting and operational automation. OrderFlow is strongest when discussing transactions, inventory consistency, idempotency and recovery between services. Both can support a broad Java backend profile; neither replaces the evidence from professional experience.

On a two-page resume, use one or two compact project bullets with a repository link. Label the work as a personal project. Explain this implementation in your own words and retain only the technologies and behaviors you can demonstrate. Do not describe it as a deployed commerce platform, a real payment integration or a contribution accepted by another open-source project.
