package com.zhiguang.be.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * outbox 消息解析工具。
 * 统一解析 Canal 转发后的 outbox JSON 消息，便于不同模块复用。
 */
public final class OutboxMessageUtil {

    private OutboxMessageUtil() {
    }

    /**
     * 提取 outbox 行数据。
     *
     * @param objectMapper JSON 解析器
     * @param message Canal JSON 消息
     * @return 匹配到的 outbox 行列表；格式不符时返回空列表
     */
    public static List<JsonNode> extractRows(ObjectMapper objectMapper, String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode table = root.get("table");
            if (table == null || !"outbox".equalsIgnoreCase(table.asText())) {
                return Collections.emptyList();
            }

            JsonNode type = root.get("type");
            if (type == null || (!"INSERT".equalsIgnoreCase(type.asText()) && !"UPDATE".equalsIgnoreCase(type.asText()))) {
                return Collections.emptyList();
            }

            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                return Collections.emptyList();
            }

            List<JsonNode> rows = new ArrayList<JsonNode>();
            for (JsonNode row : data) {
                rows.add(row);
            }
            return rows;
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }
}
