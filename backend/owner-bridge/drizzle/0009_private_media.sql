-- An upload is a scoped, temporary candidate. Its reservation, a binding to
-- exact bytes, is separate from the owner-reviewed primary image transaction.
CREATE TABLE cloud_media_uploads (
  owner_id TEXT NOT NULL, continuity_id TEXT NOT NULL,
  upload_id TEXT NOT NULL CHECK(length(upload_id) BETWEEN 1 AND 128),
  subject TEXT NOT NULL, epoch INTEGER NOT NULL CHECK(epoch>=1),
  target_id TEXT NOT NULL, target_kind TEXT NOT NULL, target_revision INTEGER NOT NULL CHECK(target_revision>=1),
  actor_id TEXT, digest TEXT NOT NULL CHECK(length(digest)=64 AND digest NOT GLOB '*[^0-9a-f]*'),
  metadata_json TEXT NOT NULL CHECK(json_valid(metadata_json) AND json_type(metadata_json)='object'),
  state TEXT NOT NULL CHECK(state IN ('uploading','ready','cleaning')),
  created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL CHECK(expires_at>created_at),
  PRIMARY KEY(owner_id,continuity_id,upload_id),
  FOREIGN KEY(owner_id,continuity_id) REFERENCES cloud_continuities(owner_id,continuity_id)
) WITHOUT ROWID;
--> statement-breakpoint
CREATE INDEX cloud_media_subject_time ON cloud_media_uploads(owner_id,continuity_id,subject,created_at);
--> statement-breakpoint
CREATE INDEX cloud_media_expiry ON cloud_media_uploads(owner_id,continuity_id,expires_at,upload_id);
--> statement-breakpoint
CREATE INDEX cloud_media_assets_by_upload ON cloud_entities(owner_id,continuity_id,json_extract(data_json,'$.uploadId'))
  WHERE kind='media_asset';
--> statement-breakpoint
CREATE UNIQUE INDEX cloud_one_primary_image ON cloud_links(owner_id,continuity_id,from_id) WHERE relation='primary_image';
--> statement-breakpoint
CREATE TRIGGER cloud_media_quota BEFORE INSERT ON cloud_media_uploads
WHEN (SELECT count(*) FROM cloud_media_uploads WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id
  AND subject=NEW.subject AND created_at>NEW.created_at-3600000)>=32
BEGIN SELECT RAISE(ABORT,'CLOUD_MEDIA_UPLOAD_LIMIT'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_media_reservation_immutable BEFORE UPDATE ON cloud_media_uploads
WHEN NEW.owner_id IS NOT OLD.owner_id OR NEW.continuity_id IS NOT OLD.continuity_id OR NEW.upload_id IS NOT OLD.upload_id
  OR NEW.subject IS NOT OLD.subject OR NEW.epoch IS NOT OLD.epoch OR NEW.target_id IS NOT OLD.target_id
  OR NEW.target_kind IS NOT OLD.target_kind OR NEW.target_revision IS NOT OLD.target_revision OR NEW.actor_id IS NOT OLD.actor_id
  OR NEW.digest IS NOT OLD.digest OR NEW.metadata_json IS NOT OLD.metadata_json OR NEW.created_at IS NOT OLD.created_at
  OR NEW.expires_at IS NOT OLD.expires_at OR (OLD.state='cleaning' AND NEW.state<>'cleaning')
  OR (OLD.state='ready' AND NEW.state='uploading')
BEGIN SELECT RAISE(ABORT,'CLOUD_MEDIA_RESERVATION_CONFLICT'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_media_asset_validate BEFORE INSERT ON cloud_entities
WHEN NEW.kind='media_asset' AND EXISTS(SELECT 1 FROM cloud_continuities WHERE owner_id=NEW.owner_id
    AND continuity_id=NEW.continuity_id AND authority='cloud')
  AND NOT EXISTS(SELECT 1 FROM cloud_media_uploads u JOIN cloud_continuities c
    ON c.owner_id=u.owner_id AND c.continuity_id=u.continuity_id AND c.epoch=u.epoch
    WHERE u.owner_id=NEW.owner_id AND u.continuity_id=NEW.continuity_id AND u.state='ready'
    AND u.upload_id=json_extract(NEW.data_json,'$.uploadId') AND u.digest=json_extract(NEW.data_json,'$.sha256')
    AND u.actor_id IS NEW.actor_id
    AND json_extract(u.metadata_json,'$.thumbnailSha256')=json_extract(NEW.data_json,'$.thumbnailSha256'))
BEGIN SELECT RAISE(ABORT,'CLOUD_MEDIA_BYTES_UNAVAILABLE'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_media_version_immutable BEFORE UPDATE ON cloud_entities
WHEN OLD.kind='media_asset' AND (OLD.data_json<>NEW.data_json OR OLD.actor_id IS NOT NEW.actor_id)
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_MEDIA_VERSION'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_media_link_kind BEFORE INSERT ON cloud_links
WHEN NEW.relation='primary_image' AND NOT EXISTS(SELECT 1 FROM cloud_entities
  WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id AND id=NEW.to_id AND kind='media_asset' AND life='active')
BEGIN SELECT RAISE(ABORT,'CLOUD_MEDIA_ASSET_REQUIRED'); END;
