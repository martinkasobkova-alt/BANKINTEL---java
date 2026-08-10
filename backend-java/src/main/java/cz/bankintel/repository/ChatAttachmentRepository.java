package cz.bankintel.repository;

import cz.bankintel.domain.entity.ChatAttachmentEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatAttachmentRepository extends JpaRepository<ChatAttachmentEntity, String> {

    List<ChatAttachmentEntity> findAllByIdInAndConversationId(Collection<String> ids, String conversationId);
}
