package com.github.xingjianluo.springbootpluginscaffoldjava.tests.extension;

import com.github.xingjianluo.springbootpluginscaffoldjava.extension.Version;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输入配置（版本对）与判定依据（按 . 分段逐段比较）均打印到日志。
 */
class VersionTest {

    private static final Logger log = LoggerFactory.getLogger(VersionTest.class);

    @Test
    void comparesSegmentsNumerically() {
        String[][] cases = {
                {"1.0.0", "1.0.0", "0"},
                {"1.2", "1.2.0", "0"},
                {"2.0.1", "2.0", "1"},
                {"1.9.9", "2.0.0", "-1"},
                {"10.0", "9.99", "1"}
        };
        for (String[] c : cases) {
            int expected = Integer.parseInt(c[2]);
            int actual = Integer.signum(Version.compare(c[0], c[1]));
            log.info("input a={}, b={}; basis=segment-wise numeric compare; expected sign={}, actual={}",
                    c[0], c[1], expected, actual);
            assertEquals(expected, actual);
        }
    }

    @Test
    void evaluatesMinimumVersionSatisfaction() {
        log.info("input required=1.2.0 actual=1.5.0; basis=actual >= required; expected=true");
        assertTrue(Version.satisfies("1.2.0", "1.5.0"));
        log.info("input required=2.0 actual=1.9.9; basis=actual >= required; expected=false");
        assertFalse(Version.satisfies("2.0", "1.9.9"));
    }
}
