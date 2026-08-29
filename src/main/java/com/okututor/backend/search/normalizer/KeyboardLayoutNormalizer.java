package com.okututor.backend.search.normalizer;

import java.util.HashMap;
import java.util.Map;

public class KeyboardLayoutNormalizer {

    private static final Map<Character, Character> EN_TO_RU = new HashMap<>();
    private static final Map<Character, Character> RU_TO_EN = new HashMap<>();

    static {
        char[] en = "`qwertyuiop[]asdfghjkl;'zxcvbnm,./".toCharArray();
        char[] ru = "ёйцукенгшщзхъфывапролджэячсмитьбю.".toCharArray();
        for (int i = 0; i < en.length; i++) {
            EN_TO_RU.put(en[i], ru[i]);
            EN_TO_RU.put(Character.toUpperCase(en[i]), Character.toUpperCase(ru[i]));
            RU_TO_EN.put(ru[i], en[i]);
            RU_TO_EN.put(Character.toUpperCase(ru[i]), Character.toUpperCase(en[i]));
        }
        EN_TO_RU.put('{', 'х'); EN_TO_RU.put('}', 'ъ');
        EN_TO_RU.put(':', 'ж'); EN_TO_RU.put('"', 'э');
        EN_TO_RU.put('<', 'б'); EN_TO_RU.put('>', 'ю');
        EN_TO_RU.put('?', ',');
        RU_TO_EN.put('х', '{'); RU_TO_EN.put('ъ', '}');
        RU_TO_EN.put('ж', ':'); RU_TO_EN.put('э', '"');
        RU_TO_EN.put('б', '<'); RU_TO_EN.put('ю', '>');
        RU_TO_EN.put(',', '?');
    }

    public String correctLayout(String input) {
        if (input == null || input.isBlank()) return input;

        boolean hasCyrillic = input.chars().anyMatch(c -> c >= 0x0400 && c <= 0x04FF);
        boolean hasLatin = input.chars().anyMatch(c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'));

        // Кириллицу (RU/KG) никогда не трогаем: прежняя RU→EN транслитерация
        // превращала "математика" в "vfnfvfnbrf" и ломала весь русский поиск.
        if (hasCyrillic) {
            return input;
        }

        // Латиница, похожая на русский текст, набранный в английской раскладке
        // ("ghbdtn" -> "привет"), исправляется в кириллицу.
        if (hasLatin && looksLikeCyrillicTypedInLatin(input)) {
            return transliterate(input, EN_TO_RU);
        }

        return input;
    }

    private boolean looksLikeCyrillicTypedInLatin(String input) {
        String common = "ghbdtn prvt ghbvthf pfq hfr yfcnz gjkjujv gjkyjv gjkyj ghbdtn";
        String lower = input.toLowerCase();
        return common.contains(lower) || lower.contains("ghbdtn") || lower.contains("hfr");
    }

    private String transliterate(String input, Map<Character, Character> map) {
        StringBuilder sb = new StringBuilder(input.length());
        for (char c : input.toCharArray()) {
            sb.append(map.getOrDefault(c, c));
        }
        return sb.toString();
    }

    public Map<String, String> generateAlternatives(String input) {
        Map<String, String> alts = new HashMap<>();
        alts.put("original", input);

        String corrected = correctLayout(input);
        if (!corrected.equals(input)) {
            alts.put("corrected", corrected);
        }

        if (hasMixedLayout(input)) {
            alts.put("en", transliterate(input, RU_TO_EN));
            alts.put("ru", transliterate(input, EN_TO_RU));
        }
        return alts;
    }

    private boolean hasMixedLayout(String input) {
        return input.chars().anyMatch(c -> c >= 0x0400 && c <= 0x04FF) &&
               input.chars().anyMatch(c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'));
    }
}