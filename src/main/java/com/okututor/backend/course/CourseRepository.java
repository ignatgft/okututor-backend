package com.okututor.backend.course;

import com.okututor.backend.search.CourseSearchProjection;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseRepository extends JpaRepository<Course, UUID>, JpaSpecificationExecutor<Course> {

    @Query("select c from Course c join fetch c.teacher where c.id = :id")
    Optional<Course> findByIdWithTeacher(@Param("id") UUID id);

    @Query("""
            select distinct c from Course c
            left join fetch c.teacher
            where c.id in :ids
            """)
    List<Course> findAllWithTeacherByIdIn(@Param("ids") List<UUID> ids);

    @Query(value = """
            select c from Course c
            join fetch c.teacher
            where c.teacher.id = :teacherId
            order by c.createdAt desc
            """,
            countQuery = "select count(c) from Course c where c.teacher.id = :teacherId")
    Page<Course> findByTeacherIdOrderByCreatedAtDesc(@Param("teacherId") UUID teacherId, Pageable pageable);

    Page<Course> findByTeacherIdAndStatusOrderByCreatedAtDesc(UUID teacherId, Course.Status status,
                                                              Pageable pageable);

    @Query(value = """
            select c from Course c
            join fetch c.teacher
            where c.status = :status
            order by c.averageRating desc nulls last, c.createdAt desc, c.id desc
            """,
            countQuery = "select count(c) from Course c where c.status = :status")
    Page<Course> findByStatusOrderByAverageRatingDescCreatedAtDesc(@Param("status") Course.Status status,
                                                                   Pageable pageable);

    @Query(value = """
            select c from Course c
            join fetch c.teacher
            where c.status = :status
            order by c.createdAt desc
            """,
            countQuery = "select count(c) from Course c where c.status = :status")
    Page<Course> findByStatusOrderByCreatedAtDesc(@Param("status") Course.Status status, Pageable pageable);

    @Query(value = "select c from Course c join fetch c.teacher order by c.createdAt desc",
            countQuery = "select count(c) from Course c")
    Page<Course> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
            select c from Course c
            join fetch c.teacher
            where (:subject is null or lower(c.subject) = lower(cast(:subject as string)))
              and (cast(:q as string) is null
                   or lower(c.title) like concat('%', lower(cast(:q as string)), '%')
                   or lower(coalesce(c.description,'')) like concat('%', lower(cast(:q as string)), '%'))
              and (:locationType is null or c.locationType = :locationType)
              and (:groupSize is null or c.groupSize = :groupSize)
              and (:maxPrice is null or c.pricePerHour <= :maxPrice)
              and (:priceMin is null or c.pricePerHour >= :priceMin)
              and (:ratingMin is null or c.averageRating >= :ratingMin)
              and (:status is null or c.status = :status)
            order by c.createdAt desc
            """)
    Page<Course> search(@Param("q") String q,
                        @Param("subject") String subject,
                        @Param("locationType") Course.LocationType locationType,
                        @Param("groupSize") Course.GroupSize groupSize,
                        @Param("maxPrice") BigDecimal maxPrice,
                        @Param("priceMin") BigDecimal priceMin,
                        @Param("ratingMin") Double ratingMin,
                        @Param("status") Course.Status status,
                        Pageable pageable);

    @Query(value = """
            SELECT c.*, ts_rank_cd(c.search_vector, plainto_tsquery('english', :q)) AS rank
            FROM courses c
            WHERE c.search_vector @@ plainto_tsquery('english', :q)
              AND c.status = 'APPROVED'
            ORDER BY rank DESC, c.created_at DESC
            """,
            countQuery = """
            SELECT count(*) FROM courses c
            WHERE c.search_vector @@ plainto_tsquery('english', :q)
              AND c.status = 'APPROVED'
            """,
            nativeQuery = true)
    Page<Course> searchFts(@Param("q") String q, Pageable pageable);

    @Query(value = """
            SELECT c.*, 
                   (CASE 
                        WHEN lower(c.title) = lower(:q_exact) THEN 100
                        WHEN lower(c.title) LIKE lower(:q_prefix) THEN 80
                        WHEN lower(c.title) LIKE lower(:q_contains) THEN 60
                        WHEN lower(c.subject) = lower(:q_exact) THEN 50
                        WHEN lower(c.category) = lower(:q_exact) THEN 40
                        ELSE 0
                    END) AS exact_score,
                   ts_rank_cd(c.search_vector, plainto_tsquery('english', :q_fts)) AS fts_score,
                   similarity(lower(c.title), lower(:q_sim)) AS trigram_score
            FROM courses c
            WHERE c.status = :status
              AND (:subject IS NULL OR lower(c.subject) = lower(:subject))
              AND (:locationType IS NULL OR c.location_type = :locationType)
              AND (:groupSize IS NULL OR c.group_size = :groupSize)
              AND (:maxPrice IS NULL OR c.price_per_hour <= :maxPrice)
              AND (:priceMin IS NULL OR c.price_per_hour >= :priceMin)
              AND (:ratingMin IS NULL OR c.average_rating >= :ratingMin)
              AND (
                  :q_fts IS NOT NULL AND c.search_vector @@ plainto_tsquery('english', :q_fts)
                  OR :q_fuzzy IS NOT NULL AND c.title % :q_fuzzy
                  OR :q_syn IS NOT NULL AND (
                      lower(c.title) ~ :q_syn
                      OR lower(c.subject) ~ :q_syn
                      OR lower(c.category) ~ :q_syn
                      OR lower(c.description) ~ :q_syn
                  )
              )
            ORDER BY 
                exact_score DESC,
                fts_score DESC,
                trigram_score DESC,
                c.average_rating DESC NULLS LAST,
                c.created_at DESC
            """,
            countQuery = """
            SELECT count(*) FROM courses c
            WHERE c.status = :status
              AND (:subject IS NULL OR lower(c.subject) = lower(:subject))
              AND (:locationType IS NULL OR c.location_type = :locationType)
              AND (:groupSize IS NULL OR c.group_size = :groupSize)
              AND (:maxPrice IS NULL OR c.price_per_hour <= :maxPrice)
              AND (:priceMin IS NULL OR c.price_per_hour >= :priceMin)
              AND (:ratingMin IS NULL OR c.average_rating >= :ratingMin)
              AND (
                  :q_fts IS NOT NULL AND c.search_vector @@ plainto_tsquery('english', :q_fts)
                  OR :q_fuzzy IS NOT NULL AND c.title % :q_fuzzy
                  OR :q_syn IS NOT NULL AND (
                      lower(c.title) ~ :q_syn
                      OR lower(c.subject) ~ :q_syn
                      OR lower(c.category) ~ :q_syn
                      OR lower(c.description) ~ :q_syn
                  )
              )
            """,
            nativeQuery = true)
    Page<Course> searchAdvanced(@Param("q_fts") String qFts,
                                @Param("q_fuzzy") String qFuzzy,
                                @Param("q_syn") String qSyn,
                                @Param("q_exact") String qExact,
                                @Param("q_prefix") String qPrefix,
                                @Param("q_contains") String qContains,
                                @Param("q_sim") String qSim,
                                @Param("subject") String subject,
                                @Param("locationType") String locationType,
                                @Param("groupSize") String groupSize,
                                @Param("maxPrice") BigDecimal maxPrice,
                                @Param("priceMin") BigDecimal priceMin,
                                @Param("ratingMin") Double ratingMin,
                                @Param("status") String status,
                                Pageable pageable);

    @Query(value = """
            SELECT c.*, 
                   (CASE 
                        WHEN lower(c.title) = lower(:q_orig) THEN 100
                        WHEN lower(c.title) = lower(:q_corr) THEN 90
                        ELSE 0
                    END) AS exact_score,
                   ts_rank_cd(c.search_vector, plainto_tsquery('english', :q_orig)) AS fts_orig,
                   ts_rank_cd(c.search_vector, plainto_tsquery('english', :q_corr)) AS fts_corr,
                   similarity(lower(c.title), lower(:q_orig)) AS trigram_orig,
                   similarity(lower(c.title), lower(:q_corr)) AS trigram_corr
            FROM courses c
            WHERE c.status = :status
              AND (:subject IS NULL OR lower(c.subject) = lower(:subject))
              AND (:locationType IS NULL OR c.location_type = :locationType)
              AND (:groupSize IS NULL OR c.group_size = :groupSize)
              AND (:maxPrice IS NULL OR c.price_per_hour <= :maxPrice)
              AND (:priceMin IS NULL OR c.price_per_hour >= :priceMin)
              AND (:ratingMin IS NULL OR c.average_rating >= :ratingMin)
              AND (
                  c.search_vector @@ plainto_tsquery('english', :q_orig)
                  OR c.search_vector @@ plainto_tsquery('english', :q_corr)
                  OR c.title % :q_orig
                  OR c.title % :q_corr
                  OR lower(c.title) LIKE concat('%', lower(:q_orig), '%')
                  OR lower(c.title) LIKE concat('%', lower(:q_corr), '%')
              )
            ORDER BY 
                exact_score DESC,
                GREATEST(fts_orig, fts_corr) DESC,
                GREATEST(trigram_orig, trigram_corr) DESC,
                c.average_rating DESC NULLS LAST,
                c.created_at DESC
            """,
            countQuery = """
            SELECT count(*) FROM courses c
            WHERE c.status = :status
              AND (:subject IS NULL OR lower(c.subject) = lower(:subject))
              AND (:locationType IS NULL OR c.location_type = :locationType)
              AND (:groupSize IS NULL OR c.group_size = :groupSize)
              AND (:maxPrice IS NULL OR c.price_per_hour <= :maxPrice)
              AND (:priceMin IS NULL OR c.price_per_hour >= :priceMin)
              AND (:ratingMin IS NULL OR c.average_rating >= :ratingMin)
              AND (
                  c.search_vector @@ plainto_tsquery('english', :q_orig)
                  OR c.search_vector @@ plainto_tsquery('english', :q_corr)
                  OR c.title % :q_orig
                  OR c.title % :q_corr
              )
            """,
            nativeQuery = true)
    Page<Course> searchWithAlternatives(@Param("q_orig") String qOrig,
                                        @Param("q_corr") String qCorr,
                                        @Param("subject") String subject,
                                        @Param("locationType") String locationType,
                                        @Param("groupSize") String groupSize,
                                        @Param("maxPrice") BigDecimal maxPrice,
                                        @Param("priceMin") BigDecimal priceMin,
                                        @Param("ratingMin") Double ratingMin,
                                        @Param("status") String status,
                                        Pageable pageable);

    /**
     * Кандидатский поисковый запрос (этап 2): hard-фильтры + FTS(ru/en) + trgm +
     * синоним-regex, проекция с teacher в одном JOIN (без N+1), LIMIT пула кандидатов.
     * Ранжирование — в RankingService, пагинация — по отранжированному пулу.
     */
    @Query(value = """
            SELECT c.id AS "id",
                   c.title AS "title",
                   c.subject AS "subject",
                   c.category AS "category",
                   c.description AS "description",
                   c.price_per_hour AS "pricePerHour",
                   c.currency AS "currency",
                   c.location_type AS "locationType",
                   c.group_size AS "groupSize",
                   c.days AS "days",
                   c.specific_days AS "specificDays",
                   c.experience AS "experience",
                   c.max_students AS "maxStudents",
                   c.status AS "status",
                   c.rejection_reason AS "rejectionReason",
                   c.cover_url AS "coverUrl",
                   c.average_rating AS "averageRating",
                   c.reviews_count AS "reviewsCount",
                   c.created_at AS "createdAt",
                   u.id AS "teacherId",
                   u.first_name AS "teacherFirstName",
                   u.last_name AS "teacherLastName",
                   u.email AS "teacherEmail",
                   GREATEST(
                       COALESCE(ts_rank_cd(c.search_vector_ru, to_tsquery('russian', :q_fts)), 0),
                       COALESCE(ts_rank_cd(c.search_vector, to_tsquery('english', :q_fts)), 0)
                   ) AS "textScore",
                    GREATEST(
                        COALESCE(similarity(lower(c.title), lower(:q_trgm)), 0),
                        COALESCE(word_similarity(lower(:q_trgm), lower(c.title)), 0)
                    ) AS "trgmScore",
                    (CASE WHEN lower(c.title) = lower(:q_exact) THEN 1 ELSE 0 END) AS "exactMatch"
             FROM courses c
             JOIN users u ON u.id = c.teacher_id
             WHERE c.status = :status
               AND (:subject IS NULL OR lower(c.subject) = lower(:subject))
               AND (:subject_regex IS NULL OR lower(c.subject) ~ :subject_regex)
               AND (:locationType IS NULL OR c.location_type = :locationType)
               AND (:groupSize IS NULL OR c.group_size = :groupSize)
               AND (:maxPrice IS NULL OR c.price_per_hour <= :maxPrice)
               AND (:priceMin IS NULL OR c.price_per_hour >= :priceMin)
               AND (:ratingMin IS NULL OR c.average_rating >= :ratingMin)
               AND (
                   :has_text = FALSE
                   OR (:q_fts IS NOT NULL AND (
                           c.search_vector_ru @@ to_tsquery('russian', :q_fts)
                           OR c.search_vector @@ to_tsquery('english', :q_fts)))
                   OR (:q_trgm IS NOT NULL AND (
                           lower(c.title) % lower(:q_trgm)
                           OR lower(:q_trgm) %> lower(c.title)))
                   OR (:q_syn IS NOT NULL AND (
                           lower(c.title) ~ :q_syn
                           OR lower(c.subject) ~ :q_syn
                           OR lower(c.category) ~ :q_syn
                           OR lower(c.description) ~ :q_syn))
               )
            ORDER BY "textScore" DESC, "trgmScore" DESC, "exactMatch" DESC,
                     c.average_rating DESC NULLS LAST, c.id DESC
            LIMIT :candidateLimit
            """, nativeQuery = true)
    List<CourseSearchProjection> searchCandidates(@Param("q_fts") String qFts,
                                                  @Param("q_trgm") String qTrgm,
                                                  @Param("q_syn") String qSyn,
                                                  @Param("q_exact") String qExact,
                                                  @Param("has_text") boolean hasText,
                                                  @Param("subject") String subject,
                                                  @Param("subject_regex") String subjectRegex,
                                                  @Param("locationType") String locationType,
                                                  @Param("groupSize") String groupSize,
                                                  @Param("maxPrice") BigDecimal maxPrice,
                                                  @Param("priceMin") BigDecimal priceMin,
                                                  @Param("ratingMin") Double ratingMin,
                                                  @Param("status") String status,
                                                  @Param("candidateLimit") int candidateLimit);

    /** Число кандидатов по тем же предикатам — для totalElements пагинации. */
    @Query(value = """
            SELECT count(*)
            FROM courses c
            WHERE c.status = :status
              AND (:subject IS NULL OR lower(c.subject) = lower(:subject))
              AND (:subject_regex IS NULL OR lower(c.subject) ~ :subject_regex)
              AND (:locationType IS NULL OR c.location_type = :locationType)
              AND (:groupSize IS NULL OR c.group_size = :groupSize)
              AND (:maxPrice IS NULL OR c.price_per_hour <= :maxPrice)
              AND (:priceMin IS NULL OR c.price_per_hour >= :priceMin)
              AND (:ratingMin IS NULL OR c.average_rating >= :ratingMin)
              AND (
                  :has_text = FALSE
                  OR (:q_fts IS NOT NULL AND (
                          c.search_vector_ru @@ to_tsquery('russian', :q_fts)
                          OR c.search_vector @@ to_tsquery('english', :q_fts)))
                  OR (:q_trgm IS NOT NULL AND (
                          lower(c.title) % lower(:q_trgm)
                          OR lower(:q_trgm) %> lower(c.title)))
                  OR (:q_syn IS NOT NULL AND (
                          lower(c.title) ~ :q_syn
                          OR lower(c.subject) ~ :q_syn
                          OR lower(c.category) ~ :q_syn
                          OR lower(c.description) ~ :q_syn))
              )
            """, nativeQuery = true)
    long countCandidates(@Param("q_fts") String qFts,
                         @Param("q_trgm") String qTrgm,
                         @Param("q_syn") String qSyn,
                         @Param("has_text") boolean hasText,
                         @Param("subject") String subject,
                         @Param("subject_regex") String subjectRegex,
                         @Param("locationType") String locationType,
                         @Param("groupSize") String groupSize,
                         @Param("maxPrice") BigDecimal maxPrice,
                         @Param("priceMin") BigDecimal priceMin,
                         @Param("ratingMin") Double ratingMin,
                         @Param("status") String status);

    /**
     * Идентификаторы курсов каталога с hard-фильтрами (без текстового сигнала).
     * SQL-пагинация по id: join fetch teacher делается вторым батч-запросом,
     * поэтому in-memory пагинации (HHH000104) нет.
     */
    @Query(value = """
            select c.id from Course c
            where c.status = :status
              and (:subject is null or lower(c.subject) = lower(cast(:subject as string)))
              and (:locationType is null or c.locationType = :locationType)
              and (:groupSize is null or c.groupSize = :groupSize)
              and (:maxPrice is null or c.pricePerHour <= :maxPrice)
              and (:priceMin is null or c.pricePerHour >= :priceMin)
              and (:ratingMin is null or c.averageRating >= :ratingMin)
            order by c.averageRating desc nulls last, c.createdAt desc, c.id desc
            """,
            countQuery = """
            select count(c.id) from Course c
            where c.status = :status
              and (:subject is null or lower(c.subject) = lower(cast(:subject as string)))
              and (:locationType is null or c.locationType = :locationType)
              and (:groupSize is null or c.groupSize = :groupSize)
              and (:maxPrice is null or c.pricePerHour <= :maxPrice)
              and (:priceMin is null or c.pricePerHour >= :priceMin)
              and (:ratingMin is null or c.averageRating >= :ratingMin)
            """)
    Page<UUID> catalogIdsWithFilters(@Param("status") Course.Status status,
                                     @Param("subject") String subject,
                                     @Param("locationType") Course.LocationType locationType,
                                     @Param("groupSize") Course.GroupSize groupSize,
                                     @Param("maxPrice") BigDecimal maxPrice,
                                     @Param("priceMin") BigDecimal priceMin,
                                     @Param("ratingMin") Double ratingMin,
                                     Pageable pageable);

    @Modifying
    @Query("""
            update Course c
            set c.averageRating = :avg, c.reviewsCount = :count, c.updatedAt = :now
            where c.id = :id
            """)
    int refreshRatingAggregate(@Param("id") UUID id,
                               @Param("avg") BigDecimal avg,
                               @Param("count") long count,
                               @Param("now") java.time.Instant now);
}