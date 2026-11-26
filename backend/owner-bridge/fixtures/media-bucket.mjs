import assert from 'node:assert/strict';
import { mkdtemp,readFile,writeFile,rm,stat } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { mediaDigest } from '../cloud-media-codec.mjs';

// Test the storage API with independently readable disk files, including a
// missing-object failure between the review preview and its actual decision.
export async function fileBucket(t) {
  const dir=await mkdtemp(join(tmpdir(),'astravar-media-'));
  t.after(()=>rm(dir,{force:true,recursive:true}));
  const path=key=>join(dir,key.replaceAll('/','_'));
  return {
    keys:new Set(),
    async put(key,bytes,options){
      assert.equal(await mediaDigest(bytes),options.sha256);
      await writeFile(path(key),bytes);
      this.keys.add(key);
    },
    async get(key){
      try {const bytes=await readFile(path(key)); return {body:new Blob([bytes]).stream(),size:bytes.length};}
      catch(error){if(error.code==='ENOENT')return null;throw error;}
    },
    async head(key){try{return {size:(await stat(path(key))).size};}catch(error){if(error.code==='ENOENT')return null;throw error;}},
    async delete(keys){for(const key of keys){await rm(path(key),{force:true});this.keys.delete(key);}},
  };
}
