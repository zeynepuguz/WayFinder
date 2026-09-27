package com.nomi.wayfinder.dto;

import org.springframework.data.domain.Page;

import java.util.List;

// Stable JSON shape for paginated lists (Spring's Page JSON is not meant to be a public API)
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
