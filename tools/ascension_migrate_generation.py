"""Export and independently verify an unrouted, separately provisioned provider generation."""
import argparse
import json
import os
import re
import sys
import tempfile
import uuid
from contextlib import closing
from pathlib import Path

import ascension_migrate as migration
from ascension_migrate_legacy import file_hash
from ascension_provider_package import prepare

FORMAT = "astravar-offline-provider-generation-v1"


def require(value, message):
    migration.verify(value, message)


def write(path, value):
    with path.open("xb") as f:
        f.write(migration.encode(value))


def target_spec(purpose, generation, db, bucket):
    require(purpose in ("isolated", "production-candidate"), "Unknown candidate purpose")
    require(str(uuid.UUID(generation))==generation and str(uuid.UUID(db))==db and db!=generation,
            "Expected distinct canonical generation and D1 UUIDs")
    require(re.fullmatch(r"[a-z0-9][a-z0-9-]{1,61}[a-z0-9]",bucket), "Invalid private R2 bucket name")
    return {"purpose":purpose,"generation":generation,"d1DatabaseId":db,"privateBucket":bucket}


def table_summaries(rows):
    return {name:{"rows":len(rows[name]),"sha256":migration.sha(migration.encode(rows[name]))} for name in migration.TABLES}


def object_inventory(bundle):
    keyed=migration.media_locations(bundle["rows"],bundle["tenant"])
    return [{"key":key,"sha256":d,"bytes":size} for key,(d,size,*_) in sorted(keyed.items())]


def export(package,destination,purpose,generation,database,bucket,package_sha):
    require(destination.parent.is_dir() and not destination.exists() and not destination.is_symlink(),"A new output directory is required")
    require(file_hash(package)==package_sha, "Supplied package fingerprint differs")
    spec=target_spec(purpose,generation,database,bucket)
    bundle=migration.read_bundle(package);migration.check_database(bundle)
    holds=sum(report.get("cutoverHolds",0) for _,report in bundle["sources"].values())
    require(purpose!="production-candidate" or holds==0,"Review holds prevent production candidate export")
    snapshot=bundle["manifest"]["source"]
    with tempfile.TemporaryDirectory(dir=destination.parent) as tmp:
        output=Path(tmp)/"release";output.mkdir(mode=0o700)
        prepare(output,package)
        with closing(migration.make_database(":memory:")) as schema:
            layout={n:list(migration.columns(schema,n)) for n in migration.TABLES}
            schema_objects=[[row["type"],row["name"]," ".join(row["sql"].split())] for row in schema.execute(
                "SELECT type,name,sql FROM sqlite_master WHERE (tbl_name GLOB 'cloud_*' OR tbl_name GLOB 'bridge_*') AND sql IS NOT NULL ORDER BY name")]
        authority="INSERT INTO cloud_continuities(owner_id,continuity_id,authority,epoch,revision,schema_version) VALUES(?,?,'staged',?,?,?)"
        batches=[{"sql":authority,"params":[*bundle["tenant"],snapshot["epoch"],snapshot["revision"],snapshot["schema"]]}]
        for n,cols in layout.items():
            stmt="INSERT INTO "+n+" ("+",".join(cols)+") SELECT "+",".join("json_extract(value,'$."+c+"')" for c in cols)+" FROM json_each(?)"
            pending=[];size=0
            for row in bundle["rows"][n]:
                count=len(migration.encode(row))+1
                require(count<1750000,"Source row exceeds supported D1 binding size")
                if pending and size+count>128000:
                    batches.append({"sql":stmt,"params":[migration.encode(pending).decode()]});pending=[];size=0
                pending.append(row);size+=count
            if pending:batches.append({"sql":stmt,"params":[migration.encode(pending).decode()]})
        require(len(batches)<=500,"Staged D1 transaction exceeds the supported statement budget")
        write(output/"d1-batch.json",batches)
        # The canonical source package is retained separately; no grant or online endpoint is part of this kit.
        (output/"snapshot.json").unlink()
        plan={"format":FORMAT,**spec,"packageSha256":package_sha,"schemaSqlSha256":file_hash(migration.SQL),
              "tenant":list(bundle["tenant"]),"sourceState":snapshot,"requiredFinalAuthority":"staged","holds":holds,
              "tables":table_summaries(bundle["rows"]),"objects":object_inventory(bundle),"schemaObjects":schema_objects,
              "d1BatchSha256":file_hash(output/"d1-batch.json"),"statementCount":len(batches),
              "precondition":"Dedicated unused bindings, no routing, all application SQL tables empty, empty private bucket, exact schema.",
              "postcondition":"Exact staged tenant, no grants or credentials, every ordered table and every full private object independently verified. No cutover."}
        write(output/"generation.json",plan)
        require(file_hash(package)==package_sha and not destination.exists(),"Source or destination changed during export")
        output.rename(destination)
    return {"generationManifestSha256":file_hash(destination/"generation.json"),"packageSha256":package_sha,
            "purpose":purpose,"holds":holds,"statements":len(batches),"objectCount":len(plan["objects"])}


