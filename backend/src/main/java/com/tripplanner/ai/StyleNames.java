package com.tripplanner.ai;

import java.util.List;

/** Accept legacy clients and stored drafts while emitting canonical style names. */
public final class StyleNames {
    private StyleNames() {}
    public static String canonical(String value) {
        return "액티비티".equals(value) ? "체험" : value;
    }
    public static List<String> canonical(List<String> values) {
        return values == null ? null : values.stream().map(StyleNames::canonical).distinct().toList();
    }
}
