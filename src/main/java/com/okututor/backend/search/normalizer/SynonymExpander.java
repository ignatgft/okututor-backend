package com.okututor.backend.search.normalizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Расширение токенов запроса синонимами (RU/KG/EN).
 *
 * <p>Обратный индекс alias→canonical даёт O(1) lookup на токен вместо полного
 * обхода групп. Многословные алиасы («англис тили», «машинное обучение»)
 * матчатся предкомпилированными паттернами по исходной строке запроса
 * (или по склейке токенов, если rawQuery не передан).
 *
 * <p>Группы: первый элемент — canonical (латиница lowercase). Алиас, уже
 * принадлежащий другой группе, не перезаписывается (putIfAbsent).
 * Дубликаты и регистр нормализуются структурно.
 */
public class SynonymExpander {

    private static final String[][] DEFAULT_GROUPS = {
            // --- технологии ---
            {"python", "пайтон", "питон", "пит", "питона", "пайтоне", "pyton", "pythn"},
            {"java", "джава", "ява", "джаву"},
            {"javascript", "жаваскрипт", "js", "джс", "node", "nodejs"},
            {"typescript", "тайпскрипт", "ts", "тиэс"},
            {"react", "реакт", "реакте", "reactjs"},
            {"spring", "спринг", "spring boot", "спринг бут", "спрингбут"},
            {"sql", "эскьюэл", "сиквел", "postgresql", "postgres", "mysql"},
            {"docker", "докер", "докера"},
            {"kubernetes", "кубернетес", "k8s", "кубер"},
            {"go", "голанг", "golang"},
            {"rust", "раст"},
            {"kotlin", "котлин"},
            {"swift", "свифт"},
            {"c++", "си плюс плюс", "cpp"},
            {"c#", "си шарп", "csharp", "dotnet", "дотнет", ".net"},
            {"git", "гит"},
            // --- предметы ---
            {"math", "математика", "математике", "математику", "математикой",
                    "мат", "mathematics", "алгебра", "геометрия", "математикалык"},
            {"physics", "физика", "физике", "физику", "физиканы"},
            {"chemistry", "химия", "химии", "химию"},
            {"biology", "биология", "биологии", "биологию"},
            {"history", "история", "истории", "историю", "тарых", "тарыхы"},
            {"geography", "география", "географии", "географию", "жография"},
            {"english", "английский", "английского", "английскому", "англ",
                    "англис", "англис тили", "агылча", "агылча тили"},
            {"russian", "русский", "русского", "русскому", "рус тили", "орусча"},
            {"kyrgyz", "кыргызский", "кыргызча", "кыргыз тили", "кыргыз тилин", "кыргыз тилде"},
            {"programming", "программирование", "программированию",
                    "программированием", "программирования",
                    "coding", "it", "айти", "ит", "программалоо"},
            {"design", "дизайн", "дизайна", "дизайну", "дизайнер"},
            // --- форматы/роли ---
            {"online", "онлайн", "он-лайн", "онлайнда", "дистанционно"},
            {"offline", "офлайн", "оффлайн", "очно", "жекеме-жеке"},
            {"tutor", "репетитор", "репетитора", "репетиторы", "мугалим",
                    "мугалими", "мугалимин", "teacher", "преподаватель", "устаз", "окутуучу"},
            // --- направления ---
            {"frontend", "фронтенд", "фронт", "фронтэнд", "фронтенд разработка"},
            {"backend", "бэкенд", "бекэнд", "бэк", "бэкенд разработка"},
            {"web", "веб", "веб-разработка", "web development", "вебке"},
            {"machine learning", "машинное обучение", "ml", "мл"},
            {"data science", "дата саенс", "анализ данных", "data analysis"},
            {"ai", "ии", "искусственный интеллект", "artificial intelligence",
                    "нейросеть", "нейросети"},
            {"mobile", "мобильная разработка", "ios", "android", "мобилка"},
    };

    /** Обратный индекс: нормализованный alias/canonical → canonical. */
    private final Map<String, String> aliasToCanonical;
    /** Прямой индекс: canonical → все термины группы (включая canonical). */
    private final Map<String, Set<String>> canonicalToAll;
    /** Предкомпилированные паттерны многословных фраз (границы слов). */
    private final Map<String, Pattern> phrasePatterns;