def check_readback(folder,manifest_sha,archive,inventory):
    require(file_hash(folder/"generation.json")==manifest_sha,"Generation fingerprint differs")
    plan=migration.loads((folder/"generation.json").read_bytes())
    require(plan["format"]==FORMAT and file_hash(folder/"d1-batch.json")==plan["d1BatchSha256"],"Generation plan changed")
    actual=migration.read_bundle(archive);migration.check_database(actual)
    state=actual["manifest"]["source"]
    expected={**plan["sourceState"],"authority":"staged"}
    require(list(actual["tenant"])==plan["tenant"] and state==expected,"Readback tenant, writer, epoch or revision differs")
    require(table_summaries(actual["rows"])==plan["tables"] and object_inventory(actual)==plan["objects"],"Independent row or object readback differs")
    seen=migration.loads(inventory.read_bytes())
    required={k:plan[k] for k in ("generation","d1DatabaseId","privateBucket")}
    require(all(seen.get(k)==v for k,v in required.items()) and seen.get("routed") is False and seen.get("private") is True and
            seen.get("schemaSqlSha256")==plan["schemaSqlSha256"] and seen.get("tenantCount")==1 and
            seen.get("unpackedForeignRows")==0 and seen.get("liveGrants")==0 and seen.get("credentials")==0 and
            seen.get("objectKeys")==[x["key"] for x in plan["objects"]],"Independent binding identity, isolation or full inventory differs")
    return {"status":"VERIFIED_UNROUTED_GENERATION","generationManifestSha256":manifest_sha,"readbackSha256":file_hash(archive),
            "tables":len(plan["tables"]),"rows":sum(v["rows"] for v in plan["tables"].values()),"objects":len(plan["objects"]),
            "authority":"staged","purpose":plan["purpose"],"holds":plan["holds"]}


def main():
    p=argparse.ArgumentParser(description=__doc__);ops=p.add_subparsers(required=True,dest="operation")
    e=ops.add_parser("export");e.add_argument("package",type=Path);e.add_argument("destination",type=Path)
    for flag in ("purpose","generation","database","bucket","package-sha"):e.add_argument("--"+flag,required=True)
    v=ops.add_parser("verify-readback");v.add_argument("folder",type=Path);v.add_argument("archive",type=Path)
    v.add_argument("inventory",type=Path);v.add_argument("--manifest-sha",required=True)
    a=p.parse_args()
    if a.operation=="export":return export(a.package,a.destination,a.purpose,a.generation,a.database,a.bucket,a.package_sha)
    return check_readback(a.folder,a.manifest_sha,a.archive,a.inventory)


if __name__=="__main__":
    os.umask(0o077)
    try: print(json.dumps(main(),sort_keys=True))
    except (ValueError,TypeError,KeyError,OSError) as error: print(error,file=sys.stderr);sys.exit(1)
