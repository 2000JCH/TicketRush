package com.ticketrush.ticketrush.global.dto;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * 페이지네이션 응답 공통 형태. Spring Data {@code Page}를 그대로 직렬화하면 구조가 불안정하다는
 * 경고가 있어(버전마다 JSON 모양이 달라짐) 필요한 필드만 담은 record로 감싼다.
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <E, T> PagedResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PagedResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
