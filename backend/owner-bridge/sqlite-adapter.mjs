/** Exact D1 prepared/batch API subset for durable local contract tests. Node >=24. */
import {DatabaseSync} from 'node:sqlite';
export class SqliteAdapter {
 constructor(path=':memory:') { this.db=new DatabaseSync(path); this.db.exec('PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;'); }
 exec(sql) { this.db.exec(sql); }
 prepare(sql) {
  const db=this.db;
  return {bind(...values) { const run=()=>{const r=db.prepare(sql).run(...values);return {success:true,meta:{changes:r.changes,last_row_id:r.lastInsertRowid}};};return {
   async first() { return db.prepare(sql).get(...values)??null; },
   async all() { return {results:db.prepare(sql).all(...values)}; },
   async run() { return run(); }, _run:run
  };}};
 }
 async batch(statements) {
  this.db.exec('BEGIN IMMEDIATE');
  try { const values=statements.map(statement=>statement._run()); this.db.exec('COMMIT'); return values; }
  catch(error){this.db.exec('ROLLBACK');throw error;}
 }
 close(){this.db.close();}
}
