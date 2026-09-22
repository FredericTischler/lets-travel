# travel-service

**Context:** Destinations (Spring Boot · Neo4j). Per
docs/lets-travel-architecture-decisions.md §2, `Destination` also doubles as
the subject's "Travel" entity for the Travel Manager/Traveler features — see
that section for why no separate `Travel` node was introduced.

## Current scope

Increment 1: basic CRUD for a single node type, `Destination`.
Increment 2: a first, directed relationship type, `TRANSPORT`, between two
`Destination` nodes, and the project's first real graph traversal query.
Increment 3 (docs/lets-travel-architecture-decisions.md §2): `Destination`
gains `managerId`/`price`/`capacity`, and mutation becomes ownership-aware —
a `TRAVEL_MANAGER` may only create/update/delete a destination they own,
`ADMIN` keeps full oversight.
Increment 4 (docs/lets-travel-architecture-decisions.md §3, Phase 3):
subscribe/unsubscribe to a `Destination`, with a 3-day self-service cutoff, a
manager/admin-facing subscriber list and force-unsubscribe, and a traveler's
own subscription history.
Increment 5 (docs/lets-travel-architecture-decisions.md §4 addendum, Phase 4):
paying for a subscription — a priced destination starts a subscription as
`PENDING_PAYMENT`, creates the payment in payment-service, and becomes `ACTIVE`
or `CANCELLED` when payment-service reports the outcome; `capacity` is now
enforced.

- `POST /destinations` — create a new destination/travel (`name`, `country`,
  `startDate`, `endDate`, `managerId`, `price`, `capacity`, optional
  `activities`/`accommodations`). Requires `ADMIN` or `TRAVEL_MANAGER`; a
  `TRAVEL_MANAGER` may only set `managerId` to their own id (403 otherwise).
- `GET /destinations/{id}` — get an active destination by id (404 if absent
  or soft-deleted). Open to any known role — the catalogue is public.
- `GET /destinations` — list all active destinations. Open to any known role.
- `PUT /destinations/{id}` — replace the mutable fields (`name`, `country`,
  `startDate`, `endDate`, `price`, `capacity`, `activities`,
  `accommodations` — not `managerId`, immutable after creation) of an active
  destination (404 if absent or soft-deleted). Requires `ADMIN` or the
  destination's own `TRAVEL_MANAGER` (403 for a non-owning manager).
- `DELETE /destinations/{id}` — soft-delete an active destination (404 if
  absent or already soft-deleted). Same ownership rule as `PUT`.
- `POST /destinations/{fromId}/transports` — create a directed `TRANSPORT`
  relationship from `fromId` to `toDestinationId` (body: `toDestinationId`,
  `mode`, `durationMinutes`). 201 if both endpoints exist and are active; 400
  if `fromId == toDestinationId`, `mode` is not one of `TRAIN`/`PLANE`/`BUS`/
  `CAR`/`BOAT`, or `durationMinutes <= 0`; 404 if origin or target is
  absent/soft-deleted; 403 unless the caller is `ADMIN` or the `TRAVEL_MANAGER`
  who owns the **origin** destination (the target only has to exist — ADR §10,
  addendum G1). Ownership is checked before the target lookup.
- `GET /destinations/{id}/transports` — one-hop traversal: destinations
  reachable from `id` via an outgoing `TRANSPORT` relationship. Filters
  `deletedAt IS NULL` at both hops (origin and target), so a soft-deleted
  target disappears from the result without its relationship being removed
  from the graph. 404 if `id` itself is absent/soft-deleted.
