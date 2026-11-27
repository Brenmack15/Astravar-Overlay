-- Command and receipt headers keep their immutable identity/digests. Detailed
-- evidence is split by record so deletion can retain unrelated transaction legs.
CREATE TABLE cloud_evidence_segments (
  owner_id TEXT NOT NULL, continuity_id TEXT NOT NULL, command_id TEXT NOT NULL, part TEXT NOT NULL,
  digest TEXT NOT NULL CHECK(length(digest)=64 AND digest NOT GLOB '*[^0-9a-f]*'),
  payload_json TEXT NOT NULL CHECK(json_valid(payload_json)),
  PRIMARY KEY(owner_id,continuity_id,command_id,part),
  FOREIGN KEY(owner_id,continuity_id,command_id) REFERENCES cloud_requests(owner_id,continuity_id,command_id) DEFERRABLE INITIALLY DEFERRED
) WITHOUT ROWID;
--> statement-breakpoint
CREATE TABLE cloud_evidence_subjects (
  owner_id TEXT NOT NULL, continuity_id TEXT NOT NULL, command_id TEXT NOT NULL, part TEXT NOT NULL, entity_id TEXT NOT NULL,
  PRIMARY KEY(owner_id,continuity_id,command_id,part,entity_id),
  FOREIGN KEY(owner_id,continuity_id,command_id,part) REFERENCES cloud_evidence_segments(owner_id,continuity_id,command_id,part)
    ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED
) WITHOUT ROWID;
--> statement-breakpoint
CREATE INDEX cloud_evidence_by_entity ON cloud_evidence_subjects(owner_id,continuity_id,entity_id,command_id,part);
--> statement-breakpoint
CREATE TABLE cloud_evidence_erased (
  owner_id TEXT NOT NULL, continuity_id TEXT NOT NULL, command_id TEXT NOT NULL, part TEXT NOT NULL,
  digest TEXT NOT NULL, entity_id TEXT NOT NULL, deleted_revision INTEGER NOT NULL,
  PRIMARY KEY(owner_id,continuity_id,command_id,part),
  FOREIGN KEY(owner_id,continuity_id,command_id) REFERENCES cloud_requests(owner_id,continuity_id,command_id),
  FOREIGN KEY(owner_id,continuity_id,entity_id) REFERENCES cloud_tombstones(owner_id,continuity_id,id)
) WITHOUT ROWID;
--> statement-breakpoint
CREATE TRIGGER cloud_evidence_no_update BEFORE UPDATE ON cloud_evidence_segments
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_EVIDENCE'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_evidence_no_replacement BEFORE INSERT ON cloud_evidence_segments
WHEN EXISTS(SELECT 1 FROM cloud_evidence_segments WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id AND command_id=NEW.command_id AND part=NEW.part)
  OR EXISTS(SELECT 1 FROM cloud_evidence_erased WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id AND command_id=NEW.command_id AND part=NEW.part)
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_EVIDENCE'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_evidence_reviewed_purge BEFORE DELETE ON cloud_evidence_segments
BEGIN
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM cloud_evidence_subjects s JOIN cloud_tombstones t
    ON t.owner_id=s.owner_id AND t.continuity_id=s.continuity_id AND t.id=s.entity_id
    WHERE s.owner_id=OLD.owner_id AND s.continuity_id=OLD.continuity_id AND s.command_id=OLD.command_id AND s.part=OLD.part)
  THEN RAISE(ABORT,'CLOUD_EVIDENCE_TOMBSTONE_REQUIRED') END;
  INSERT INTO cloud_evidence_erased(owner_id,continuity_id,command_id,part,digest,entity_id,deleted_revision)
    SELECT OLD.owner_id,OLD.continuity_id,OLD.command_id,OLD.part,OLD.digest,t.id,t.deleted_revision
    FROM cloud_evidence_subjects s JOIN cloud_tombstones t ON t.owner_id=s.owner_id AND t.continuity_id=s.continuity_id AND t.id=s.entity_id
    WHERE s.owner_id=OLD.owner_id AND s.continuity_id=OLD.continuity_id AND s.command_id=OLD.command_id AND s.part=OLD.part ORDER BY t.id LIMIT 1;
END;
--> statement-breakpoint
CREATE TRIGGER cloud_evidence_marker_no_update BEFORE UPDATE ON cloud_evidence_erased
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_EVIDENCE'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_evidence_marker_no_delete BEFORE DELETE ON cloud_evidence_erased
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_EVIDENCE'); END;
--> statement-breakpoint
CREATE TABLE cloud_media_gc (
  owner_id TEXT NOT NULL, continuity_id TEXT NOT NULL, command_id TEXT NOT NULL, upload_id TEXT NOT NULL, digest TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('pending','removed')), PRIMARY KEY(owner_id,continuity_id,command_id,upload_id),
  FOREIGN KEY(owner_id,continuity_id,command_id) REFERENCES cloud_requests(owner_id,continuity_id,command_id) DEFERRABLE INITIALLY DEFERRED
) WITHOUT ROWID;
--> statement-breakpoint
CREATE TRIGGER cloud_media_retired_reservation BEFORE INSERT ON cloud_media_uploads
WHEN EXISTS(SELECT 1 FROM cloud_media_gc g WHERE g.owner_id=NEW.owner_id AND g.continuity_id=NEW.continuity_id AND g.upload_id=NEW.upload_id)
BEGIN SELECT RAISE(ABORT,'CLOUD_MEDIA_RETIRED_ID'); END;
