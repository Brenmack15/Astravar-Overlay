CREATE INDEX bridge_campaign_placement_holder ON bridge_campaign_records(owner_id,campaign_id,grant_id,kind,json_extract(fields_json,'$.holderProfileId'),record_id);
--> statement-breakpoint
CREATE INDEX bridge_campaign_property_item ON bridge_campaign_records(owner_id,campaign_id,grant_id,kind,json_extract(fields_json,'$.itemId'),record_id);

--> statement-breakpoint
CREATE INDEX bridge_campaign_companion_resource ON bridge_campaign_records(owner_id,campaign_id,grant_id,kind,json_extract(fields_json,'$.companionId'),record_id);
