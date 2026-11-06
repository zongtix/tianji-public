package com.tianji.promotion.utils;

import com.tianji.common.exceptions.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CodeUtilTest {
    @Test
    void roundTripsAllFreshValuesAndSerialBoundaries() {
        long[] serials = {0, 1, 31, 32, 1_000_000_000L, 0x7FFFFFFFL, 0x80000000L, 0xFFFFFFFFL};
        for (long serial : serials) {
            for (int fresh = 0; fresh < 16; fresh++) {
                String code = CodeUtil.generateCode(serial, fresh);
                assertTrue(code.matches("^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{8,10}$"));
                assertEquals(serial, CodeUtil.parseCode(code));
            }
        }
    }

    @Test
    void rejectsOutOfRangeSerialsInsteadOfOverwritingFreshBits() {
        for (long serial : new long[]{-1, Long.MIN_VALUE, 0x100000000L, 0x100000001L, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> CodeUtil.generateCode(serial, 1));
        }
    }

    @Test
    void usesOnlyLowFourBitsOfCouponId() {
        assertEquals(CodeUtil.generateCode(123, 3), CodeUtil.generateCode(123, 19));
    }

    @Test
    void generatesDistinctCodesForSampleAcrossFreshValues() {
        Set<String> codes = new HashSet<>();
        for (int serial = 1; serial <= 1000; serial++) {
            for (int fresh = 0; fresh < 16; fresh++) {
                assertTrue(codes.add(CodeUtil.generateCode(serial, fresh)));
            }
        }
    }

    @Test
    void rejectsInvalidFormatAndAlteredCheckCode() {
        assertThrows(BadRequestException.class, () -> CodeUtil.parseCode(null));
        assertThrows(BadRequestException.class, () -> CodeUtil.parseCode(""));
        assertThrows(BadRequestException.class, () -> CodeUtil.parseCode("IIIIIIIIII"));
        String code = CodeUtil.generateCode(1_000_000_000L, 3);
        // 改动校验码高位，保留用于选择异或表的低四位。
        String altered = Base32.encode(Base32.decode(code) ^ (1L << 49));
        assertThrows(BadRequestException.class, () -> CodeUtil.parseCode(altered));
    }
}
