package com.example.tradingpt.domain.lecture.repository;

import static com.example.tradingpt.domain.lecture.entity.QChapter.chapter;
import static com.example.tradingpt.domain.lecture.entity.QLecture.lecture;
import static com.example.tradingpt.domain.lecture.entity.QLectureProgress.lectureProgress;

import com.querydsl.core.Tuple;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.example.tradingpt.domain.lecture.dto.response.ChapterBlockDTO;
import com.example.tradingpt.domain.lecture.dto.response.LectureResponseDTO;
import com.example.tradingpt.domain.lecture.entity.QLectureProgress;
import com.example.tradingpt.domain.lecture.enums.ChapterType;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * QueryDSL Custom Repository Implementation
 *
 * Demonstrates:
 * 1. Type-safe query construction (compile-time validation)
 * 2. Complex multi-table JOIN with subquery
 * 3. Pagination with offset/limit
 * 4. Tuple-based result mapping to DTOs
 * 5. LinkedHashMap for preserving insertion order
 *
 * Pattern:
 *   Repository interface: LectureRepositoryCustom
 *   Implementation: LectureRepositoryImpl (this file)
 *   Main repository: LectureRepository extends JpaRepository, LectureRepositoryCustom
 */
@Repository
@RequiredArgsConstructor
public class LectureRepositoryImpl implements LectureRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<ChapterBlockDTO> findCurriculum(Long userId, int page, int size) {
        long offset = (long) page * size;

        // Step 1: Fetch paginated chapter IDs (efficient pagination)
        List<Long> chapterIds = queryFactory
            .select(chapter.id)
            .from(chapter)
            .orderBy(chapter.chapterOrder.asc())
            .offset(offset)
            .limit(size)
            .fetch();

        if (chapterIds.isEmpty()) {
            return List.of();
        }

        // Step 2: Subquery for latest progress per lecture per user
        QLectureProgress lpSub = new QLectureProgress("lpSub");

        // Step 3: Main query with JOIN + subquery filter
        List<Tuple> rows = queryFactory
            .select(
                chapter.id,
                chapter.title,
                chapter.description,
                chapter.chapterType,
                lecture.id,
                lecture.title,
                lecture.content,
                lecture.thumbnailUrl,
                lecture.durationSeconds,
                lecture.requiredTokens,
                lectureProgress.watchedSeconds,
                lectureProgress.isCompleted,
                lectureProgress.lastWatchedAt
            )
            .from(chapter)
            .join(lecture).on(lecture.chapter.eq(chapter))
            .leftJoin(lectureProgress)
            .on(
                lectureProgress.lecture.eq(lecture)
                    .and(lectureProgress.customer.id.eq(userId))
                    .and(
                        // Subquery: only the latest progress record
                        lectureProgress.id.eq(
                            JPAExpressions
                                .select(lpSub.id.max())
                                .from(lpSub)
                                .where(
                                    lpSub.lecture.eq(lecture)
                                        .and(lpSub.customer.id.eq(userId))
                                )
                        )
                    )
            )
            .where(chapter.id.in(chapterIds))
            .orderBy(chapter.chapterOrder.asc(), lecture.lectureOrder.asc())
            .fetch();

        // Step 4: Group by chapter using LinkedHashMap (preserves order)
        Map<Long, ChapterBlockDTO> chapterMap = new LinkedHashMap<>();

        for (Tuple t : rows) {
            Long chapterId = t.get(chapter.id);

            chapterMap.putIfAbsent(chapterId,
                ChapterBlockDTO.builder()
                    .chapterId(chapterId)
                    .chapterTitle(t.get(chapter.title))
                    .description(t.get(chapter.description))
                    .lectures(new ArrayList<>())
                    .build()
            );

            LectureResponseDTO lectureDTO = LectureResponseDTO.builder()
                .lectureId(t.get(lecture.id))
                .chapterId(chapterId)
                .title(t.get(lecture.title))
                .content(t.get(lecture.content))
                .thumbnailUrl(t.get(lecture.thumbnailUrl))
                .durationSeconds(t.get(lecture.durationSeconds))
                .watchedSeconds(t.get(lectureProgress.watchedSeconds))
                .completed(t.get(lectureProgress.isCompleted))
                .build();

            chapterMap.get(chapterId).getLectures().add(lectureDTO);
        }

        return new ArrayList<>(chapterMap.values());
    }
}
