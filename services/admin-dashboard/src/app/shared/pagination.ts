/**
 * Shape of a paginated travel-service response (its `PageResponse<T>` Java
 * DTO): `page` is 0-based. `page`/`size` in the request are clamped by the
 * backend rather than rejected (0 or above, 1..100) — a client never needs to
 * validate them itself before sending.
 */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
