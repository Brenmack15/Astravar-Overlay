-- Additive V2 owner grant, bounded record mirror and durable command delivery.
CREATE TABLE bridge_campaign_pairings(pairing_id TEXT PRIMARY KEY REFERENCES bridge_pairings(id),read_scopes_json TEXT NOT NULL CHECK(json_valid(read_scopes_json)),write_scopes_json TEXT NOT NULL CHECK(json_valid(write_scopes_json)));
--> statement-breakpoint
CREATE TABLE bridge_campaign_grants(id TEXT PRIMARY KEY,owner_id TEXT NOT NULL,campaign_id TEXT NOT NULL,credential_id TEXT NOT NULL REFERENCES bridge_credentials(id),revision INTEGER NOT NULL DEFAULT 1,read_scopes_json TEXT NOT NULL,write_scopes_json TEXT NOT NULL,auto_scopes_json TEXT NOT NULL DEFAULT '[]',mirror_generation INTEGER NOT NULL DEFAULT 0,expires_at INTEGER NOT NULL,revoked INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL);
--> statement-breakpoint
CREATE INDEX bridge_campaign_grants_scope ON bridge_campaign_grants(owner_id,campaign_id,credential_id);
--> statement-breakpoint
CREATE TABLE bridge_campaign_grant_events(owner_id TEXT NOT NULL,command_id TEXT NOT NULL,grant_id TEXT NOT NULL REFERENCES bridge_campaign_grants(id),expected_revision INTEGER NOT NULL,auto_scopes_json TEXT NOT NULL,read_scopes_json TEXT NOT NULL,write_scopes_json TEXT NOT NULL,fingerprint TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(owner_id,command_id));
--> statement-breakpoint
CREATE TRIGGER bridge_campaign_grant_event_apply BEFORE INSERT ON bridge_campaign_grant_events BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM bridge_campaign_grants WHERE id=NEW.grant_id AND owner_id=NEW.owner_id AND revision=NEW.expected_revision AND revoked=0 AND expires_at>NEW.created_at) THEN RAISE(ABORT,'GRANT_REVISION_CONFLICT') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM json_each(NEW.auto_scopes_json) s WHERE s.value NOT IN(SELECT value FROM json_each((SELECT write_scopes_json FROM bridge_campaign_grants WHERE id=NEW.grant_id)))) THEN RAISE(ABORT,'AUTO_SYNC_SCOPE_DENIED') END;
 UPDATE bridge_campaign_grants SET auto_scopes_json=NEW.auto_scopes_json,read_scopes_json=NEW.read_scopes_json,write_scopes_json=NEW.write_scopes_json,revision=revision+1 WHERE id=NEW.grant_id;
