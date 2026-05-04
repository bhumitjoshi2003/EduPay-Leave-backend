package com.indraacademy.ias_management.util;

public class SchoolContext {

    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    private SchoolContext() {}

    public static void set(Long schoolId) {
        HOLDER.set(schoolId);
    }

    public static Long get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
