package com.okututor.backend.search;

import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.enrollment.EnrollmentRepository;
import com.okututor.backend.search.normalizer.KeyboardLayoutNormalizer;
import com.okututor.backend.search.normalizer.SearchQueryNormalizer;
import com.okututor.backend.search.normalizer.SynonymExpander;
import com.okututor.backend.search.understanding.AiQueryParser;
import com.okututor.backend.search.understanding.DisabledAiQueryParser;
import com.okututor.backend.search.understanding.HttpAiQueryParser;
import com.okututor.backend.search.understanding.QueryUnderstandingService;
import com.okututor.backend.search.understanding.RuleBasedQueryParser;
import com.okututor.backend.tutors.AvailabilitySlotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfig {

    @Bean
    public KeyboardLayoutNormalizer keyboardLayoutNormalizer() {
        return new KeyboardLayoutNormalizer();
    }

    @Bean
    public SynonymExpander synonymExpander(SearchProperties searchProperties) {
        return SynonymExpander.withDefaultsPlus(searchProperties.getSynonyms().getGroups());
    }

    @Bean
    public SearchQueryNormalizer searchQueryNormalizer(KeyboardLayoutNormalizer keyboardNormalizer,
                                                        SynonymExpander synonymExpander) {
        return new SearchQueryNormalizer(keyboardNormalizer, synonymExpander);
    }

    @Bean
    public PersonalizationService personalizationService(BookingRepository bookingRepository,
                                                         EnrollmentRepository enrollmentRepository) {
        return new PersonalizationService(bookingRepository, enrollmentRepository);
    }

    @Bean
    public RankingService rankingService(SearchProperties searchProperties,
                                         PersonalizationService personalizationService) {
        return new RankingService(searchProperties, personalizationService);
    }

    @Bean
    public RuleBasedQueryParser ruleBasedQueryParser() {
        return new RuleBasedQueryParser();
    }

    /** AI-парсер по умолчанию выключен; включается search.ai.enabled=true + endpoint. */
    @Bean
    public AiQueryParser aiQueryParser(SearchProperties props,
                                       @Value("${OKUTUTOR_SEARCH_AI_ENDPOINT:}") String endpoint,
                                       @Value("${OKUTUTOR_SEARCH_AI_API_KEY:}") String apiKey) {
        if (!props.getAi().isEnabled()) {
            return new DisabledAiQueryParser();
        }
        return new HttpAiQueryParser(props.getAi(), endpoint, apiKey);
    }

    @Bean
    public QueryUnderstandingService queryUnderstandingService(RuleBasedQueryParser ruleParser,
                                                               AiQueryParser aiParser,
                                                               SearchProperties props) {
        return new QueryUnderstandingService(ruleParser, aiParser, props);
    }

    @Bean
    public SearchAvailabilityService searchAvailabilityService(AvailabilitySlotRepository slotRepository,
                                                               BookingRepository bookingRepository) {
        return new SearchAvailabilityService(slotRepository, bookingRepository);
    }

    @Bean
    public ExplanationService explanationService(RuleBasedQueryParser ruleParser) {
        return new ExplanationService(ruleParser);
    }
}