- `POST /destinations/{id}/subscriptions` — subscribe the caller themselves
  (subscriber id is always the JWT subject, never client-supplied) to a
  destination. Any of the 3 known roles. Optional body
  `{provider: MANUAL|STRIPE|PAYPAL, currency?}`: ignored for a free
  destination (`price` null/0 -> `ACTIVE` at once, no payment-service call),
  **required** for a priced one (400 without `provider`) — see "Paying for a
  subscription". 201 with the new subscription (a priced one also carries a
  `payment` object: `paymentId`, and Stripe's `clientSecret` / PayPal's
  `approveUrl`); 404 if the destination is absent/soft-deleted; 409 if the
  destination's `startDate` has already passed, the caller already holds an
  `ACTIVE` or still-valid `PENDING_PAYMENT` subscription for it, or no seat is
  left; 502 if payment-service could not create the payment (nothing is left
  pending).
- `DELETE /destinations/{id}/subscriptions` — unsubscribe the caller
  themselves. 204 on success; 404 if the destination is absent/soft-deleted,
  or the caller has no live subscription to cancel; 409 if the subscription is
  `ACTIVE` and fewer than 3 days remain before `startDate` (the cutoff does
  not apply to a `PENDING_PAYMENT` one — nothing is paid yet).
- `GET /destinations/{id}/subscriptions` — list every subscription (any
  status) for a destination. Restricted to the destination's own
  `TRAVEL_MANAGER` or `ADMIN` (403 for a non-owning manager). 404 if the
  destination is absent/soft-deleted.
- `DELETE /destinations/{id}/subscriptions/{travelerId}` — manager/admin
  force-unsubscribe of a specific traveler. Same ownership rule as the list
  endpoint. Deliberately does **not** enforce the 3-day cutoff — see
  "Subscriptions" below. 404 if the destination is absent/soft-deleted, or
  `travelerId` has no active subscription to cancel.
- `GET /travelers/me/subscriptions` — the caller's own subscription history
  (any status), across every active destination. Any of the 3 known roles,
  self only. Rows carry `subscriptionId`, `paymentId` and `expiresAt` when
  they went through the payment flow.
- `POST /internal/subscriptions/{subscriptionRef}/payment-result` —
  **service-to-service**, called by payment-service, not by the dashboard. Body
  `{travelId, userId, paymentId, status: COMPLETED|FAILED, amount, currency}`.
  Accepts only the `service:payment` token (401 without a token, 403 for any
  user token — `ADMIN` included). 200 with the subscription as it is afterwards;
  404 unknown `subscriptionRef`; 409 if the payment does not match the
  subscription or completed for a cancelled one. Idempotent.

Increment 5 (docs/lets-travel-architecture-decisions.md §5, §5ter): feedback
on participated destinations, its quality-control visibility for
managers/admins, and manager statistics/ranking built from it.

- `POST /destinations/{id}/feedback` — give feedback as the caller themselves
  (author = JWT subject, never the body). Body: `rating` (integer 1..5,
  required), `comment` (optional plain text, non-blank, max 1000 chars). Any of
  the 3 known roles. 201 with the feedback; 400 on a bad rating/comment
  (including a non-integer rating such as `4.5`); 403 if the caller has no
  `ACTIVE` subscription on the destination; 404 if the destination is
  absent/soft-deleted; 409 if the destination's `endDate` is not strictly in
  the past yet, or the caller already gave feedback on it.
- `GET /destinations/{id}/feedback` — every feedback on a destination.
  Restricted to the destination's own `TRAVEL_MANAGER` or `ADMIN` (403 for a
  non-owning manager or a traveler); 404 if absent/soft-deleted.
- `GET /travelers/me/feedback` — the caller's own feedback, across every active
  destination. Any known role, self only.
- `GET /feedback` — every feedback on every active destination. `ADMIN` only.
- `GET /managers/{managerId}/stats` — `activeTravels`, `pastTravels`,
  `subscribers`, `feedbackCount`, `averageRating`, `pastRatings` (per past
  destination). Any known role; aggregates only.
- `GET /managers/ranking` — managers ordered by performance score (damped
  rating, income, traveler volume), `ADMIN` only — see "Dashboards, ranking and
  statistics". Also `GET /managers/me/dashboard`, `GET /admin/dashboard` and
  `GET /travelers/me/stats`, described there.
