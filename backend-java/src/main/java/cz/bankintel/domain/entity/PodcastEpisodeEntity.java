package cz.bankintel.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "podcast_episodes")
@Getter
@Setter
public class PodcastEpisodeEntity {

    @Id
    private String id;

    @Column(name = "show_id", length = 36)
    private String showId;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(nullable = false, length = 2000)
    private String title;

    @Column(nullable = false, length = 800)
    private String summary = "";

    @Column(name = "audio_url", length = 4000)
    private String audioUrl;

    @Column(name = "external_url", length = 4000)
    private String externalUrl;

    @Column(name = "cover_image_url", length = 4000)
    private String coverImageUrl;

    @Column(name = "feed_title", length = 500)
    private String feedTitle;

    @Column(length = 500)
    private String author;

    @Column(name = "gridfs_id", length = 64)
    private String gridfsId;

    @Column(name = "stored_audio_rel", length = 512)
    private String storedAudioRel;

    @Column(name = "stored_cover_rel", length = 512)
    private String storedCoverRel;

    @Column(name = "audio_content_type", length = 128)
    private String audioContentType;

    @Column(name = "cover_content_type", length = 128)
    private String coverContentType;

    @Column(name = "original_filename", length = 500)
    private String originalFilename;

    @Column(nullable = false, length = 32)
    private String source = "upload";

    @Column(nullable = false)
    private boolean published = true;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (publishedAt == null) {
            publishedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
