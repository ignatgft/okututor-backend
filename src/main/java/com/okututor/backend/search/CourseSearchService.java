package com.okututor.backend.search;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.course.Course;
import com.okututor.backend.course.CourseRepository;
import com.okututor.backend.course.dto.CourseResponse;
import com.okututor.backend.search.normalizer.KeyboardLayoutNormalizer;
import com.okututor.backend.search.normalizer.SearchQueryNormalizer;
import com.okututor.backend.search.understanding.QueryUnderstandingService;
import com.okututor.backend.search.understanding.RuleBasedQueryParser;
import com.okututor.backend.search.understanding.StructuredQuery;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Поиск курсов:
 * NORMALIZATION → QUERY UNDERSTANDING → HARD FILTERS (SQL) →
 * CANDIDATE RETRIEVAL (LIMIT пул) → RANKING (нормализованные факторы,
 * включая доступность репетитора) → PAGINATION → RESPONSE (+explanations в v2).
 */
@Service
public class CourseSearchService {

    private static final Logger log = LoggerFactory.getLogger(CourseSearchService.class);

    private final CourseRepository courseRepository;
    private final UserRepository userRepository;
    private final SearchQueryNormalizer normalizer;
    private final KeyboardLayoutNormalizer keyboardNormalizer;
    private final RankingService rankingService;
    private final SearchProperties props;
    private final QueryUnderstandingService queryUnderstanding;
    private final RuleBasedQueryParser ruleParser;
    private final SearchAvailabilityService availabilityService;
    private final ExplanationService explanationService;
    private final PersonalizationService personalizationService;

    public CourseSearchService(CourseRepository courseRepository,
                               UserRepository userRepository,
                               SearchQueryNormalizer normalizer,
                               KeyboardLayoutNormalizer keyboardNormalizer,
                               RankingService rankingService,
                               SearchProperties props,
                               QueryUnderstandingService queryUnderstanding,
                               RuleBasedQueryParser ruleParser,
                               SearchAvailabilityService availabilityService,
                               ExplanationService explanationService,
                               PersonalizationService personalizationService) {
        this.courseRepository = courseRepository;
        this.userRepository = userRepository;
        this.normalizer = normalizer;
        this.keyboardNormalizer = keyboardNormalizer;
        this.rankingService = rankingService;
        this.props = props;
        this.queryUnderstanding = queryUnderstanding;
        this.ruleParser = ruleParser;
        this.availabilityService = availabilityService;
        this.explanationService = explanationService;
        this.personalizationService = personalizationService;
    }

    /** Внутренний результат текстового pipeline: отранжированная страница кандидатов + контекст. */
    record SearchPipelineResult(List<CourseSearchProjection> rankedPage,
                                StructuredQuery understood,
                                SearchQueryNormalizer.NormalizedQuery normalized,
                                List<String> subjectAliases,
                                Map<UUID, Double> availability,
                                PersonalizationService.Profile profile,
                                long total,
                                Pageable pageable) {
    }

    @Transactional(readOnly = true)
    public Page<CourseResponse> search(String q, String subject, String locationType, String groupSize,
                                       BigDecimal maxPrice, BigDecimal priceMin,
                                       Double ratingMin, int page, int size, UUID userId) {
        SearchFilters filters = resolveFilters(q, subject, locationType, groupSize,
                maxPrice, priceMin, page, size);
        if (!filters.hasText()) {
            return catalog(filters.subjectFilter(), filters.location(), filters.group(),
                    filters.effectiveMaxPrice(), filters.effectivePriceMin(), ratingMin,
                    filters.pageable());
        }
        SearchPipelineResult result = pipeline(filters, ratingMin, userId);
        List<CourseResponse> content = result.rankedPage().stream()
                .map(this::toResponse)
                .toList();
        return new PageImpl<>(content, result.pageable(), result.total());
    }

