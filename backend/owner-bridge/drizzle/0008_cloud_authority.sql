-- Private campaign protocol v1. Existing campaigns acquire no authority or grant
-- from installation of this migration. Their data remains in its original tables.
CREATE TABLE cloud_continuities (
  owner_id TEXT NOT NULL CHECK(length(owner_id) BETWEEN 1 AND 200),
  continuity_id TEXT NOT NULL CHECK(length(continuity_id) BETWEEN 1 AND 200),
  authority TEXT NOT NULL CHECK(authority IN ('legacy','staged','cloud')),
  epoch INTEGER NOT NULL DEFAULT 1 CHECK(typeof(epoch)='integer' AND epoch BETWEEN 1 AND 9007199254740991),
  revision INTEGER NOT NULL DEFAULT 0 CHECK(typeof(revision)='integer' AND revision BETWEEN 0 AND 9007199254740991),
  schema_version INTEGER NOT NULL DEFAULT 1 CHECK(schema_version=1),
  PRIMARY KEY(owner_id,continuity_id)
) WITHOUT ROWID;
--> statement-breakpoint
CREATE TRIGGER cloud_continuity_identity BEFORE UPDATE ON cloud_continuities
WHEN NEW.owner_id IS NOT OLD.owner_id OR NEW.continuity_id IS NOT OLD.continuity_id
  OR NEW.revision<OLD.revision OR NEW.epoch<OLD.epoch OR NEW.epoch>OLD.epoch+1
  OR (NEW.authority<>OLD.authority AND NEW.epoch<>OLD.epoch+1)
BEGIN SELECT RAISE(ABORT,'CLOUD_AUTHORITY_EPOCH'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_continuity_no_replacement BEFORE INSERT ON cloud_continuities
WHEN EXISTS(SELECT 1 FROM cloud_continuities WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id)
BEGIN SELECT RAISE(ABORT,'CLOUD_CONTINUITY_EXISTS'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_continuity_no_delete BEFORE DELETE ON cloud_continuities
BEGIN SELECT RAISE(ABORT,'CLOUD_AUTHORITY_REQUIRED'); END;
--> statement-breakpoint
-- The discriminator in the compound parent key enforces an actor-typed FK
-- even for out-of-order imports. An actor references its own stable identity.
CREATE TABLE cloud_entities (
  owner_id TEXT NOT NULL,
  continuity_id TEXT NOT NULL,
  id TEXT NOT NULL CHECK(length(id) BETWEEN 1 AND 200),
  kind TEXT NOT NULL CHECK(length(kind) BETWEEN 1 AND 80 AND kind NOT GLOB '*[^a-z0-9_]*'),
  actor_id TEXT,
  title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 1000),
  data_json TEXT NOT NULL CHECK(json_valid(data_json) AND json_type(data_json)='object'),
  life TEXT NOT NULL DEFAULT 'active' CHECK(life IN ('active','archived')),
  revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision BETWEEN 1 AND 9007199254740991),
  actor_kind TEXT GENERATED ALWAYS AS (CASE WHEN actor_id IS NOT NULL THEN 'actor' END) VIRTUAL,
  PRIMARY KEY(owner_id,continuity_id,id),
  UNIQUE(owner_id,continuity_id,id,kind),
  CHECK(kind<>'actor' OR (actor_id IS NOT NULL AND actor_id=id)),
  FOREIGN KEY(owner_id,continuity_id) REFERENCES cloud_continuities(owner_id,continuity_id),
  FOREIGN KEY(owner_id,continuity_id,actor_id,actor_kind)
    REFERENCES cloud_entities(owner_id,continuity_id,id,kind) DEFERRABLE INITIALLY DEFERRED
) WITHOUT ROWID;
--> statement-breakpoint
CREATE INDEX cloud_entities_by_kind ON cloud_entities(owner_id,continuity_id,kind,life,id);
--> statement-breakpoint
CREATE INDEX cloud_entities_by_actor ON cloud_entities(owner_id,continuity_id,actor_id,kind,life,id);
--> statement-breakpoint
CREATE TRIGGER cloud_entity_identity BEFORE UPDATE ON cloud_entities
WHEN NEW.owner_id IS NOT OLD.owner_id OR NEW.continuity_id IS NOT OLD.continuity_id
  OR NEW.id IS NOT OLD.id OR NEW.kind IS NOT OLD.kind OR NEW.revision<=OLD.revision