END;
--> statement-breakpoint
CREATE TABLE bridge_campaign_records(owner_id TEXT NOT NULL,campaign_id TEXT NOT NULL,kind TEXT NOT NULL,record_id TEXT NOT NULL,revision INTEGER NOT NULL,fields_json TEXT NOT NULL,grant_id TEXT NOT NULL REFERENCES bridge_campaign_grants(id),visibility_revision INTEGER NOT NULL DEFAULT 1,published_at INTEGER NOT NULL,last_device_sync_at INTEGER NOT NULL,PRIMARY KEY(owner_id,campaign_id,kind,record_id));
--> statement-breakpoint
CREATE INDEX bridge_campaign_records_grant ON bridge_campaign_records(grant_id,kind,record_id);
--> statement-breakpoint
CREATE TABLE bridge_campaign_publications(owner_id TEXT NOT NULL,campaign_id TEXT NOT NULL,command_id TEXT NOT NULL,grant_id TEXT NOT NULL REFERENCES bridge_campaign_grants(id),fingerprint TEXT NOT NULL,records_json TEXT NOT NULL,removed_json TEXT NOT NULL DEFAULT '[]',expected_generation INTEGER,created_at INTEGER NOT NULL,PRIMARY KEY(owner_id,campaign_id,command_id));
--> statement-breakpoint
CREATE TRIGGER bridge_campaign_publication_apply BEFORE INSERT ON bridge_campaign_publications BEGIN
 SELECT CASE WHEN NEW.expected_generation IS NOT NULL AND NOT EXISTS(SELECT 1 FROM bridge_campaign_grants WHERE id=NEW.grant_id AND mirror_generation=NEW.expected_generation) THEN RAISE(ABORT,'MIRROR_SNAPSHOT_CONFLICT') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM json_each(NEW.removed_json) n LEFT JOIN bridge_campaign_records r ON r.grant_id=NEW.grant_id AND r.kind=json_extract(n.value,'$.kind') AND r.record_id=json_extract(n.value,'$.recordId') WHERE r.revision IS NULL OR r.revision<>json_extract(n.value,'$.expectedRevision')) THEN RAISE(ABORT,'REVISION_CONFLICT') END;
 DELETE FROM bridge_campaign_records WHERE grant_id=NEW.grant_id AND EXISTS(SELECT 1 FROM json_each(NEW.removed_json) n WHERE json_extract(n.value,'$.kind')=bridge_campaign_records.kind AND json_extract(n.value,'$.recordId')=bridge_campaign_records.record_id);
 UPDATE bridge_campaign_grants SET mirror_generation=mirror_generation+1 WHERE id=NEW.grant_id AND (json_array_length(NEW.records_json)>0 OR json_array_length(NEW.removed_json)>0);
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM bridge_campaign_grants g JOIN bridge_credentials c ON c.id=g.credential_id WHERE g.id=NEW.grant_id AND g.owner_id=NEW.owner_id AND g.campaign_id=NEW.campaign_id AND g.revoked=0 AND g.expires_at>NEW.created_at AND c.revoked=0 AND c.expires_at>NEW.created_at) THEN RAISE(ABORT,'GRANT_EXPIRED') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM json_each(NEW.records_json) n JOIN bridge_campaign_records r ON r.owner_id=NEW.owner_id AND r.campaign_id=NEW.campaign_id AND r.kind=json_extract(n.value,'$.kind') AND r.record_id=json_extract(n.value,'$.recordId') WHERE r.revision>json_extract(n.value,'$.revision') OR (r.grant_id=NEW.grant_id AND r.visibility_revision=(SELECT revision FROM bridge_campaign_grants WHERE id=NEW.grant_id) AND r.revision=json_extract(n.value,'$.revision') AND r.fields_json<>json_extract(n.value,'$.fields'))) THEN RAISE(ABORT,'REVISION_CONFLICT') END;
 UPDATE bridge_campaign_records SET last_device_sync_at=NEW.created_at WHERE grant_id=NEW.grant_id AND NEW.expected_generation IS NOT NULL;
 INSERT INTO bridge_campaign_records(owner_id,campaign_id,kind,record_id,revision,fields_json,grant_id,visibility_revision,published_at,last_device_sync_at) SELECT NEW.owner_id,NEW.campaign_id,json_extract(value,'$.kind'),json_extract(value,'$.recordId'),json_extract(value,'$.revision'),json_extract(value,'$.fields'),NEW.grant_id,(SELECT revision FROM bridge_campaign_grants WHERE id=NEW.grant_id),NEW.created_at,NEW.created_at FROM json_each(NEW.records_json) WHERE 1 ON CONFLICT(owner_id,campaign_id,kind,record_id) DO UPDATE SET revision=excluded.revision,fields_json=excluded.fields_json,grant_id=excluded.grant_id,visibility_revision=excluded.visibility_revision,published_at=excluded.published_at,last_device_sync_at=excluded.last_device_sync_at;
END;
--> statement-breakpoint
CREATE TABLE bridge_campaign_commands(owner_id TEXT NOT NULL,campaign_id TEXT NOT NULL,command_id TEXT NOT NULL,grant_id TEXT NOT NULL REFERENCES bridge_campaign_grants(id),grant_revision INTEGER NOT NULL,request_fingerprint TEXT NOT NULL,fingerprint TEXT NOT NULL,command_json TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(owner_id,campaign_id,command_id));
--> statement-breakpoint
CREATE TRIGGER bridge_campaign_command_validate BEFORE INSERT ON bridge_campaign_commands BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM bridge_campaign_grants g JOIN bridge_credentials c ON c.id=g.credential_id WHERE g.id=NEW.grant_id AND g.owner_id=NEW.owner_id AND g.campaign_id=NEW.campaign_id AND g.revision=NEW.grant_revision AND g.revoked=0 AND g.expires_at>NEW.created_at AND c.revoked=0 AND c.expires_at>NEW.created_at) THEN RAISE(ABORT,'GRANT_REVISION_CONFLICT') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM json_each(NEW.command_json,'$.operations') n LEFT JOIN bridge_campaign_records r ON r.owner_id=NEW.owner_id AND r.campaign_id=NEW.campaign_id AND r.kind=json_extract(n.value,'$.kind') AND r.record_id=json_extract(n.value,'$.recordId') WHERE COALESCE(r.revision,0)<>json_extract(n.value,'$.expectedRevision')) THEN RAISE(ABORT,'REVISION_CONFLICT') END;
 SELECT CASE WHEN (SELECT COUNT(*) FROM bridge_campaign_commands c LEFT JOIN bridge_campaign_receipts r ON r.owner_id=c.owner_id AND r.campaign_id=c.campaign_id AND r.command_id=c.command_id WHERE c.grant_id=NEW.grant_id AND c.grant_revision=NEW.grant_revision AND r.command_id IS NULL)>=100 THEN RAISE(ABORT,'PENDING_LIMIT') END;