    /** Поиск с объяснениями и извлечёнными фильтрами (endpoint v2). */
    @Transactional(readOnly = true)
    public SearchV2Response searchV2(String q, String subject, String locationType, String groupSize,
                                     BigDecimal maxPrice, BigDecimal priceMin,
                                     Double ratingMin, int page, int size, UUID userId) {
        SearchFilters filters = resolveFilters(q, subject, locationType, groupSize,
                maxPrice, priceMin, page, size);
        if (!filters.hasText()) {
            Page<CourseResponse> catalog = catalog(filters.subjectFilter(), filters.location(),
                    filters.group(), filters.effectiveMaxPrice(), filters.effectivePriceMin(),
                    ratingMin, filters.pageable());
            List<SearchV2Response.CourseResult> items = catalog.getContent().stream()
                    .map(dto -> new SearchV2Response.CourseResult(dto, catalogExplanation(dto, filters)))
                    .toList();
            return new SearchV2Response(items, filters.understood(),
                    catalog.getNumber(), catalog.getSize(), catalog.getTotalElements());
        }
        SearchPipelineResult result = pipeline(filters, ratingMin, userId);
        List<SearchV2Response.CourseResult> items = result.rankedPage().stream()
                .map(candidate -> new SearchV2Response.CourseResult(
                        toResponse(candidate),
                        explanationService.explain(candidate, result.understood(),
                                result.normalized(), result.subjectAliases(),
                                candidate.getTeacherId() == null
                                        ? null
                                        : result.availability().get(candidate.getTeacherId()),
                                personalizationService.boost(result.profile(),
                                        candidate.getSubject(), candidate.getTeacherId()))))
                .toList();
        return new SearchV2Response(items, result.understood(),
                result.pageable().getPageNumber(), result.pageable().getPageSize(),
                result.total());
    }

    /** Параметры поиска после нормализации и query understanding. */
    record SearchFilters(String rawQuery,
                         boolean hasText,
                         SearchQueryNormalizer.NormalizedQuery normalized,
                         StructuredQuery understood,
                         String subjectFilter,
                         Course.LocationType location,
                         Course.GroupSize group,
                         BigDecimal effectiveMaxPrice,
                         BigDecimal effectivePriceMin,
                         List<String> subjectAliases,
                         String subjectRegex,
                         Pageable pageable) {
    }

