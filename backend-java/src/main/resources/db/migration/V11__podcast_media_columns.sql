ALTER TABLE podcast_episodes ADD COLUMN stored_audio_rel VARCHAR(512);
ALTER TABLE podcast_episodes ADD COLUMN stored_cover_rel VARCHAR(512);
ALTER TABLE podcast_episodes ADD COLUMN audio_content_type VARCHAR(128);
ALTER TABLE podcast_episodes ADD COLUMN cover_content_type VARCHAR(128);
ALTER TABLE podcast_episodes ADD COLUMN original_filename VARCHAR(500);
