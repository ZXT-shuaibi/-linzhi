package com.zhiguang.be.common.api;

/**
 * API 字段错误信息。
 * 用于封装参数校验失败时的字段级错误详情，包含出错字段名和错误原因。
 * 通常在 ErrorResponse 中以列表形式返回，帮助客户端定位具体的参数问题。
 *
 * @param field 出错的字段名称，如 "username"、"email" 等
 * @param reason 错误原因描述，如 "不能为空"、"格式不正确" 等
 */
public record ApiFieldError(String field, String reason) {
}