BEGIN SELECT RAISE(ABORT,'CLOUD_ENTITY_REVISION'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_entity_insert_revision BEFORE INSERT ON cloud_entities
WHEN EXISTS(SELECT 1 FROM cloud_entities WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id
  AND id=NEW.id AND (kind<>NEW.kind OR revision>=NEW.revision))
BEGIN SELECT RAISE(ABORT,'CLOUD_ENTITY_REVISION'); END;
--> statement-breakpoint
CREATE TABLE cloud_links (
  owner_id TEXT NOT NULL,
  continuity_id TEXT NOT NULL,
  from_id TEXT NOT NULL,
  relation TEXT NOT NULL CHECK(length(relation) BETWEEN 1 AND 80 AND relation NOT GLOB '*[^a-z0-9_]*'),
  to_id TEXT NOT NULL,
  PRIMARY KEY(owner_id,continuity_id,from_id,relation,to_id),
  FOREIGN KEY(owner_id,continuity_id,from_id) REFERENCES cloud_entities(owner_id,continuity_id,id) DEFERRABLE INITIALLY DEFERRED,
  FOREIGN KEY(owner_id,continuity_id,to_id) REFERENCES cloud_entities(owner_id,continuity_id,id) DEFERRABLE INITIALLY DEFERRED
) WITHOUT ROWID;
--> statement-breakpoint
CREATE INDEX cloud_links_to ON cloud_links(owner_id,continuity_id,to_id,relation,from_id);
--> statement-breakpoint
CREATE TABLE cloud_grants (
  owner_id TEXT NOT NULL,
  continuity_id TEXT NOT NULL,
  subject TEXT NOT NULL CHECK(length(subject) BETWEEN 1 AND 240),
  scopes_json TEXT NOT NULL CHECK(json_valid(scopes_json) AND json_type(scopes_json)='array'),
  actors_json TEXT NOT NULL CHECK(json_valid(actors_json) AND json_type(actors_json)='array'),
  auto_json TEXT NOT NULL DEFAULT '[]' CHECK(json_valid(auto_json) AND json_type(auto_json)='array'),
  expires_at INTEGER NOT NULL CHECK(typeof(expires_at)='integer' AND expires_at BETWEEN 0 AND 9007199254740991),
  revoked INTEGER NOT NULL DEFAULT 0 CHECK(revoked IN (0,1)),
  revision INTEGER NOT NULL DEFAULT 1 CHECK(typeof(revision)='integer' AND revision BETWEEN 1 AND 9007199254740991),
  PRIMARY KEY(owner_id,continuity_id,subject),
  FOREIGN KEY(owner_id,continuity_id) REFERENCES cloud_continuities(owner_id,continuity_id)
) WITHOUT ROWID;
--> statement-breakpoint
CREATE TRIGGER cloud_grant_identity BEFORE UPDATE ON cloud_grants
WHEN NEW.owner_id IS NOT OLD.owner_id OR NEW.continuity_id IS NOT OLD.continuity_id OR NEW.subject IS NOT OLD.subject
  OR NEW.revision<>OLD.revision+1
BEGIN SELECT RAISE(ABORT,'CLOUD_GRANT_REVISION'); END;
--> statement-breakpoint
-- Grant scope sets must remain structurally valid even after a reviewed import.
-- Individual actor IDs can be granted before an authorized actor creation.
CREATE VIEW cloud_invalid_grants AS
SELECT g.owner_id,g.continuity_id,g.subject FROM cloud_grants g
WHERE EXISTS(SELECT 1 FROM json_each(g.scopes_json) s WHERE s.type<>'text'
  OR (s.value NOT GLOB '?*.read' AND s.value NOT GLOB '?*.write')
  OR (s.value GLOB '*.write' AND substr(s.value,1,length(s.value)-6)||'.read' NOT IN (SELECT value FROM json_each(g.scopes_json))))
  OR EXISTS(SELECT 1 FROM json_each(g.auto_json) a WHERE a.type<>'text' OR a.value NOT GLOB '?*.write'
    OR a.value NOT IN (SELECT value FROM json_each(g.scopes_json)))
  OR EXISTS(SELECT 1 FROM json_each(g.actors_json) a WHERE a.type<>'text' OR length(a.value) NOT BETWEEN 1 AND 200)
  OR EXISTS(SELECT 1 FROM json_each(g.actors_json) a WHERE a.value='*' AND json_array_length(g.actors_json)<>1)
  OR (SELECT count(DISTINCT value) FROM json_each(g.scopes_json))<>json_array_length(g.scopes_json)
  OR (SELECT count(DISTINCT value) FROM json_each(g.auto_json))<>json_array_length(g.auto_json)
  OR (SELECT count(DISTINCT value) FROM json_each(g.actors_json))<>json_array_length(g.actors_json);
--> statement-breakpoint
CREATE TRIGGER cloud_grant_validate_insert AFTER INSERT ON cloud_grants
WHEN EXISTS(SELECT 1 FROM cloud_invalid_grants WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id AND subject=NEW.subject)
BEGIN SELECT RAISE(ABORT,'INVALID_CLOUD_GRANT'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_grant_validate_update AFTER UPDATE ON cloud_grants
WHEN EXISTS(SELECT 1 FROM cloud_invalid_grants WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id AND subject=NEW.subject)
BEGIN SELECT RAISE(ABORT,'INVALID_CLOUD_GRANT'); END;
--> statement-breakpoint
CREATE TABLE cloud_requests (
  owner_id TEXT NOT NULL,
  continuity_id TEXT NOT NULL,
  command_id TEXT NOT NULL CHECK(length(command_id) BETWEEN 1 AND 200),
  subject TEXT NOT NULL CHECK(length(subject) BETWEEN 1 AND 240),
  digest TEXT NOT NULL CHECK(length(digest)=64 AND digest NOT GLOB '*[^0-9a-f]*'),
  request_json TEXT NOT NULL CHECK(json_valid(request_json) AND json_type(request_json)='object'),
  access_json TEXT NOT NULL CHECK(json_valid(access_json) AND json_type(access_json)='array'),
  status TEXT NOT NULL CHECK(status IN ('PENDING_APPROVAL','COMMITTED','REFUSED','CONFLICT','SUPERSEDED','FAILED')),
  submitted_at INTEGER NOT NULL CHECK(typeof(submitted_at)='integer' AND submitted_at>=0),
  terminal_command_id TEXT GENERATED ALWAYS AS (CASE WHEN status<>'PENDING_APPROVAL' THEN command_id END) VIRTUAL,
  PRIMARY KEY(owner_id,continuity_id,command_id),
  UNIQUE(owner_id,continuity_id,command_id,status),
  FOREIGN KEY(owner_id,continuity_id) REFERENCES cloud_continuities(owner_id,continuity_id),
  FOREIGN KEY(owner_id,continuity_id,terminal_command_id)
    REFERENCES cloud_receipts(owner_id,continuity_id,command_id) DEFERRABLE INITIALLY DEFERRED
) WITHOUT ROWID;
--> statement-breakpoint
CREATE INDEX cloud_pending_requests ON cloud_requests(owner_id,continuity_id,submitted_at,command_id)
  WHERE status='PENDING_APPROVAL';
--> statement-breakpoint
CREATE TRIGGER cloud_request_no_replacement BEFORE INSERT ON cloud_requests
WHEN EXISTS(SELECT 1 FROM cloud_requests WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id AND command_id=NEW.command_id)
BEGIN SELECT RAISE(ABORT,'CLOUD_COMMAND_EXISTS'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_request_immutable BEFORE UPDATE ON cloud_requests
WHEN NEW.owner_id IS NOT OLD.owner_id OR NEW.continuity_id IS NOT OLD.continuity_id OR NEW.command_id IS NOT OLD.command_id
  OR NEW.subject IS NOT OLD.subject OR NEW.digest IS NOT OLD.digest OR NEW.request_json IS NOT OLD.request_json
  OR NEW.access_json IS NOT OLD.access_json OR NEW.submitted_at IS NOT OLD.submitted_at
  OR OLD.status<>'PENDING_APPROVAL' OR NEW.status='PENDING_APPROVAL'
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_REQUEST'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_request_no_delete BEFORE DELETE ON cloud_requests
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_REQUEST'); END;
--> statement-breakpoint
CREATE TABLE cloud_receipts (
  owner_id TEXT NOT NULL,
  continuity_id TEXT NOT NULL,
  command_id TEXT NOT NULL,
  transaction_id TEXT NOT NULL CHECK(length(transaction_id) BETWEEN 1 AND 200),
  status TEXT NOT NULL CHECK(status IN ('COMMITTED','REFUSED','CONFLICT','SUPERSEDED','FAILED')),
  epoch INTEGER NOT NULL CHECK(typeof(epoch)='integer' AND epoch BETWEEN 1 AND 9007199254740991),
  revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision BETWEEN 0 AND 9007199254740991),
  result_json TEXT NOT NULL CHECK(json_valid(result_json) AND json_type(result_json)='object'),
  access_json TEXT NOT NULL CHECK(json_valid(access_json) AND json_type(access_json)='array'),
  created_at INTEGER NOT NULL CHECK(typeof(created_at)='integer' AND created_at>=0),
  PRIMARY KEY(owner_id,continuity_id,command_id),
  UNIQUE(owner_id,continuity_id,transaction_id),
  FOREIGN KEY(owner_id,continuity_id,command_id,status)
    REFERENCES cloud_requests(owner_id,continuity_id,command_id,status) DEFERRABLE INITIALLY DEFERRED
) WITHOUT ROWID;
--> statement-breakpoint
CREATE INDEX cloud_receipts_by_revision ON cloud_receipts(owner_id,continuity_id,revision,command_id);
--> statement-breakpoint
CREATE TRIGGER cloud_receipt_no_replacement BEFORE INSERT ON cloud_receipts
WHEN EXISTS(SELECT 1 FROM cloud_receipts WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id
  AND (command_id=NEW.command_id OR transaction_id=NEW.transaction_id))
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_RECEIPT'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_receipt_no_update BEFORE UPDATE ON cloud_receipts
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_RECEIPT'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_receipt_no_delete BEFORE DELETE ON cloud_receipts
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_RECEIPT'); END;
--> statement-breakpoint
CREATE TABLE cloud_changes (
  owner_id TEXT NOT NULL,
  continuity_id TEXT NOT NULL,
  revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision BETWEEN 1 AND 9007199254740991),
  ordinal INTEGER NOT NULL CHECK(typeof(ordinal)='integer' AND ordinal>=0),
  entity_id TEXT NOT NULL CHECK(length(entity_id) BETWEEN 1 AND 200),
  kind TEXT NOT NULL CHECK(length(kind) BETWEEN 1 AND 80),
  actor_id TEXT,
  change_type TEXT NOT NULL CHECK(change_type IN ('upsert','delete')),
  PRIMARY KEY(owner_id,continuity_id,revision,ordinal),
  FOREIGN KEY(owner_id,continuity_id) REFERENCES cloud_continuities(owner_id,continuity_id)
) WITHOUT ROWID;
--> statement-breakpoint
CREATE INDEX cloud_changes_by_actor ON cloud_changes(owner_id,continuity_id,actor_id,revision,ordinal);
--> statement-breakpoint
CREATE TRIGGER cloud_change_no_replacement BEFORE INSERT ON cloud_changes
WHEN EXISTS(SELECT 1 FROM cloud_changes WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id
  AND revision=NEW.revision AND ordinal=NEW.ordinal)
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_CHANGE'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_change_no_update BEFORE UPDATE ON cloud_changes
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_CHANGE'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_change_no_delete BEFORE DELETE ON cloud_changes
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_CHANGE'); END;
--> statement-breakpoint
CREATE TABLE cloud_tombstones (
  owner_id TEXT NOT NULL,
  continuity_id TEXT NOT NULL,
  id TEXT NOT NULL CHECK(length(id) BETWEEN 1 AND 200),
  kind TEXT NOT NULL CHECK(length(kind) BETWEEN 1 AND 80),
  deleted_revision INTEGER NOT NULL CHECK(typeof(deleted_revision)='integer' AND deleted_revision BETWEEN 1 AND 9007199254740991),
  PRIMARY KEY(owner_id,continuity_id,id),
  FOREIGN KEY(owner_id,continuity_id) REFERENCES cloud_continuities(owner_id,continuity_id)
) WITHOUT ROWID;
--> statement-breakpoint
CREATE TRIGGER cloud_tombstone_insert BEFORE INSERT ON cloud_tombstones
WHEN EXISTS(SELECT 1 FROM cloud_tombstones WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id AND id=NEW.id)
  OR EXISTS(SELECT 1 FROM cloud_entities WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id AND id=NEW.id
    AND (kind<>NEW.kind OR revision>=NEW.deleted_revision))
BEGIN SELECT RAISE(ABORT,'CLOUD_TOMBSTONE_CONFLICT'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_tombstone_no_update BEFORE UPDATE ON cloud_tombstones
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_TOMBSTONE'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_tombstone_no_delete BEFORE DELETE ON cloud_tombstones
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_TOMBSTONE'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_entity_block_resurrection BEFORE INSERT ON cloud_entities
WHEN EXISTS(SELECT 1 FROM cloud_tombstones WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id AND id=NEW.id)
BEGIN SELECT RAISE(ABORT,'DELETED_CLOUD_IDENTITY'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_entity_require_tombstone BEFORE DELETE ON cloud_entities
WHEN NOT EXISTS(SELECT 1 FROM cloud_tombstones WHERE owner_id=OLD.owner_id AND continuity_id=OLD.continuity_id AND id=OLD.id AND kind=OLD.kind)
BEGIN SELECT RAISE(ABORT,'CLOUD_TOMBSTONE_REQUIRED'); END;
--> statement-breakpoint
CREATE TABLE cloud_guards (ok INTEGER NOT NULL CHECK(ok=1));
--> statement-breakpoint
CREATE TRIGGER cloud_fence_v2_submit BEFORE INSERT ON bridge_campaign_commands
WHEN EXISTS(SELECT 1 FROM cloud_continuities
  WHERE owner_id=NEW.owner_id AND continuity_id=NEW.campaign_id AND authority='cloud')
BEGIN SELECT RAISE(ABORT,'CLOUD_AUTHORITY_REQUIRED'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_fence_v2_publish BEFORE INSERT ON bridge_campaign_publications
WHEN (json_array_length(NEW.records_json)>0 OR json_array_length(NEW.removed_json)>0) AND EXISTS(SELECT 1 FROM cloud_continuities
  WHERE owner_id=NEW.owner_id AND continuity_id=NEW.campaign_id AND authority='cloud')
BEGIN SELECT RAISE(ABORT,'CLOUD_AUTHORITY_REQUIRED'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_fence_v2_decide BEFORE INSERT ON bridge_campaign_receipts
WHEN json_extract(NEW.receipt_json,'$.status')='APPLIED' AND EXISTS(SELECT 1 FROM cloud_continuities
  WHERE owner_id=NEW.owner_id AND continuity_id=NEW.campaign_id AND authority='cloud')
BEGIN SELECT RAISE(ABORT,'CLOUD_AUTHORITY_REQUIRED'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_fence_hp_submit BEFORE INSERT ON bridge_hp_proposals
WHEN EXISTS(SELECT 1 FROM cloud_continuities
  WHERE owner_id=NEW.owner_id AND continuity_id=NEW.campaign_id AND authority='cloud')
BEGIN SELECT RAISE(ABORT,'CLOUD_AUTHORITY_REQUIRED'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_fence_hp_decide BEFORE INSERT ON bridge_hp_receipts
WHEN NEW.status IN ('APPLIED','PUBLISHED') AND EXISTS(SELECT 1 FROM cloud_continuities
  WHERE owner_id=NEW.owner_id AND continuity_id=NEW.campaign_id AND authority='cloud')
BEGIN SELECT RAISE(ABORT,'CLOUD_AUTHORITY_REQUIRED'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_fence_v1_submit BEFORE INSERT ON bridge_proposals
WHEN EXISTS(SELECT 1 FROM cloud_continuities
  WHERE owner_id=NEW.owner_id AND continuity_id=NEW.campaign_id AND authority='cloud')
BEGIN SELECT RAISE(ABORT,'CLOUD_AUTHORITY_REQUIRED'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_fence_v1_decide BEFORE INSERT ON bridge_receipts
WHEN NEW.status IN ('APPLIED','PUBLISHED') AND EXISTS(SELECT 1 FROM cloud_continuities
  WHERE owner_id=NEW.owner_id AND continuity_id=NEW.campaign_id AND authority='cloud')
BEGIN SELECT RAISE(ABORT,'CLOUD_AUTHORITY_REQUIRED'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_enroll_existing BEFORE INSERT ON cloud_continuities
WHEN NEW.authority='cloud' AND (
  EXISTS(SELECT 1 FROM bridge_campaign_records WHERE owner_id=NEW.owner_id AND campaign_id=NEW.continuity_id)
  OR EXISTS(SELECT 1 FROM bridge_hp_records WHERE owner_id=NEW.owner_id AND campaign_id=NEW.continuity_id)
  OR EXISTS(SELECT 1 FROM bridge_records WHERE owner_id=NEW.owner_id AND campaign_id=NEW.continuity_id))
BEGIN SELECT RAISE(ABORT,'STAGED_MIGRATION_REQUIRED'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_enroll_pending BEFORE INSERT ON cloud_continuities
WHEN NEW.authority='cloud' AND (
  EXISTS(SELECT 1 FROM bridge_campaign_commands c WHERE c.owner_id=NEW.owner_id AND c.campaign_id=NEW.continuity_id
    AND NOT EXISTS(SELECT 1 FROM bridge_campaign_receipts r WHERE r.owner_id=c.owner_id AND r.campaign_id=c.campaign_id AND r.command_id=c.command_id)) OR EXISTS(SELECT 1 FROM bridge_hp_proposals c WHERE c.owner_id=NEW.owner_id AND c.campaign_id=NEW.continuity_id
    AND NOT EXISTS(SELECT 1 FROM bridge_hp_receipts r WHERE r.owner_id=c.owner_id AND r.campaign_id=c.campaign_id AND r.command_id=c.command_id)) OR EXISTS(SELECT 1 FROM bridge_proposals c WHERE c.owner_id=NEW.owner_id AND c.campaign_id=NEW.continuity_id
    AND NOT EXISTS(SELECT 1 FROM bridge_receipts r WHERE r.owner_id=c.owner_id AND r.campaign_id=c.campaign_id AND r.command_id=c.command_id)))
BEGIN SELECT RAISE(ABORT,'LEGACY_COMMAND_PENDING'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_cutover_pending BEFORE UPDATE OF authority ON cloud_continuities
WHEN OLD.authority<>'cloud' AND NEW.authority='cloud' AND (
  EXISTS(SELECT 1 FROM bridge_campaign_commands c WHERE c.owner_id=NEW.owner_id AND c.campaign_id=NEW.continuity_id
    AND NOT EXISTS(SELECT 1 FROM bridge_campaign_receipts r WHERE r.owner_id=c.owner_id AND r.campaign_id=c.campaign_id AND r.command_id=c.command_id)) OR EXISTS(SELECT 1 FROM bridge_hp_proposals c WHERE c.owner_id=NEW.owner_id AND c.campaign_id=NEW.continuity_id
    AND NOT EXISTS(SELECT 1 FROM bridge_hp_receipts r WHERE r.owner_id=c.owner_id AND r.campaign_id=c.campaign_id AND r.command_id=c.command_id)) OR EXISTS(SELECT 1 FROM bridge_proposals c WHERE c.owner_id=NEW.owner_id AND c.campaign_id=NEW.continuity_id
    AND NOT EXISTS(SELECT 1 FROM bridge_receipts r WHERE r.owner_id=c.owner_id AND r.campaign_id=c.campaign_id AND r.command_id=c.command_id)))
BEGIN SELECT RAISE(ABORT,'LEGACY_COMMAND_PENDING'); END;