- `GET /travelers/me/recommendations` — personalised suggestions with a score
  and reasons, from the caller's participation and feedback history (see
  "Recommendations" below). Any known role, self only (`?travelerId=` is
  `ADMIN`-only).

`Destination.id` is application-assigned (a plain UUID), not Neo4j's
internal (opaque) element id.

## TRANSPORT relationship — deliberate simplifications (increment 2)

- **Directed, non-symmetric**: `A-[TRANSPORT]->B` does not imply
  `B-[TRANSPORT]->A`; if both exist they are two distinct relationships.
- **No anti-duplicate protection**: creating the same `A->B` trip twice is
  not blocked. This is an accepted, documented gap for this increment, not
  an oversight — see the Javadoc on `TransportRepository`.
- **Single hop only for `GET /destinations/{id}/transports`**: it still
  returns exactly the destinations one `TRANSPORT` edge away. Multi-hop
  itineraries are a separate endpoint,
  `GET /destinations/{fromId}/routes/{toId}?maxHops=N` (default 4, max 6),
  shortest path in number of hops via `Neo4jClient`
  (docs/lets-travel-architecture-decisions.md §11, bonus) — `reachable: false`
  (200, not 404) when no path connects the two within `maxHops`.
- **Update/delete on transports** (§11 addendum): `PATCH`/`DELETE
  /destinations/{fromId}/transports/{transportId}` exist — same ownership rule
  as `create` (the origin's `managerId`, or `ADMIN`). `DELETE` physically
  removes the relationship rather than soft-deleting it: a `TRANSPORT`
  relationship has no lifecycle independent of its two nodes and nothing
  references it afterwards, unlike a payment or a review.
- **Bypasses the standard Spring Data Neo4j aggregate save/load flow**: the
  `Destination` entity does declare `@Relationship(type = "TRANSPORT", ...)`
  (documenting the domain model), but both creation and traversal are
  implemented with explicit Cypher via `Neo4jClient` in
  `TransportRepository`, not via `destinationRepository.save(...)`. SDN
  persists a `@Relationship` collection by deleting every existing
  relationship of that type from a node and recreating it from the
  in-memory list on every `save()` — combined with "no anti-duplicate
  protection" (so several relationships of the same type may legitimately
  coexist from one origin), a load-modify-save flow on a
  partially-fetched aggregate could silently wipe sibling relationships it
  never loaded. Explicit Cypher avoids that risk entirely.

## Subscriptions (docs/lets-travel-architecture-decisions.md §3, Phase 3)

`(TravelerRef {userId})-[:SUBSCRIBED {id, status, subscribedAt, cancelledAt,
expiresAt, amount, currency, paymentId}]->(Destination)`.
`TravelerRef` is a lightweight applicative reference (created on demand via
`MERGE` on `userId`), never a copy of identity-service's `User` — same
principle as `Destination.managerId`/`Payment.userId`. `status` is
`ACTIVE`, `PENDING_PAYMENT` or `CANCELLED` in storage; API reads also show
`EXPIRED` (a `PENDING_PAYMENT` whose `expiresAt` passed — derived in Cypher on
read, never written). `id` (UUID, new in Phase 4) is what payment-service
stores as `subscriptionRef`; relations created before Phase 4 have none.
Same explicit-Cypher-via-`Neo4jClient` approach as
`TransportRepository`/`ActivityRepository`, for the same reason (SDN would
rewrite the whole relation collection on save).

