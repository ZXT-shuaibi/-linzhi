package com.zhiguang.be.discover.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record NearbySearchRequest(
    @NotNull(message = "纬度不能为空")
    @DecimalMin(value = "-90.0", message = "纬度必须在 -90 到 90 之间")
    @DecimalMax(value = "90.0", message = "纬度必须在 -90 到 90 之间")
    Double lat,

    @NotNull(message = "经度不能为空")
    @DecimalMin(value = "-180.0", message = "经度必须在 -180 到 180 之间")
    @DecimalMax(value = "180.0", message = "经度必须在 -180 到 180 之间")
    Double lng,

    @NotNull(message = "半径不能为空")
    @Min(value = 100, message = "半径最小 100 米")
    @Max(value = 50000, message = "半径最大 50 公里")
    Integer radius,

    @Min(value = 1, message = "页码最小为 1")
    Integer page,

    @Min(value = 1, message = "每页数量最小为 1")
    @Max(value = 100, message = "每页数量最大为 100")
    Integer size,

    String type,
    String tag
) {
    public NearbySearchRequest {
        if (page == null) page = 1;
        if (size == null) size = 20;
    }
}
