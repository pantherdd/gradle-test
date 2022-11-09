package com.ms.test.java;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

class JavaRuntimeTest {

    @Test
    void testParseInvalid() {
        Assertions.assertThatThrownBy(() -> JavaRuntime.parse("invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Could not parse Java Runtime version: invalid");
    }

    @Test
    void testParseOther() {
        Assertions.assertThat(JavaRuntime.parse("1.7")).isEqualTo(JavaRuntime.OTHER);
    }

    @Test
    @EnabledOnJre(JRE.JAVA_8)
    void testCurrentIsJava8() {
        Assertions.assertThat(JavaRuntime.current()).isEqualTo(JavaRuntime.JAVA_8);
    }

    @Test
    @EnabledOnJre(JRE.JAVA_11)
    void testCurrentIsJava11() {
        Assertions.assertThat(JavaRuntime.current()).isEqualTo(JavaRuntime.JAVA_11);
    }

    @Test
    @EnabledOnJre(JRE.JAVA_17)
    void testCurrentIsJava17() {
        Assertions.assertThat(JavaRuntime.current()).isEqualTo(JavaRuntime.JAVA_17);
    }
}