- Subscribing while already `ACTIVE` for the same destination is a 409, not
  a silent no-op: every subscribe creates a fresh `SUBSCRIBED` relation
  rather than reusing/resetting one (needed for an accurate historical count
  of cancellations on the traveler's personal stats page), so silently
  succeeding on a duplicate would create a second, redundant active relation.
- Subscribing to a destination whose `startDate` has already passed is also
  a 409: the destination exists and is well-formed, there is just nothing
  left to join.
- The 3-day cutoff applies only to the traveler's own self-service
  unsubscribe. The manager/admin force-unsubscribe endpoint deliberately
  does **not** enforce it: pulling a traveler close to departure is an
  administrative/capacity decision (a no-show, a policy violation), not the
  self-service flexibility case the cutoff exists to protect.
- No native uniqueness constraint on the traveler×destination pair (Neo4j
  Community has no node-key/composite constraints): the duplicate-active
  check is applicative, same documented gap as the rest of this phase.
  `TravelerRef.userId` itself *is* constrained unique (a plain
  single-property constraint, which Community does support) so concurrent
  first-time subscribes for the same traveler can't create two distinct
  `TravelerRef` nodes.

## Feedback (docs/lets-travel-architecture-decisions.md §5, §5ter)

`(TravelerRef {userId})-[:GAVE_FEEDBACK {id, rating, comment, createdAt}]->(Destination)`.
Same explicit-Cypher-via-`Neo4jClient` approach as `SUBSCRIBED`
(`FeedbackRepository`), with `deletedAt IS NULL` filtered on the `Destination`
side of every read, so a soft-deleted destination's feedback vanishes from
every list, statistic and the ranking without the relation being touched.

- **Participation rule**: only a traveler with an `ACTIVE` subscription on the
  destination, whose `endDate` is strictly before today, may give feedback. A
  cancelled, force-unsubscribed or `PENDING_PAYMENT` subscription does not
  count (403); an `ACTIVE` one on a trip that has not ended is a 409.
- **One feedback per traveler per destination**, enforced by a single
  `MERGE ... ON CREATE SET` (409 on a second one). Neo4j Community cannot
  enforce relationship uniqueness natively, so under truly concurrent requests
  a duplicate is narrowed, not impossible — the same documented gap as
  `SUBSCRIBED`.
- **Immutable**: no edit, no delete endpoint (an admin cannot remove an
  abusive comment either). Deliberate for this phase, see the ADR.
- **Comment = plain text, never HTML**: stored and returned verbatim (only
  surrounding whitespace is trimmed) — the backend neither strips nor
  HTML-encodes it. XSS protection is the renderer's responsibility (Angular
  escapes interpolation by default; no `[innerHTML]` on feedback). Cypher is
  parameterised.
- The manager/admin list exposes the author's user id (a UUID; names and
  e-mails stay in identity-service).

## Manager statistics (docs/lets-travel-architecture-decisions.md §5ter.5)

Read-only aggregations in `ManagerStatsRepository`/`ManagerStatsService`.
Definitions: `activeTravels` = the manager's non-soft-deleted destinations
(dates ignored), `pastTravels` = those already ended, `subscribers` = distinct
travelers with an `ACTIVE` subscription on them, `averageRating` = mean over
all feedbacks (not a mean of per-destination means), rounded to 2 decimals and
`null` when there is no feedback. A manager id owning no active destination
yields zeros with a 200, not a 404 (manager identity lives in identity-service).

`GET /managers/{id}/stats` still carries neither income nor report counts.
The ranking, however, is no longer the provisional "average rating, then
count" version (which let one 5-star review outrank a hundred 4.9-star ones):
it is now a performance score, see "Dashboards, ranking and statistics" below.

## Recommendations (docs/lets-travel-architecture-decisions.md §7)

`GET /travelers/me/recommendations` — the destinations the caller could still
join, best first, each with a `score` and human-readable `reasons`. Any known
role, for the caller; `?travelerId=` is `ADMIN`-only (403 otherwise, 400 if not
a UUID); `?limit=` defaults to 10 and is clamped to 1..50.

- **Eligible**: not soft-deleted, `startDate >= today` (same rule as
  subscribing), not already live for the caller (`ACTIVE` or an unexpired
  `PENDING_PAYMENT`; a `CANCELLED`/expired one does not block), not full.
