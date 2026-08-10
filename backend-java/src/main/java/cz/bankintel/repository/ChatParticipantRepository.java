package cz.bankintel.repository;

import cz.bankintel.domain.entity.ChatParticipantEntity;
import cz.bankintel.domain.entity.ChatParticipantId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatParticipantRepository extends JpaRepository<ChatParticipantEntity, ChatParticipantId> {

    List<ChatParticipantEntity> findAllByConversationId(String conversationId);

    List<ChatParticipantEntity> findAllByConversationIdIn(Collection<String> conversationIds);

    @Query(
            """
            SELECT p FROM ChatParticipantEntity p
            WHERE p.conversationId IN (
                SELECT p2.conversationId FROM ChatParticipantEntity p2 WHERE p2.userId = :userId
            )
            """)
    List<ChatParticipantEntity> findAllForUserConversations(@Param("userId") String userId);

    boolean existsByConversationIdAndUserId(String conversationId, String userId);
}
