import base64
import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parents[2] / 'tools'))
from ascension_migrate_attestations import FORMAT, index_attestations, verify_attestations
from ascension_migrate_documents import FORMAT as DOCUMENT_FORMAT, FAMILY, index_documents
from ascension_migrate_legacy import encode, sha
from ascension_migrate_reviewed import source_key, path_value


class AttestationTest(unittest.TestCase):
    def setUp(self):
        self.scope = ('owner-fixture','fixture-continuity')
        raw = b'track-1 | Fixture whistle | Returned by courier\r\n'
        doc = {'sourceId':'doc-fixture','versionId':'1','filename':'notes.md','mediaType':'text/markdown',
               'bytes':len(raw),'sha256':sha(raw),'base64':base64.b64encode(raw).decode()}
        self.docs = {'format':DOCUMENT_FORMAT,'version':1,'owner':self.scope[0],'continuity':self.scope[1],'documents':[doc]}
        self.dk = sha(encode(self.docs))
        _, rows = index_documents(self.docs)
        self.proof = {'source':self.dk,'family':FAMILY,'identity':['doc-fixture','1'],
                      'rowSha256':sha(encode(next(iter(rows.values())))),'line':1,'lineSha256':sha(raw.rstrip(b'\r\n'))}
        self.accepted = {'title':'Fixture whistle','quantity':1,'ledger_event':'track-1'}
        self.row = {'id':'track-1','category':'holding','decision':'review-one','acceptedPath':['item'],
                    'accepted':copy.deepcopy(self.accepted),'fields':{'title':'Fixture whistle','quantity':1},'proof':self.proof}
        self.input = {'format':FORMAT,'version':1,'owner':self.scope[0],'continuity':self.scope[1],'records':[self.row]}

    def check(self,source_pin=True):
        digest=sha(encode(self.input))
        raws={self.dk:encode(self.docs),digest:encode(self.input)}
        rows={(h,f,ids):r for h,group in ((digest,index_attestations(self.input)),(self.dk,index_documents(self.docs)))
              for (f,ids),r in group[1].items()}
        authority={'attestedSources':[digest] if source_pin else [],'decisions':{'review-one':{
            'decision_id':'review-one','authority':'fixture owner','verbatim':'Confirmed','item':self.accepted}}}
        verify_attestations(raws,{digest:FORMAT,self.dk:DOCUMENT_FORMAT},authority,rows,self.scope,source_key,path_value)

    def test_matches_owner_and_exact_separate_original(self):
        self.check()

    def test_rejects_missing_scope_pin_and_changed_evidence(self):
        with self.assertRaisesRegex(ValueError,'independent review'):
            self.check(False)
        self.row['proof']['lineSha256']='0'*64
        with self.assertRaisesRegex(ValueError,'original bytes'):
            self.check()

    def test_rejects_changed_quantity_or_decision(self):
        self.row['fields']['quantity']=2
        with self.assertRaisesRegex(ValueError,'quantity'):
            self.check()
        self.row['accepted']['quantity']=2
        with self.assertRaisesRegex(ValueError,'accepted decision'):
            self.check()


class InstalledFormSourceTest(unittest.TestCase):
    def test_other_actor_or_unaccepted_feature_cannot_acquire_the_form(self):
        from ascension_migrate_attestations import verify_installed_form
        from ascension_migrate_reviewed import source_key
        hold={"source":"archived","family":"review.attested_holdings","identity":["hardware"]}
        rule={"source":"physical","family":"playable.characters.features","identity":["operator","form-rule"]}
        source=source_key(rule)
        item={"id":"hardware","title":"Utility frame chassis","data":{"installedOnActorId":"operator","ownerId":"operator","custodianId":"operator","holding":"installed"}}
        form={"id":"frame","actorId":"operator","title":"Utility frame","data":{"requiresOperating":True,"sourceItemId":"hardware"}}
        native={"name":"Utility frame","source":{"acceptance":"ACCEPTED_HOMEBREW","name":"Chassis notes"}}
        ws=[{**hold,"sourcePath":["id"],"targetPath":["data","sourceItemId"],"transform":"canonicalRef","target":"frame"},
            {**rule,"sourcePath":["id"],"targetPath":["id"],"transform":"scopedIdentity","target":"frame"},
            {**rule,"sourcePath":["source","name"],"targetPath":["data","source"],"transform":"exact","target":"frame"}]
        plan={"parity":ws,"links":[{**hold,"from":"frame","relation":"requires_item","to":"hardware","targetPath":["id"]}]}
        def check():
            verify_installed_form(form,item,source_key(hold),{source:native},{source:{"disposition":"map"}},plan,source_key)
        check()
        form["actorId"]="another"
        with self.assertRaisesRegex(ValueError,"own actor"):
            check()
        form["actorId"]="operator"
        native["source"]["acceptance"]="PROPOSED"
        with self.assertRaisesRegex(ValueError,"accepted native form"):
            check()
        native["source"]["acceptance"]="OFFICIAL"
        ws.append({**ws[0],"targetPath":["data","baseAc"]})
        with self.assertRaisesRegex(ValueError,"only witness"):
            check()