- **History** = destinations the caller holds an `ACTIVE` subscription on
  (participation) or rated (`GAVE_FEEDBACK`). `PENDING_PAYMENT`/`CANCELLED`
  alone do not count; soft-deleted destinations are ignored.
- **Score** = sum over the history of `weight × similarity`. Similarity uses
  four fields of the travel: country (+3), activities in common (+1 each, max
  3), accommodation type in common (+1), price within ±25 % (+1). Weight is
  set by the rating (5 -> +3, 4 -> +2, 3 -> +0.5, 2 -> -1.5, 1 -> -3), or +1 for
  participation without a rating: a 5 pulls similar trips up, a 1-2 pushes
  them down (they stay listed, lower, with their reason). Every reason ends
  with its signed points, so the score is the sum of the reasons (more than 6
  are folded into a last "smaller factors" line).
- **Ties** are broken by most `ACTIVE` subscribers, then soonest start, then
  name. **Cold start** (no history): `score` = number of `ACTIVE`
  subscribers, same ties, and the reason says so.
- The Cypher (`RecommendationRepository`) only establishes facts about a
  (candidate, history) pair; all weights are named constants in
  `RecommendationScorer` (plain Java, unit-tested without a database). The
  worked example and the trade-offs are in the ADR §7 addendum.

Limits (deliberate, see the ADR): hand-picked weights; exact (case-insensitive)
string matching for country/activity/accommodation type, no synonyms; a fixed
price tolerance and no currency; no time decay; cancellations are not a
negative signal; computed on demand over the whole catalogue, no cache and no
pagination or filters.

## Paying for a subscription (docs/lets-travel-architecture-decisions.md §4 addendum, Phase 4)

- **Free destination** (`price` null or 0): unchanged, the subscription is
  `ACTIVE` at once.
- **Priced destination**: the traveler picks `provider` (`MANUAL`/`STRIPE`/
  `PAYPAL`). The subscription is created `PENDING_PAYMENT` with an
  `expiresAt` (60 min for Stripe/PayPal, 72 h for `MANUAL` — both configurable:
  `SUBSCRIPTION_PENDING_TTL_MINUTES`, `SUBSCRIPTION_PENDING_TTL_MANUAL_MINUTES`),
  the price and currency (`EUR` unless the body says otherwise) are recorded,
  then `PaymentServiceClient` calls payment-service's existing create endpoint
  for that provider with `userId` = the caller, `travelId`, and
  `subscriptionRef` = the subscription id. The **traveler's own token is
  forwarded**, so payment-service's ownership rule is what guarantees a
  traveler only pays for their own subscription. Nothing of Stripe/PayPal is
  reimplemented here. Same client pattern as identity's `PaymentServiceClient`
  (RestClient, short timeouts, `X-Request-Id` propagated) except that a failure
  is *not* swallowed: the pending subscription is cancelled again and the
  caller gets a 502.
- **Outcome**: payment-service calls the internal endpoint (service token,
  see above). `COMPLETED` -> `ACTIVE`; `FAILED` -> `CANCELLED`. The payment is
  cross-checked (same traveler, same destination, the recorded payment id,
  amount >= price, same currency) — a mismatch is a 409 and changes nothing.
- **Capacity**: `ACTIVE` subscriptions and still-valid `PENDING_PAYMENT` ones
  count toward `capacity`; 409 when full — free destinations too (`capacity`
  was not enforced before this phase). Count and insert are one Cypher
  statement, no lock: two strictly simultaneous subscribes can both take the
  last seat.
- **Expiry**: nothing sweeps expired holds. An expired `PENDING_PAYMENT` reads
  as `EXPIRED`, stops counting toward capacity and no longer blocks a new
  subscribe. A payment that completes *after* expiry still activates the
  subscription (the money was taken; a warning is logged and the destination
  may exceed its capacity); one that completes for a subscription cancelled
  meanwhile is a 409 and an `ERROR` log ("manual refund required").