    private SearchFilters resolveFilters(String q, String subject, String locationType, String groupSize,
                                         BigDecimal maxPrice, BigDecimal priceMin, int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), props.getMaxPageSize());
        int pageNumber = Math.max(page, 0);
        Pageable pageable = PageRequest.of(pageNumber, pageSize);

        SearchQueryNormalizer.NormalizedQuery normalized =
                (q == null || q.isBlank()) ? null : normalizer.normalize(q);
        boolean hasText = normalized != null && !normalized.originalTokens().isEmpty();

        // Query Understanding: извлечённые фильтры дополняют явные параметры запроса;
        // явные параметры API всегда имеют приоритет
        StructuredQuery understood = hasText ? queryUnderstanding.understand(q) : StructuredQuery.empty();
        String subjectFilter = blankToNull(subject);
        Course.LocationType location = parseLocationType(locationType);
        if (location == null && understood.format() != null) {
            location = Course.LocationType.valueOf(understood.format());
        }
        Course.GroupSize group = parseGroupSize(groupSize);
        BigDecimal effectiveMaxPrice = maxPrice != null ? maxPrice : understood.priceMax();
        BigDecimal effectivePriceMin = priceMin != null ? priceMin : understood.priceMin();
        // Hard-фильтр по subject — только явный API-параметр. Извлечённый из запроса
        // subject работает как soft-сигнал ранжирования (subjectFactor) и объяснений:
        // при свободных subject-метках курса hard-фильтр по нему давал ложные отсечения.
        List<String> subjectAliases = subjectFilter != null
                ? List.of(subjectFilter)
                : (understood.subject() != null ? ruleParser.subjectAliases(understood.subject()) : List.of());
        String subjectRegex = null;
        validatePriceRange(effectivePriceMin, effectiveMaxPrice);

        return new SearchFilters(q, hasText, normalized, understood, subjectFilter, location, group,
                effectiveMaxPrice, effectivePriceMin, subjectAliases, subjectRegex, pageable);
    }

    private SearchPipelineResult pipeline(SearchFilters filters, Double ratingMin, UUID userId) {
        long startNanos = System.nanoTime();

        String firstToken = filters.normalized().originalTokens().get(0);
        String ftsQuery = filters.normalized().ftsQuery().isEmpty() ? null : filters.normalized().ftsQuery();

        // Токены матчинга: расширенные токены + алиасы технологии + алиасы предмета
        // (для ranking subjectFactor и объяснений)
        List<String> matchTokens = new ArrayList<>(filters.normalized().expandedTokens());
        matchTokens.addAll(ruleParser.technologyAliases(filters.understood().technology()));
        matchTokens.addAll(filters.subjectAliases());

        // Synonym OR-ветка: при извлечённой технологии tech-токен обязателен —
        // общие токены («programming») не могут быть единственным матчем (спека:
        // programming только доп. сигнал ranking). Без технологии — все токены.
        List<String> synTokens = filters.understood().technology() != null
                ? ruleParser.technologyAliases(filters.understood().technology())
                : matchTokens;
        List<String> synWithGoal = new ArrayList<>(synTokens);
        synWithGoal.addAll(ruleParser.goalKeywords(filters.understood().goal()));
        String synRegex = buildSynonymRegex(synWithGoal);

        long dbStart = System.nanoTime();
        List<CourseSearchProjection> candidates = courseRepository.searchCandidates(
                ftsQuery,
                firstToken,
                synRegex,
                firstToken,
                true,
                filters.subjectFilter(),
                filters.subjectRegex(),
                toDbValue(filters.location()),
                toDbValue(filters.group()),
                filters.effectiveMaxPrice(),
                filters.effectivePriceMin(),
                ratingMin,
                Course.Status.APPROVED.name(),
                Math.max(1, props.getCandidateLimit()));
        long total = courseRepository.countCandidates(
                ftsQuery, firstToken, synRegex, true,
                filters.subjectFilter(), filters.subjectRegex(),
                toDbValue(filters.location()), toDbValue(filters.group()),
                filters.effectiveMaxPrice(), filters.effectivePriceMin(), ratingMin,
                Course.Status.APPROVED.name());
        long dbMs = (System.nanoTime() - dbStart) / 1_000_000;

        long rankStart = System.nanoTime();
        Map<UUID, Double> availability = props.getRanking().getAvailabilityWeight() > 0
                ? availabilityService.availabilityScores(
                        candidates.stream().map(CourseSearchProjection::getTeacherId)
                                .filter(Objects::nonNull).toList())
                : Map.of();
        PersonalizationService.Profile profile =
                (props.getPersonalization().isEnabled()
                        && props.getRanking().getPersonalizationWeight() > 0
                        && userId != null)
                        ? personalizationService.profile(userId)
                        : PersonalizationService.Profile.empty();
        RankingService.RankingContext rankingContext = new RankingService.RankingContext(
                matchTokens, filters.understood().technology(), availability, profile);
        List<CourseSearchProjection> ranked = rankingService.rank(candidates, rankingContext);
        long rankMs = (System.nanoTime() - rankStart) / 1_000_000;

        int pageNumber = filters.pageable().getPageNumber();
        int pageSize = filters.pageable().getPageSize();
        int fromIndex = Math.min(pageNumber * pageSize, ranked.size());
        int toIndex = Math.min(fromIndex + pageSize, ranked.size());
        List<CourseSearchProjection> rankedPage = ranked.subList(fromIndex, toIndex);

        logSearch(filters.rawQuery(), 0, dbMs, rankMs, candidates.size(), rankedPage.size(),
                total, (System.nanoTime() - startNanos) / 1_000_000);
        return new SearchPipelineResult(rankedPage, filters.understood(), filters.normalized(),
                filters.subjectAliases(), availability, profile, total, filters.pageable());
    }

    /** Объяснения для каталожного режима (без текстового сигнала). */
    private List<String> catalogExplanation(CourseResponse dto, SearchFilters filters) {
        List<String> reasons = new ArrayList<>();
        if (dto.price_per_hour() != null && filters.effectiveMaxPrice() != null
                && dto.price_per_hour().compareTo(filters.effectiveMaxPrice()) <= 0) {
            reasons.add("price_within_budget");
        }
        if (dto.average_rating() != null && dto.average_rating().doubleValue() >= 4.0) {
            reasons.add("high_rating");
        }
        return reasons;
    }

    /** Каталог с hard-фильтрами: SQL-пагинация по id + батч-загрузка teacher (без N+1). */
    private Page<CourseResponse> catalog(String subject, Course.LocationType location,
                                         Course.GroupSize group, BigDecimal maxPrice,
                                         BigDecimal priceMin, Double ratingMin, Pageable pageable) {
        Page<UUID> ids = courseRepository.catalogIdsWithFilters(
                Course.Status.APPROVED, subject, location, group, maxPrice, priceMin, ratingMin, pageable);
        if (ids.getContent().isEmpty()) {
            return new PageImpl<>(List.of(), pageable, ids.getTotalElements());
        }
        Map<UUID, Course> byId = new HashMap<>();
        for (Course course : courseRepository.findAllWithTeacherByIdIn(ids.getContent())) {
            byId.put(course.getId(), course);
        }
        List<CourseResponse> content = ids.getContent().stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(CourseResponse::from)
                .toList();
        return new PageImpl<>(content, pageable, ids.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Page<CourseResponse> searchWithAlternatives(String q, String subject, String locationType,
                                                       String groupSize, BigDecimal maxPrice,
                                                       BigDecimal priceMin, Double ratingMin,
                                                       int page, int size) {
        // сохранён для обратной совместимости; основной путь — search()
        return search(q, subject, locationType, groupSize, maxPrice, priceMin, ratingMin, page, size, null);
    }

    @Transactional(readOnly = true)
    public Page<CourseResponse> popular(int limit) {
        Pageable pageable = PageRequest.of(0, Math.min(Math.max(limit, 1), 50));
        return courseRepository.findByStatusOrderByAverageRatingDescCreatedAtDesc(
                Course.Status.APPROVED, pageable).map(CourseResponse::from);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> suggestions(String q) {
        if (q == null || q.isBlank()) return List.of();

        SearchQueryNormalizer.NormalizedQuery normalized = normalizer.normalize(q);
        if (normalized.originalTokens().isEmpty()) return List.of();

        StructuredQuery understood = queryUnderstanding.understand(q);
        String firstToken = normalized.originalTokens().get(0);
        String ftsQuery = normalized.ftsQuery().isEmpty() ? null : normalized.ftsQuery();

        List<String> matchTokens = new ArrayList<>(normalized.expandedTokens());
        matchTokens.addAll(ruleParser.technologyAliases(understood.technology()));
        if (understood.subject() != null) {
            matchTokens.addAll(ruleParser.subjectAliases(understood.subject()));
        }
        List<String> synTokens = understood.technology() != null
                ? ruleParser.technologyAliases(understood.technology())
                : matchTokens;
        String synRegex = buildSynonymRegex(synTokens);

        List<CourseSearchProjection> candidates = courseRepository.searchCandidates(
                ftsQuery, firstToken, synRegex, firstToken, true,
                null, null, null, null, null, null, null,
                Course.Status.APPROVED.name(), 5);

        return rankingService.rank(candidates,
                        new RankingService.RankingContext(matchTokens, understood.technology(),
                                Map.of(), PersonalizationService.Profile.empty()))
                .stream()
                .map(this::courseSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> tutorSuggestions(String q) {
        if (q == null || q.isBlank()) return List.of();
        return userRepository.searchTutors(q.trim().toLowerCase(Locale.ROOT), PageRequest.of(0, 3))
                .getContent().stream().map(this::tutorSummary).toList();
    }

    private CourseResponse toResponse(CourseSearchProjection p) {
        return new CourseResponse(
                p.getId(),
                p.getTeacherId(),
                teacherName(p),
                p.getTitle(),
                p.getSubject(),
                p.getCategory(),
                p.getDescription(),
                p.getPricePerHour(),
                p.getCurrency(),
                p.getLocationType(),
                p.getGroupSize(),
                csvToList(p.getDays()),
                csvToList(p.getSpecificDays()),
                p.getExperience(),
                p.getMaxStudents(),
                p.getStatus(),
                p.getRejectionReason(),
                p.getCoverUrl(),
                p.getAverageRating(),
                p.getReviewsCount() == null ? 0 : p.getReviewsCount(),
                p.getCreatedAt());
    }

    /** full_name как в User.getFullName: имя+фамилия, иначе локальная часть email. */
    private String teacherName(CourseSearchProjection p) {
        String joined = ((p.getTeacherFirstName() == null ? "" : p.getTeacherFirstName().trim()) + " "
                + (p.getTeacherLastName() == null ? "" : p.getTeacherLastName().trim())).trim();
        if (!joined.isEmpty()) {
            return joined;
        }
        String email = p.getTeacherEmail();
        return email == null ? null : email.substring(0, Math.max(email.indexOf('@'), 0));
    }

    private static List<String> csvToList(String csv) {
        return (csv == null || csv.isBlank())
                ? List.of()
                : java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private Map<String, Object> courseSummary(CourseSearchProjection c) {
        return Map.of(
                "id", c.getId(),
                "title", c.getTitle(),
                "subject", c.getSubject(),
                "price_per_hour", c.getPricePerHour(),
                "currency", c.getCurrency(),
                "average_rating", c.getAverageRating() != null ? c.getAverageRating() : 0,
                "reviews_count", c.getReviewsCount() == null ? 0 : c.getReviewsCount());
    }

    private Map<String, Object> tutorSummary(User u) {
        return Map.of(
                "id", u.getId(),
                "full_name", (u.getFirstName() != null ? u.getFirstName() : "") + " "
                        + (u.getLastName() != null ? u.getLastName() : ""),
                "avatar_url", u.getAvatarUrl() != null ? u.getAvatarUrl() : "");
    }

    private void validatePriceRange(BigDecimal priceMin, BigDecimal maxPrice) {
        if (priceMin != null && maxPrice != null && priceMin.compareTo(maxPrice) > 0) {
            throw ApiException.validation("price_min must be <= max_price");
        }
    }

    private void logSearch(String q, long normalizeMs, long dbMs, long rankMs,
                           long candidateCount, int resultCount, long total, long totalMs) {
        log.info("SEARCH query_length={} candidate_count={} result_count={} total={} "
                        + "normalization_ms={} db_ms={} ranking_ms={} total_ms={}",
                q == null ? 0 : q.length(), candidateCount, resultCount, total,
                normalizeMs, dbMs, rankMs, totalMs);
    }

    private Course.LocationType parseLocationType(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Course.LocationType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Course.GroupSize parseGroupSize(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Course.GroupSize.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** в БД enum-значения хранятся в нижнем регистре (конвертеры атрибута). */
    private String toDbValue(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Regex-альтернатива для матчинга любого из токенов/синонимов в SQL
     * ({@code lower(col) ~ :q_syn}). Каждый токен матчится как целое слово:
     * {@code (?<![[:alnum:]])token(?![[:alnum:]])} — иначе короткие алиасы («it»)
     * матчились бы подстрокой внутри «with», «итог» и т.п. Токены экранируются
     * от regex-инъекций.
     */
    private static String buildSynonymRegex(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return null;
        String pattern = tokens.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .map(CourseSearchService::escapeRegex)
                .filter(s -> !s.isBlank())
                .distinct()
                .map(s -> "(?<![[:alnum:]])" + s + "(?![[:alnum:]])")
                .collect(java.util.stream.Collectors.joining("|"));
        return pattern.isEmpty() ? null : pattern;
    }

    /** экранирует все не-буквенно-цифровые символы для PostgreSQL ARE. */
    private static String escapeRegex(String value) {
        return value.replaceAll("([^\\p{L}\\p{N} ])", "\\\\$1");
    }
}
