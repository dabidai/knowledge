package com.knowledge.dto;

import lombok.Data;
import org.springframework.data.domain.Page;
import java.util.List;

/** 分页响应 DTO */
@Data
public class PagedResponse<T> {
    private List<T> content;
    private long totalElements;
    private int totalPages;
    private int page;
    private int size;

    public PagedResponse(Page<T> page) {
        this.content = page.getContent();
        this.totalElements = page.getTotalElements();
        this.totalPages = page.getTotalPages();
        this.page = page.getNumber();
        this.size = page.getSize();
    }
}