- **Failure window**: payment `COMPLETED` in payment-service but the callback
  fails -> the subscription stays `PENDING_PAYMENT` until payment-service
  re-sends (`POST /payments/reconcile-subscriptions`, admin) or the hold
  expires. No distributed transaction.
- The subscribe flow (`SubscriptionCheckoutService`) deliberately has no
  `@Transactional`: `Neo4jClient` binds a rollback-able transaction to any
  active Spring transaction synchronization, even `NOT_SUPPORTED`, which
  silently undid the compensation when the payment call failed.

## Soft-delete

Nodes are never physically removed. "Delete" sets `deletedAt` on the node to
the current timestamp; the node stays. Every read Cypher query
(`findActiveById`, `findAllActive`) systematically filters on
`d.deletedAt IS NULL`, so a soft-deleted destination is indistinguishable
from a non-existent one to API callers — same foundation as `User`/`Payment`
in identity-service/payment-service.

## Schema constraint at startup

There is no Flyway (or equivalent migration tool) for this service. Instead,
`Neo4jSchemaInitializer` runs an idempotent Cypher statement at every startup
(`CREATE CONSTRAINT ... IF NOT EXISTS`) to ensure `Destination.id` is unique.
This is a deliberate choice for a single node type with a single, simple
constraint; it should be reconsidered if the graph model grows (multiple
node types, relationships, ordered/versioned schema changes).

## Assumed debt: Neo4j Community Edition access control

Unlike identity-service and payment-service, which each connect to Postgres
with a dedicated application account with limited rights, this service
connects to Neo4j using the single administrative account (`neo4j`).
Neo4j Community Edition has no RBAC and no multi-tenant user support: there
is exactly one administrative account, and no way to provision a scoped,
per-service account. This is a known, documented limitation of the edition,
not an oversight — see the Javadoc on `Neo4jConnectionConfig` for the full
rationale.

## Dashboards, ranking and statistics (ADR "Dashboards" addendum)

Money lives in payment-service, so these endpoints compose travel-service's
graph with payment-service, **read-only and best-effort**
(`PaymentStatsClient`, 3 s / 5 s timeouts, `X-Request-Id` propagated). If
payment-service cannot answer, the money fields are `null` and `partial` is
`true`; everything else is still served. Nothing is stored here.

| Endpoint | Who | What |
|---|---|---|
| `GET /managers/me/dashboard?managerId=&months=6` | `TRAVEL_MANAGER` (own), `ADMIN` (any via `managerId`) | trips, travelers, rating, income (total / per month / per travel), recent feedback. A manager passing another id: 403 |
| `GET /admin/dashboard?months=6` | `ADMIN` | totals, income per month, top managers (score / rating / income), top travels (income / rating), past-travel history, recent feedback |
| `GET /managers/ranking` | `ADMIN` | every manager with an active travel, by performance score (original fields kept, new ones added) |
| `GET /travelers/me/stats?travelerId=` | any role (self), `ADMIN` (any) | past participations, upcoming, cancellations, feedbacks given, preferred payment provider |

`months` is clamped to 1..24. Income comes from payment-service's
`GET /payments/income` with a `service:travel` service token
(`JwtService.generateServiceToken()`, accepted there on that single route); the
traveler's payment summary comes from `GET /payments/summary` with the caller's
own token forwarded. Only **active** (non-deleted) travels are summed, so a
manager's per-travel figures add up to their total. Amounts are per ISO
currency (`{"EUR": 190.0}`); the scalar `*Amount` fields are the
`dashboard.reference-currency` share (env `DASHBOARD_REFERENCE_CURRENCY`,
default `EUR`), with no conversion.