END;
--> statement-breakpoint
CREATE TABLE bridge_campaign_receipts(sequence INTEGER PRIMARY KEY AUTOINCREMENT,owner_id TEXT NOT NULL,campaign_id TEXT NOT NULL,command_id TEXT NOT NULL,grant_id TEXT NOT NULL REFERENCES bridge_campaign_grants(id),request_fingerprint TEXT NOT NULL,receipt_json TEXT NOT NULL,records_json TEXT NOT NULL,created_at INTEGER NOT NULL,UNIQUE(owner_id,campaign_id,command_id));
--> statement-breakpoint
CREATE TRIGGER bridge_campaign_receipt_apply BEFORE INSERT ON bridge_campaign_receipts WHEN json_extract(NEW.receipt_json,'$.status')='APPLIED' BEGIN
 UPDATE bridge_campaign_grants SET mirror_generation=mirror_generation+1 WHERE id=NEW.grant_id;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM json_each(NEW.records_json) n LEFT JOIN bridge_campaign_records r ON r.owner_id=NEW.owner_id AND r.campaign_id=NEW.campaign_id AND r.kind=json_extract(n.value,'$.kind') AND r.record_id=json_extract(n.value,'$.recordId') WHERE COALESCE(r.revision,0)<>json_extract(n.value,'$.revision')-1) THEN RAISE(ABORT,'REVISION_CONFLICT') END;
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM bridge_campaign_grants g JOIN bridge_credentials d ON d.id=g.credential_id JOIN bridge_campaign_commands c ON c.grant_id=g.id AND c.command_id=NEW.command_id AND c.owner_id=NEW.owner_id AND c.campaign_id=NEW.campaign_id WHERE g.id=NEW.grant_id AND g.revision=c.grant_revision AND g.revoked=0 AND g.expires_at>NEW.created_at AND d.revoked=0 AND d.expires_at>NEW.created_at) THEN RAISE(ABORT,'GRANT_REVISION_CONFLICT') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM json_each((SELECT command_json FROM bridge_campaign_commands WHERE owner_id=NEW.owner_id AND campaign_id=NEW.campaign_id AND command_id=NEW.command_id),'$.operations') n LEFT JOIN bridge_campaign_records r ON r.owner_id=NEW.owner_id AND r.campaign_id=NEW.campaign_id AND r.kind=json_extract(n.value,'$.kind') AND r.record_id=json_extract(n.value,'$.recordId') WHERE COALESCE(r.revision,0)<>json_extract(n.value,'$.expectedRevision')) THEN RAISE(ABORT,'REVISION_CONFLICT') END;
 INSERT INTO bridge_campaign_records(owner_id,campaign_id,kind,record_id,revision,fields_json,grant_id,visibility_revision,published_at,last_device_sync_at) SELECT NEW.owner_id,NEW.campaign_id,json_extract(value,'$.kind'),json_extract(value,'$.recordId'),json_extract(value,'$.revision'),json_extract(value,'$.fields'),NEW.grant_id,(SELECT revision FROM bridge_campaign_grants WHERE id=NEW.grant_id),NEW.created_at,NEW.created_at FROM json_each(NEW.records_json) WHERE 1 ON CONFLICT(owner_id,campaign_id,kind,record_id) DO UPDATE SET revision=excluded.revision,fields_json=excluded.fields_json,grant_id=excluded.grant_id,visibility_revision=excluded.visibility_revision,published_at=excluded.published_at,last_device_sync_at=excluded.last_device_sync_at;
END;
--> statement-breakpoint
CREATE TRIGGER bridge_campaign_receipt_immutable_update BEFORE UPDATE ON bridge_campaign_receipts BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT'); END;
--> statement-breakpoint
CREATE TRIGGER bridge_campaign_receipt_immutable_delete BEFORE DELETE ON bridge_campaign_receipts BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT'); END;
--> statement-breakpoint
CREATE TRIGGER bridge_campaign_command_immutable_update BEFORE UPDATE ON bridge_campaign_commands BEGIN SELECT RAISE(ABORT,'IMMUTABLE_COMMAND'); END;
--> statement-breakpoint
CREATE TRIGGER bridge_campaign_command_immutable_delete BEFORE DELETE ON bridge_campaign_commands BEGIN SELECT RAISE(ABORT,'IMMUTABLE_COMMAND'); END;
--> statement-breakpoint
CREATE TRIGGER bridge_campaign_publication_immutable_update BEFORE UPDATE ON bridge_campaign_publications BEGIN SELECT RAISE(ABORT,'IMMUTABLE_PUBLICATION'); END;
--> statement-breakpoint
CREATE TRIGGER bridge_campaign_publication_immutable_delete BEFORE DELETE ON bridge_campaign_publications BEGIN SELECT RAISE(ABORT,'IMMUTABLE_PUBLICATION'); END;
--> statement-breakpoint
CREATE TRIGGER bridge_campaign_grant_event_immutable_update BEFORE UPDATE ON bridge_campaign_grant_events BEGIN SELECT RAISE(ABORT,'IMMUTABLE_GRANT_EVENT'); END;
--> statement-breakpoint
CREATE TRIGGER bridge_campaign_grant_event_immutable_delete BEFORE DELETE ON bridge_campaign_grant_events BEGIN SELECT RAISE(ABORT,'IMMUTABLE_GRANT_EVENT'); END;
