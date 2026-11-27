import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from ascension_migrate_legacy import encode, sha
from ascension_migrate_provenance import historical_transfer, restored_component
from ascension_migrate_reviewed import source_key, path_value


class ProvenanceTest(unittest.TestCase):
    def test_restore_requires_same_physical_attack_and_both_edit_states(self):
        key=("original","playable.characters.attacks",("actor","tool-attack"))
        path=["damage",0,"type"]
        before={"id":"tool-attack","damage":[{"id":"first","type":"FIRE"}]}
        after={"id":"tool-attack","damage":[{"id":"first","type":"COLD"}]}
        def snapshot(a):
            return json.dumps({"character":{"id":"actor","attacks":[a]}})
        history={"profileId":"actor","beforeJson":snapshot(before),"afterJson":snapshot(after)}
        binding={"source":"original","family":"playable.changes","identity":["edit"],"rowSha256":sha(encode(history))}
        document={"historical_edit_binding":binding,"field_corrections":[{"path":path,"native":"COLD","accepted":"FIRE"}]}
        grant={"targetKind":"attack","sourcePath":path,"targetPath":["data","components",0,"type"],
               "acceptedPath":["field_corrections",0,"accepted"],"before":"COLD","after":"FIRE"}
        entries={key:after,source_key(binding):history}
        restored_component(grant,document,entries,key,path_value,source_key)
        history["afterJson"]=snapshot(before)
        binding["rowSha256"]=sha(encode(history))
        with self.assertRaisesRegex(ValueError,"Recorded edit"):
            restored_component(grant,document,entries,key,path_value,source_key)

    def test_transfer_cannot_retire_unimported_inventory(self):
        document={"transferId":"historical","capturedDate":"2020-01-01","character":{"targetProfileId":"actor","sourceUrl":"https://example.test"},
                  "inventory":[{"sourceId":"item"}]}
        ref={"externalId":"item","namespace":"notion","campaignId":"scope","source":"Notion private transfer historical · https://example.test"}
        connected={"exportedAt":1700000000000,"authority":{"actors":[{"actorId":"actor","playable":True,"campaignId":"scope"}]},
                   "playable":{"characters":[{"profileId":"actor"}]},"campaign":{"records":{"externalReferences":[ref]}}}
        self.assertTrue(historical_transfer(document,connected,("owner","scope")))
        ref["externalId"]="different"
        self.assertFalse(historical_transfer(document,connected,("owner","scope")))
        ref.update(externalId="item",campaignId="other")
        self.assertFalse(historical_transfer(document,connected,("owner","scope")))
