import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from ascension_migrate_funds import confirmed_funds, validate_fund_target
from ascension_migrate_reviewed import path_value, scoped_identity


class ConfirmedFundsTest(unittest.TestCase):
    def setUp(self):
        self.scope = ("fixture-owner", "fixture-continuity")
        self.actor = {"actorId": "actor", "campaignId": self.scope[1]}
        self.key = ("digest", "authority.actors", ("actor",))
        wid = scoped_identity(self.scope, self.key[1], self.key[2], "wallet", "actor")
        self.grant = {"source": self.key[0], "family": self.key[1], "identity": ["actor"], "target": wid,
                      "targetKind": "wallet", "sourcePath": ["actorId"], "targetPath": ["data","coins","sp"],
                      "before": None, "after": 7, "acceptedPath": ["components",0,"confirmed_held_sp"]}
        self.doc = {"components": [{"actor_id": "actor", "confirmed_held_sp": 7}]}
        self.wallet = {"id": wid, "actorId": "actor", "data": {"shared": False, "holderId": "actor",
                       "confirmedComponentOnly": True}}
        self.entries = {self.key: self.actor}

    def verify(self):
        confirmed_funds(self.grant, self.doc, self.actor, path_value, self.scope)
        validate_fund_target(self.grant, self.wallet, self.grant["target"], self.entries)

    def test_scoped_component(self):
        self.verify()

    def test_rejects_unverified_amount_denomination_or_subject(self):
        for changed in ({"after": 8}, {"before": 1}, {"identity":["a-second-actor"]},
                        {"targetPath":["data","coins","gp"]}, {"family":"records.wallets"}):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                confirmed_funds(self.grant | changed,self.doc,self.actor,path_value,self.scope)
        with self.assertRaises(ValueError):
            confirmed_funds(self.grant,self.doc,self.actor,path_value,("fixture-owner","another-continuity"))

    def test_refuses_duplicate_or_complete_wallet(self):
        self.entries["other","records.wallets",("saved",)] = {"holderProfileId":"actor","campaignId":self.scope[1]}
        with self.assertRaisesRegex(ValueError,"duplicate"):
            self.verify()
        del self.entries["other","records.wallets",("saved",)]
        self.wallet["data"]["confirmedComponentOnly"] = False
        with self.assertRaisesRegex(ValueError,"component wallet"):
            self.verify()
