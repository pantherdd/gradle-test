package com.ms.test.java;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;

/**
 * Java runtime utilities.
 */
public enum JavaRuntime {

    /**
     * Java 8 runtime.
     */
    JAVA_8,

    /**
     * Java 11 runtime.
     */
    JAVA_11,

    /**
     * Java 17 runtime.
     */
    JAVA_17,

    /**
     * Other Java runtime.
     */
    OTHER;

    /**
     * Returns the {@link JavaRuntime} of the currently executing JVM.
     *
     * @return The {@link JavaRuntime} of the currently executing JVM.
     */
    @Nonnull
    public static JavaRuntime current() {
        return parse(System.getProperty("java.version"));
    }

    /**
     * Parses the given version string into a {@link JavaRuntime}.
     *
     * @param version The version string to parse.
     * @return The parsed {@link JavaRuntime}.
     */
    @Nonnull
    public static JavaRuntime parse(@Nonnull String version) {
        Matcher matcher = Pattern.compile("^(?:1\\.)?(\\d+)").matcher(version);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Could not parse Java Runtime version: " + version);
        }
        // Let's see whether the Jacoco coverage report shows a merge of the different test runs or not
        switch (matcher.group(1)) {
            case "8":
                return JAVA_8;
            case "11":
                return JAVA_11;
            case "17":
                return JAVA_17;
            default:
                return OTHER;
        }
    }
}
