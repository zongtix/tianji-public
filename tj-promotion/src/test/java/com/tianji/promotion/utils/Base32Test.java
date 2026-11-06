package com.tianji.promotion.utils;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class Base32Test {
    @Test
    void encodesZeroAsACharacter() {
        assertEquals("6", Base32.encode(0L));
        assertEquals(0L, Base32.decode(Base32.encode(0L)));
    }

    @Test
    void preservesExistingAlphabetAndLowDigitFirstOrder() {
        assertEquals("C", Base32.encode(1L));
        assertEquals("L", Base32.encode(31L));
        assertEquals("6C", Base32.encode(32L));
    }

    @Test
    void roundTripsAllLongBits() {
        for (long value : new long[]{1, 31, 32, 1_000_000_000L, Long.MAX_VALUE, Long.MIN_VALUE, -1}) {
            assertEquals(value, Base32.decode(Base32.encode(value)));
        }
        Random random = new Random(20261009L);
        for (int i = 0; i < 1000; i++) {
            long value = random.nextLong();
            assertEquals(value, Base32.decode(Base32.encode(value)));
        }
    }

    @Test
    void rejectsInvalidLongEncodingInsteadOfSilentlyDecoding() {
        for (String code : new String[]{null, "", "I", "0", "c", "6 C", "66666666666666", "666666666666P"}) {
            assertThrows(IllegalArgumentException.class, () -> Base32.decode(code));
        }
    }

    @Test
    void roundTripsByteArraysIncludingPartialGroups() {
        Random random = new Random(20261009L);
        for (int length = 0; length <= 128; length++) {
            byte[] raw = new byte[length];
            random.nextBytes(raw);
            assertArrayEquals(raw, Base32.decode2Byte(Base32.encode(raw)), "length=" + length);
        }
    }

    @Test
    void rejectsInvalidByteEncoding() {
        for (String code : new String[]{null, "I6", "6", "666", "666666", "6P"}) {
            assertThrows(IllegalArgumentException.class, () -> Base32.decode2Byte(code));
        }
    }
}