    public SynonymExpander() {
        this(DEFAULT_GROUPS);
    }

    /** Кастомные группы (тесты/замена словаря). Дубликаты и регистр игнорируются. */
    public SynonymExpander(String[][] groups) {
        aliasToCanonical = new HashMap<>();
        canonicalToAll = new LinkedHashMap<>();
        phrasePatterns = new HashMap<>();
        buildIndexes(groups);
    }

    /** Дефолтный словарь + дополнительные группы из конфигурации. */
    public static SynonymExpander withDefaultsPlus(List<List<String>> extraGroups) {
        String[][] merged = DEFAULT_GROUPS;
        if (extraGroups == null || extraGroups.isEmpty()) {
            return new SynonymExpander(merged);
        }
        String[][] combined = new String[DEFAULT_GROUPS.length + extraGroups.size()][];
        System.arraycopy(DEFAULT_GROUPS, 0, combined, 0, DEFAULT_GROUPS.length);
        int i = DEFAULT_GROUPS.length;
        for (List<String> group : extraGroups) {
            combined[i++] = group == null ? new String[0] : group.toArray(new String[0]);
        }
        return new SynonymExpander(combined);
    }

    private void buildIndexes(String[][] groups) {
        if (groups == null) {
            return;
        }
        for (String[] group : groups) {
            if (group == null || group.length == 0) {
                continue;
            }
            String canonical = normalize(group[0]);
            if (canonical == null) {
                continue;
            }

            Set<String> allTerms = new LinkedHashSet<>();
            allTerms.add(canonical);
            aliasToCanonical.put(canonical, canonical);
            registerPhrase(canonical);

            for (int i = 1; i < group.length; i++) {
                String alias = normalize(group[i]);
                if (alias == null || alias.equals(canonical)) {
                    continue;
                }
                // алиас уже принадлежит другой группе — не перезаписываем
                aliasToCanonical.putIfAbsent(alias, canonical);
                allTerms.add(alias);
                registerPhrase(alias);
            }

            // мёрж групп с одинаковым canonical
            canonicalToAll.merge(canonical, Collections.unmodifiableSet(allTerms),
                    (existing, added) -> {
                        Set<String> mergedSet = new LinkedHashSet<>(existing);
                        mergedSet.addAll(added);
                        return Collections.unmodifiableSet(mergedSet);
                    });
        }
    }

    private void registerPhrase(String term) {
        if (term.contains(" ")) {
            phrasePatterns.computeIfAbsent(term, t -> Pattern.compile(
                    "(?<![\\p{L}\\p{N}])" + Pattern.quote(t) + "(?![\\p{L}\\p{N}])"));
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return normalized.isEmpty() ? null : normalized;
    }

    public List<String> expand(List<String> tokens) {
        return expand(tokens, null);
    }

    /**
     * O(T) по числу токенов (обратный индекс) + O(P) по числу многословных
     * паттернов. Если rawQuery не передан, фразы ищутся в склейке токенов.
     */
    public List<String> expand(List<String> tokens, String rawQuery) {
        Set<String> result = new LinkedHashSet<>(tokens);

        for (String token : tokens) {
            String lower = normalize(token);
            if (lower == null) {
                continue;
            }
            String canonical = aliasToCanonical.get(lower);
            if (canonical == null) {
                // пунктация по краям токена игнорируется: «python!!!» → python
                String stripped = lower.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
                canonical = aliasToCanonical.get(stripped);
            }
            if (canonical != null) {
                Set<String> group = canonicalToAll.get(canonical);
                if (group != null) {
                    result.addAll(group);
                }
            }
        }

        String phraseSource = (rawQuery != null && !rawQuery.isBlank())
                ? rawQuery
                : String.join(" ", tokens);
        String lowerSource = phraseSource.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (Map.Entry<String, Pattern> entry : phrasePatterns.entrySet()) {
            if (entry.getValue().matcher(lowerSource).find()) {
                String canonical = aliasToCanonical.get(entry.getKey());
                if (canonical != null) {
                    Set<String> group = canonicalToAll.get(canonical);
                    if (group != null) {
                        result.addAll(group);
                    }
                }
            }
        }

        return new ArrayList<>(result);
    }

    /** canonical → все термины группы (включая canonical). */
    public Map<String, Set<String>> getSynonyms() {
        return Collections.unmodifiableMap(canonicalToAll);
    }
}
