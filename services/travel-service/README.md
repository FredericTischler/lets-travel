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
  absent/soft-deleted.
- `GET /destinations/{id}/transports` — one-hop traversal: destinations
  reachable from `id` via an outgoing `TRANSPORT` relationship. Filters
  `deletedAt IS NULL` at both hops (origin and target), so a soft-deleted
  target disappears from the result without its relationship being removed
  from the graph. 404 if `id` itself is absent/soft-deleted.
- `POST /destinations/{id}/subscriptions` — subscribe the caller themselves
  (subscriber id is always the JWT subject, never client-supplied) to a
  destination. Any of the 3 known roles. 201 with the new subscription; 404
  if the destination is absent/soft-deleted; 409 if the destination's
  `startDate` has already passed, or the caller already holds an active
  subscription for it.
- `DELETE /destinations/{id}/subscriptions` — unsubscribe the caller
  themselves, enforcing a 3-day cutoff before `startDate`. 204 on success;
  404 if the destination is absent/soft-deleted, or the caller has no active
  subscription to cancel; 409 if fewer than 3 days remain before `startDate`.
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
  self only.

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
- `GET /managers/ranking` — managers ordered by average rating then number of
  feedbacks. `ADMIN` only.

`Destination.id` is application-assigned (a plain UUID), not Neo4j's
internal (opaque) element id.

## TRANSPORT relationship — deliberate simplifications (increment 2)

- **Directed, non-symmetric**: `A-[TRANSPORT]->B` does not imply
  `B-[TRANSPORT]->A`; if both exist they are two distinct relationships.
- **No anti-duplicate protection**: creating the same `A->B` trip twice is
  not blocked. This is an accepted, documented gap for this increment, not
  an oversight — see the Javadoc on `TransportRepository`.
- **Single hop only**: no pathfinding, no multi-hop/recursive traversal, no
  second node type. `GET /destinations/{id}/transports` returns exactly the
  destinations one `TRANSPORT` edge away.
- **No update/delete on transports**: once created, a `TRANSPORT`
  relationship cannot be modified or removed through the API in this
  increment.
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

`(TravelerRef {userId})-[:SUBSCRIBED {status, subscribedAt, cancelledAt}]->(Destination)`.
`TravelerRef` is a lightweight applicative reference (created on demand via
`MERGE` on `userId`), never a copy of identity-service's `User` — same
principle as `Destination.managerId`/`Payment.userId`. `status` is
`ACTIVE` or `CANCELLED`. Same explicit-Cypher-via-`Neo4jClient` approach as
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

Income (payment-service) and report counts (identity-service) are **not**
included and travel-service does not call those services: the dashboard (or a
later phase) combines the three sources into the final performance score. The
ranking is therefore **provisional**: average rating descending, then number of
feedbacks descending, managers without feedback last. It has no smoothing —
one 5-star review outranks a hundred 4.9-star ones.

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

## Configuration

All connection values are externalized via environment variables in
`application.yml` (`NEO4J_HOST`, `NEO4J_PORT`, `NEO4J_USERNAME`,
`NEO4J_PASSWORD`, `SERVER_PORT`). There is no `dbname` variable: Neo4j
Community Edition has a single default database. The service fails fast at
startup if any required variable is absent.

## Not yet implemented

- No second node type: `Activity`/`Accommodation` exist but only as children
  of `Destination`; no dedicated `Travel` node either — see
  docs/lets-travel-architecture-decisions.md §2 for why `Destination` itself
  covers that role instead.
- No pathfinding / multi-hop traversal — only the one-hop `TRANSPORT` query
  above.
- No update/delete on `TRANSPORT` relationships, no anti-duplicate
  protection (see above).
- No `/internal/*` cross-service endpoints.
- No payment integration for a subscription yet
  (docs/lets-travel-architecture-decisions.md §4) — a `SUBSCRIBED` relation
  goes straight to `ACTIVE`, there is no `PENDING_PAYMENT` intermediate
  status in this phase.
- No recommendations yet (docs/lets-travel-architecture-decisions.md §7) —
  `SUBSCRIBED` and `GAVE_FEEDBACK` are now both in the graph, ready to be
  consumed by them. (The Elasticsearch search/autocomplete of §6 exists in the
  code but is not described in this README yet — outside the feedback
  increment.)
- Feedback is immutable: no edit, no delete, no admin moderation of an abusive
  comment (see "Feedback" above). No pagination on the feedback lists.
- Manager statistics are partial by design: no income (payment-service) and no
  report count (identity-service), and the manager ranking is a provisional
  score (rating, then number of feedbacks) — see "Manager statistics" above.
  Assembling the final performance score is left to the dashboard / a later
  phase; travel-service makes no inter-service call for it.