**Performance score** (`PerformanceScore`, 0..100):
`100 * (0.5 * ratingScore + 0.3 * incomeScore + 0.2 * travelersScore)`.
`ratingScore` is the *damped* mean rating `(sum + 5*3.0) / (count + 5)`
rescaled from 1..5 to 0..1 (prior weight 5 at the neutral 3.0): one 5-star
review gives 3.33, a hundred 4.9 give 4.81, no feedback gives 3.0 (neutral).
`incomeScore` = reference-currency income / best manager's; `travelersScore` =
distinct `ACTIVE` travelers / best manager's. Without income (payment-service
down) the two remaining weights are renormalised and each entry is `partial`.
Order: score, then feedback count, then manager id.

**Report counts** are identity-service's: the front reads
`GET /reports/count/{userId}` (any role) for a manager or a traveler; they are
**not** part of the score and travel-service does not call identity-service.

## Configuration

All connection values are externalized via environment variables in
`application.yml` (`NEO4J_HOST`, `NEO4J_PORT`, `NEO4J_USERNAME`,
`NEO4J_PASSWORD`, `JWT_SIGNING_KEY`, `PAYMENT_SERVICE_URL`, `SERVER_PORT`).
Required placeholders are bare `${VAR}` (the Compose-style `${VAR:?msg}` is *not*
fail-fast in Spring; `ConfigFailFastTest` proves it). `GET /actuator/health`
returns the aggregate status only (`show-details: never`).
There is no `dbname` variable: Neo4j Community Edition has a single default
database. The service fails fast at startup if any required variable is absent
— including `PAYMENT_SERVICE_URL`, the base URL of payment-service (the test
suite supplies a test-only default in `src/test/resources/application.properties`;
production has none). `SUBSCRIPTION_PENDING_TTL_MINUTES` (default 60) and
`SUBSCRIPTION_PENDING_TTL_MANUAL_MINUTES` (default 4320) are plain tunables, not
secrets/URLs, so they do have defaults.

## Not yet implemented

- No second node type: `Activity`/`Accommodation` exist but only as children
  of `Destination`; no dedicated `Travel` node either — see
  docs/lets-travel-architecture-decisions.md §2 for why `Destination` itself
  covers that role instead.
- No anti-duplicate protection on `TRANSPORT` relationships (see above) —
  pathfinding and update/delete are implemented (§11, bonus).
- The only `/internal/*` endpoint is the payment-result callback above; no
  other cross-service endpoint.
- Subscription payment (docs/lets-travel-architecture-decisions.md §4) is
  best-effort, not transactional: nothing automatically retries a payment
  callback that failed (payment-service exposes an admin reconciliation
  endpoint, nothing schedules it); nothing refunds a payment that completed
  for a subscription cancelled meanwhile (409 + error log only); a `FAILED`
  payment produces a plain `CANCELLED` (no cancellation reason, so it counts
  as a cancellation in the traveler's stats).
- `capacity` is enforced without a lock (see "Paying for a subscription"),
  and expired payment holds are derived on read, never swept or written.
- Existing `SUBSCRIBED` relations created before Phase 4 have no `id`, so they
  can never be the target of a payment result (they were never pending).
- The dashboard-facing "pay now" screens are not part of this service; the
  subscribe response gives the traveler what they need (payment id, Stripe
  `clientSecret` / PayPal `approveUrl`, `expiresAt`).
- Recommendations exist (see "Recommendations") but are a hand-weighted
  content-based score, not a learned model or collaborative filtering. (The
  Elasticsearch search/autocomplete of §6 exists in the code but is not
  described in this README yet.)
- Feedback is immutable: no edit, no delete, no admin moderation of an abusive
  comment (see "Feedback" above). No pagination on the feedback lists.
- Dashboards/ranking (see "Dashboards, ranking and statistics"): the score has
  no report-count penalty (identity-service's, added by the front for display
  only); income is not converted between currencies (only the reference
  currency is scored); refunds are not modelled (a `COMPLETED` payment counts
  as income); income of a soft-deleted travel disappears from its manager's
  figures; the income aggregate is fetched whole from payment-service on every
  dashboard call (no cache, fine at demo volume); "cancellations" include
  payment failures; no pagination of the history list.
