package com.okututor.backend.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Конфигурация поиска (prefix {@code search}). Все значения переопределяются
 * переменными окружения (SEARCH_*) и application.yml.
 */
@ConfigurationProperties(prefix = "search")
public class SearchProperties {

    private int maxQueryLength = 200;
    private int defaultPageSize = 20;
    private int maxPageSize = 100;
    /** размер пула кандидатов, по которым работает ranking (спека #13). */
    private int candidateLimit = 100;

    private Ranking ranking = new Ranking();
    private Ai ai = new Ai();
    private Personalization personalization = new Personalization();
    private Embeddings embeddings = new Embeddings();
    private Synonyms synonyms = new Synonyms();

    public int getMaxQueryLength() { return maxQueryLength; }
    public void setMaxQueryLength(int maxQueryLength) { this.maxQueryLength = maxQueryLength; }
    public int getDefaultPageSize() { return defaultPageSize; }
    public void setDefaultPageSize(int defaultPageSize) { this.defaultPageSize = defaultPageSize; }
    public int getMaxPageSize() { return maxPageSize; }
    public void setMaxPageSize(int maxPageSize) { this.maxPageSize = maxPageSize; }
    public int getCandidateLimit() { return candidateLimit; }
    public void setCandidateLimit(int candidateLimit) { this.candidateLimit = candidateLimit; }
    public Ranking getRanking() { return ranking; }
    public void setRanking(Ranking ranking) { this.ranking = ranking; }
    public Ai getAi() { return ai; }
    public void setAi(Ai ai) { this.ai = ai; }
    public Personalization getPersonalization() { return personalization; }
    public void setPersonalization(Personalization personalization) { this.personalization = personalization; }
    public Embeddings getEmbeddings() { return embeddings; }
    public void setEmbeddings(Embeddings embeddings) { this.embeddings = embeddings; }
    public Synonyms getSynonyms() { return synonyms; }
    public void setSynonyms(Synonyms synonyms) { this.synonyms = synonyms; }

    /**
     * Веса ранжирования. Каждый фактор нормализуется в 0..1 до умножения на вес
     * (спека #15); итоговый score дополнительно нормируется на сумму активных весов.
     * Вес 0 означает «фактор выключен» (availability/personalization подключаются
     * на этапах 4–5).
     */
    public static class Ranking {
        private double textWeight = 0.45;
        private double subjectWeight = 0.20;
        private double technologyWeight = 0.0;
        private double ratingWeight = 0.15;
        private double reviewWeight = 0.10;
        private double availabilityWeight = 0.0;
        private double personalizationWeight = 0.0;
        /** параметр насыщения нормализации количества отзывов: reviews/(reviews+k). */
        private int reviewSaturation = 10;
        /** text factor при точном совпадении токена запроса с токеном названия. */
        private double exactTitleBonus = 0.9;
        /** text factor при вхождении технологии (canonical) в название. */
        private double technologyTitleBonus = 0.8;

        public double getTextWeight() { return textWeight; }
        public void setTextWeight(double textWeight) { this.textWeight = textWeight; }
        public double getSubjectWeight() { return subjectWeight; }
        public void setSubjectWeight(double subjectWeight) { this.subjectWeight = subjectWeight; }
        public double getTechnologyWeight() { return technologyWeight; }
        public void setTechnologyWeight(double technologyWeight) { this.technologyWeight = technologyWeight; }
        public double getRatingWeight() { return ratingWeight; }
        public void setRatingWeight(double ratingWeight) { this.ratingWeight = ratingWeight; }
        public double getReviewWeight() { return reviewWeight; }
        public void setReviewWeight(double reviewWeight) { this.reviewWeight = reviewWeight; }
        public double getAvailabilityWeight() { return availabilityWeight; }
        public void setAvailabilityWeight(double availabilityWeight) { this.availabilityWeight = availabilityWeight; }
        public double getPersonalizationWeight() { return personalizationWeight; }
        public void setPersonalizationWeight(double personalizationWeight) { this.personalizationWeight = personalizationWeight; }
        public int getReviewSaturation() { return reviewSaturation; }
        public void setReviewSaturation(int reviewSaturation) { this.reviewSaturation = reviewSaturation; }
        public double getExactTitleBonus() { return exactTitleBonus; }
        public void setExactTitleBonus(double exactTitleBonus) { this.exactTitleBonus = exactTitleBonus; }
        public double getTechnologyTitleBonus() { return technologyTitleBonus; }
        public void setTechnologyTitleBonus(double technologyTitleBonus) { this.technologyTitleBonus = technologyTitleBonus; }
    }

    /** AI-парсер запросов: по умолчанию выключен, поиск полностью автономен (спека #7). */
    public static class Ai {
        private boolean enabled = false;
        private String provider = "";
        private String model = "";
        private int timeoutMs = 3000;
        private int maxTokens = 500;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }

    public static class Personalization {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** Embeddings: архитектурно возможны, по умолчанию выключены (спека #38). */
    public static class Embeddings {
        private boolean enabled = false;
        private String provider = "";
        private String model = "";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    /**
     * Дополнительные группы синонимов из конфигурации — расширение словаря
     * без деплоя: {@code search.synonyms.groups: [["canonical","alias",...], ...]}.
     * Алиас, уже занятый дефолтной группой, не перезаписывается.
     */
    public static class Synonyms {
        private java.util.List<java.util.List<String>> groups = new java.util.ArrayList<>();
        public java.util.List<java.util.List<String>> getGroups() { return groups; }
        public void setGroups(java.util.List<java.util.List<String>> groups) {
            this.groups = groups == null ? new java.util.ArrayList<>() : groups;
        }
    }
}
