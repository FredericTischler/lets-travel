package com.travelplan.travel.dto;

import java.util.List;

/**
 * Generic API response for a paginated list — this project's first, so a
 * small hand-written record rather than reaching for Spring Data's
 * {@code Page}/{@code Pageable}: those are built around {@code Repository}
 * query derivation, which this codebase deliberately does not use for
 * relationship-heavy Neo4j reads (explicit Cypher via {@code Neo4jClient}
 * instead — see e.g. {@code FeedbackRepository}'s class Javadoc). A
 * {@code PageImpl} would also serialize several Spring-internal fields
 * (`pageable`, `sort`, `empty`, ...) this project has no use for; every other
 * response in this codebase is a plain hand-shaped DTO, and this keeps that
 * convention.
 *
 * {@code page} is 0-based. {@code totalPages} is 0 when {@code totalElements}
 * is 0 (an empty result is one page of zero, not zero pages), matching how a
 * consumer would render "page 1 of N" either way.
 */
public class PageResponse<T> {

    private final List<T> content;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;

    public PageResponse(List<T> content, int page, int size, long totalElements) {
        this.content = content;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = (int) Math.max(1, Math.ceil(totalElements / (double) size));
    }

    public List<T> getContent() {
        return content;
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public long getTotalElements() {
        return totalElements;
    }

    public int getTotalPages() {
        return totalPages;
    }
}
