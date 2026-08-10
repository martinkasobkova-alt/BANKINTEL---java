package cz.bankintel.repository;

import cz.bankintel.domain.entity.ChatMessageEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, String> {

    @Query(
            """
            SELECT m FROM ChatMessageEntity m
            WHERE m.conversationId = :conversationId
            AND (:before IS NULL OR m.createdAt < :before)
            ORDER BY m.createdAt DESC
            """)
    List<ChatMessageEntity> findPage(
            @Param("conversationId") String conversationId,
            @Param("before") Instant before,
            Pageable pageable);

    @Query(
            """
            SELECT COUNT(m) FROM ChatMessageEntity m
            WHERE m.conversationId = :conversationId
            AND m.senderId <> :userId
            AND (:lastReadAt IS NULL OR m.createdAt > :lastReadAt)
            """)
    long countUnread(
            @Param("conversationId") String conversationId,
            @Param("userId") String userId,
            @Param("lastReadAt") Instant lastReadAt);

    @Query(
            """
            SELECT COUNT(m) FROM ChatMessageEntity m
            JOIN ChatParticipantEntity p ON p.conversationId = m.conversationId
            WHERE p.userId = :userId
            AND m.senderId <> :userId
            AND (p.lastReadAt IS NULL OR m.createdAt > p.lastReadAt)
            """)
    long countTotalUnreadForUser(@Param("userId") String userId);
}
