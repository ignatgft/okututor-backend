package com.okututor.backend.search.understanding;

import com.okututor.backend.search.understanding.StructuredQuery.Intent;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based разбор запросов без LLM (спека #4, #6): словари предметов/технологий/
 * целей RU/KG/EN + регулярные выражения для класса, цены, формата, уровня.
 * Простые запросы («математика», «java», «English tutor») разбираются этим парсером
 * с минимальной задержкой; AI-парсер для них не вызывается.
 */
public class RuleBasedQueryParser {

    /** Предмет: canonical key → ключевые слова (порядок записей детерминирован). */
    private static final LinkedHashMap<String, List<String>> SUBJECT_KEYWORDS = new LinkedHashMap<>();

    static {
        SUBJECT_KEYWORDS.put("MATHEMATICS", List.of(
                "математика", "математике", "математику", "математикой", "алгебра", "геометрия",
                "math", "mathematics", "мат"));
        SUBJECT_KEYWORDS.put("ENGLISH", List.of(
                "английский", "английского", "английскому", "англис тили", "англис", "инглиш",
                "english", "англ", "агылча", "агылча тили"));
        SUBJECT_KEYWORDS.put("RUSSIAN", List.of(
                "русский", "русского", "русскому", "рус тили", "russian", "орусча"));
        SUBJECT_KEYWORDS.put("KYRGYZ", List.of(
                "кыргызский", "кыргыз тили", "кыргыз тилин", "кыргыз тилде", "кыргызча", "kyrgyz"));
        SUBJECT_KEYWORDS.put("PHYSICS", List.of("физика", "физике", "физику", "physics"));
        SUBJECT_KEYWORDS.put("CHEMISTRY", List.of("химия", "химии", "химию", "chemistry"));
        SUBJECT_KEYWORDS.put("BIOLOGY", List.of("биология", "биологии", "биологию", "biology"));
        SUBJECT_KEYWORDS.put("HISTORY", List.of("история", "истории", "историю", "тарых", "history"));
        SUBJECT_KEYWORDS.put("GEOGRAPHY", List.of("география", "географии", "географию", "geography"));
        SUBJECT_KEYWORDS.put("PROGRAMMING", List.of(
                "программирование", "программированию", "программированием", "программирования",
                "programming", "coding", "айти", "it", "программалоо"));
        SUBJECT_KEYWORDS.put("DESIGN", List.of("дизайн", "дизайна", "дизайну", "design"));
        SUBJECT_KEYWORDS.put("MUSIC", List.of("музыка", "музыки", "music"));
        SUBJECT_KEYWORDS.put("MOBILE", List.of(
                "мобильная разработка", "мобилка", "mobile", "ios", "android"));
    }

    /** Технология: canonical key → ключевые слова. */
    private static final LinkedHashMap<String, List<String>> TECHNOLOGY_KEYWORDS = new LinkedHashMap<>();

    static {
        TECHNOLOGY_KEYWORDS.put("JAVA", List.of("java", "джава", "джаву", "ява"));
        TECHNOLOGY_KEYWORDS.put("PYTHON", List.of("python", "пайтон", "питон", "пит", "питона", "пайтоне", "pyton", "pythn"));
        TECHNOLOGY_KEYWORDS.put("JAVASCRIPT", List.of("javascript", "жаваскрипт", "джс", "js"));
        TECHNOLOGY_KEYWORDS.put("TYPESCRIPT", List.of("typescript", "тайпскрипт"));
        TECHNOLOGY_KEYWORDS.put("REACT", List.of("react", "реакт", "reactjs"));
        TECHNOLOGY_KEYWORDS.put("SPRING", List.of("spring", "спринг", "spring boot"));
        TECHNOLOGY_KEYWORDS.put("SQL", List.of("sql", "эскьюэл", "сиквел"));
        TECHNOLOGY_KEYWORDS.put("DOCKER", List.of("docker", "докер", "докера"));
        TECHNOLOGY_KEYWORDS.put("KUBERNETES", List.of("kubernetes", "кубернетес", "k8s"));
        TECHNOLOGY_KEYWORDS.put("GO", List.of("golang", "голанг"));
        TECHNOLOGY_KEYWORDS.put("RUST", List.of("rust", "раст"));
        TECHNOLOGY_KEYWORDS.put("KOTLIN", List.of("kotlin", "котлин"));
        TECHNOLOGY_KEYWORDS.put("SWIFT", List.of("swift", "свифт"));
        TECHNOLOGY_KEYWORDS.put("CPP", List.of("c++", "си плюс плюс", "cpp"));
        TECHNOLOGY_KEYWORDS.put("CSHARP", List.of("c#", "си шарп", "csharp", "dotnet", "дотнет"));
    }

    private static final LinkedHashMap<String, List<String>> GOAL_KEYWORDS = new LinkedHashMap<>();

    static {
        GOAL_KEYWORDS.put("ORT", List.of("орт", "ort", "жалпы билим берүү тестирлөөсү"));
        GOAL_KEYWORDS.put("IELTS", List.of("ielts", "айелтс"));
        GOAL_KEYWORDS.put("TOEFL", List.of("toefl", "тоуфл"));
        GOAL_KEYWORDS.put("EGE", List.of("егэ"));
        GOAL_KEYWORDS.put("EXAM", List.of("экзамен", "экзамену", "exam"));
    }

    private static final List<String> TUTOR_WORDS = List.of(
            "репетитор", "репетитора", "репетиторы", "мугалим", "мугалими", "мугалимин",
            "tutor", "tutors", "teacher", "преподаватель", "преподавателя", "устаз");

    private static final List<String> FORMAT_ONLINE = List.of("онлайн", "он-лайн", "online", "онлайнда");
    private static final List<String> FORMAT_OFFLINE = List.of("офлайн", "оффлайн", "offline", "очно");

    private static final LinkedHashMap<String, List<String>> LEVEL_KEYWORDS = new LinkedHashMap<>();

    static {
        LEVEL_KEYWORDS.put("BEGINNER", List.of("для начинающих", "начинающих", "начинающим",
                "beginner", "с нуля", "башталгыч", "баштапкы"));
        LEVEL_KEYWORDS.put("INTERMEDIATE", List.of("intermediate", "средний", "среднего"));
        LEVEL_KEYWORDS.put("ADVANCED", List.of("advanced", "продвинутый", "продвинутых", "продвинутым"));
    }

    private static final List<Pattern> PRICE_MAX_PATTERNS = List.of(
            compilePrice("(?:до|не дороже|дешевле|cheaper than|under|max(?:imum)?)"),
            compilePrice("(?:чоң эмес|аспаган)"));
    private static final List<Pattern> PRICE_MIN_PATTERNS = List.of(
            compilePrice("(?:от|не дешевле|from|кеминде)"));

    private static final Pattern GRADE_BEFORE_CLASS = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(\\d{1,2})\\s*-?\\s*(?:класс|class)(?![\\p{L}\\p{N}])");
    private static final Pattern GRADE_AFTER_CLASS = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:класс|class)\\s*(\\d{1,2})(?![\\p{L}\\p{N}])");

    private static final BigDecimal PRICE_CAP = new BigDecimal("100000000");

    private static Pattern compilePrice(String prefix) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + prefix + "\\s+(\\d{1,9})");
    }

    private static Pattern wordPattern(String keyword) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(keyword) + "(?![\\p{L}\\p{N}])");
    }

    public StructuredQuery parse(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return StructuredQuery.empty();
        }
        String lower = rawQuery.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();

        String subject = findKey(SUBJECT_KEYWORDS, lower);
        String technology = findKey(TECHNOLOGY_KEYWORDS, lower);
        if (technology != null && subject == null) {
            subject = "PROGRAMMING";
        }
        String goal = findKey(GOAL_KEYWORDS, lower);
        Integer grade = findGrade(lower);
        String format = findFormat(lower);
        BigDecimal priceMax = findPrice(lower, PRICE_MAX_PATTERNS);
        BigDecimal priceMin = findPrice(lower, PRICE_MIN_PATTERNS);
        if (priceMin != null && priceMax != null && priceMin.compareTo(priceMax) > 0) {
            priceMin = null;
        }
        String level = findKey(LEVEL_KEYWORDS, lower);
        Intent intent = containsAny(lower, TUTOR_WORDS) ? Intent.FIND_TUTOR : Intent.FIND_COURSE;

        return new StructuredQuery(intent, subject, technology, goal, grade, format,
                priceMax, priceMin, level, null, false);
    }

    /** Алиасы предмета (для hard-фильтра по subject в SQL). */
    public List<String> subjectAliases(String subjectKey) {
        return subjectKey == null ? List.of() : SUBJECT_KEYWORDS.getOrDefault(subjectKey, List.of());
    }

    /** Алиасы технологии (для ranking и матчинга title/subject). */
    public List<String> technologyAliases(String technologyKey) {
        return technologyKey == null ? List.of() : TECHNOLOGY_KEYWORDS.getOrDefault(technologyKey, List.of());
    }

    /** Ключевые слова цели (для расширения текстового матчинга). */
    public List<String> goalKeywords(String goalKey) {
        return goalKey == null ? List.of() : GOAL_KEYWORDS.getOrDefault(goalKey, List.of());
    }

    private String findKey(LinkedHashMap<String, List<String>> dictionary, String lower) {
        for (Map.Entry<String, List<String>> entry : dictionary.entrySet()) {
            if (containsAny(lower, entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private boolean containsAny(String lower, List<String> keywords) {
        for (String keyword : keywords) {
            if (wordPattern(keyword).matcher(lower).find()) {
                return true;
            }
        }
        return false;
    }

    private Integer findGrade(String lower) {
        Matcher before = GRADE_BEFORE_CLASS.matcher(lower);
        if (before.find()) {
            return clampGrade(before.group(1));
        }
        Matcher after = GRADE_AFTER_CLASS.matcher(lower);
        if (after.find()) {
            return clampGrade(after.group(1));
        }
        return null;
    }

    private Integer clampGrade(String digits) {
        try {
            int grade = Integer.parseInt(digits);
            return (grade >= 1 && grade <= 12) ? grade : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String findFormat(String lower) {
        if (containsAny(lower, FORMAT_ONLINE)) {
            return "ONLINE";
        }
        if (containsAny(lower, FORMAT_OFFLINE)) {
            return "OFFLINE";
        }
        return null;
    }

    private BigDecimal findPrice(String lower, List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(lower);
            if (matcher.find()) {
                String digits = matcher.group(1).replaceAll("\\s", "");
                try {
                    BigDecimal value = new BigDecimal(digits);
                    if (value.signum() >= 0 && value.compareTo(PRICE_CAP) <= 0) {
                        return value;
                    }
                } catch (NumberFormatException ignored) {
                    // число вне диапазона — игнорируем
                }
            }
        }
        return null;
    }
}
