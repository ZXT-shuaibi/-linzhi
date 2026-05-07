package com.zhiguang.be.common.util;

public final class Texts {

    private Texts() {
    }

    public static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
