package com.zhiguang.be.social;

/**
 * 页码分页元数据。
 */
public class PageMeta {

    private final int page;
    private final int size;
    private final long total;
    private final int totalPages;
    private final boolean hasNext;

    /**
     * 构造分页元数据对象。
     *
     * @param page 当前页码
     * @param size 分页大小
     * @param total 总记录数
     * @param totalPages 总页数
     * @param hasNext 是否还有下一页
     */
    public PageMeta(int page, int size, long total, int totalPages, boolean hasNext) {
        this.page = page;
        this.size = size;
        this.total = total;
        this.totalPages = totalPages;
        this.hasNext = hasNext;
    }

    /**
     * 根据页码、分页大小和总量构造分页元数据。
     *
     * @param page 当前页码
     * @param size 分页大小
     * @param total 总记录数
     * @return 分页元数据
     */
    public static PageMeta of(int page, int size, long total) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        int totalPages = total <= 0 ? 0 : (int) Math.ceil((double) total / safeSize);
        boolean hasNext = safePage < totalPages;
        return new PageMeta(safePage, safeSize, total, totalPages, hasNext);
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public long getTotal() {
        return total;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public boolean isHasNext() {
        return hasNext;
    }
}
