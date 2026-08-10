package cz.bankintel.repository;

import cz.bankintel.domain.entity.ChatConversationEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatConversationRepository extends JpaRepository<ChatConversationEntity, String> {

    @Query(
            """
            SELECT c FROM ChatConversationEntity c
            JOIN ChatParticipantEntity p ON p.conversationId = c.id
            WHERE p.userId = :userId
            ORDER BY c.updatedAt DESC, c.createdAt DESC
            """)
    List<ChatConversationEntity> findAllForUser(@Param("userId") String userId);

    @Query(
            """
            SELECT c FROM ChatConversationEntity c
            WHERE c.type = 'direct'
            AND (SELECT COUNT(p) FROM ChatParticipantEntity p WHERE p.conversationId = c.id) = 2
            AND EXISTS (
                SELECT 1 FROM ChatParticipantEntity p1
                WHERE p1.conversationId = c.id AND p1.userId = :userId1
            )
            AND EXISTS (
                SELECT 1 FROM ChatParticipantEntity p2
                WHERE p2.conversationId = c.id AND p2.userId = :userId2
            )
            """)
    Optional<ChatConversationEntity> findDirectBetweenUsers(
            @Param("userId1") String userId1, @Param("userId2") String userId2);
}
