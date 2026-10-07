package com.dumaru.pawprint.client.palette;

/**
 * Korean initial-consonant (초성) search: "ㅊㄴㅁ" matches "참나무".
 */
public final class Hangul {
    private static final char[] INITIALS = {
            'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'};
    private static final int SYLLABLE_START = 0xAC00;
    private static final int SYLLABLE_END = 0xD7A3;
    private static final int SYLLABLES_PER_INITIAL = 21 * 28;

    private Hangul() {
    }

    /** Replaces every Hangul syllable with its initial consonant and keeps other characters. */
    public static String initials(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= SYLLABLE_START && c <= SYLLABLE_END) {
                out.append(INITIALS[(c - SYLLABLE_START) / SYLLABLES_PER_INITIAL]);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** True when the query has at least one initial consonant and no full syllables, e.g. "ㅊㄴㅁ". */
    public static boolean isInitialsQuery(String query) {
        boolean hasInitial = false;
        for (char c : query.toCharArray()) {
            if (c >= SYLLABLE_START && c <= SYLLABLE_END) {
                return false;
            }
            if (c >= 'ㄱ' && c <= 'ㅎ') {
                hasInitial = true;
            }
        }
        return hasInitial;
    }
}
