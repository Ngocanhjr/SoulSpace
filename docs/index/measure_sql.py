"""Đo SQL chỉ đọc trên cặp DB benchmark đã chuẩn bị theo README.

Cần psycopg trong data/demo-seed/requirements.txt. Cấp PGHOST, PGPORT,
FLYWAY_PASSWORD, DB_PASSWORD qua environment; không đưa mật khẩu vào tham số.
"""
import argparse, hashlib, json, math, os, statistics
from pathlib import Path
import psycopg
ROOT = Path(__file__).resolve().parents[2]

def conn(db, role="soulspace_migrator"):
    password_key = "DB_PASSWORD" if role == "soulspace_runtime" else "FLYWAY_PASSWORD"
    return psycopg.connect(host=os.environ.get("PGHOST", "127.0.0.1"),
                          port=int(os.environ.get("PGPORT", "5432")), dbname=db,
                          user=role, password=os.environ.get(password_key), autocommit=True,
                          options="-c default_transaction_read_only=on")

QUERIES = {
 'comments': '''SELECT id, blog_id, author_id, parent_id, content, likes_count, created_at, updated_at, deleted_at, version FROM public.comments WHERE blog_id = %s::uuid ORDER BY created_at DESC, id DESC''',
 'author': '''SELECT * FROM public.blogs WHERE author_id = %s::uuid AND published = true ORDER BY created_at DESC, id DESC''',
 'shares': '''SELECT b.*, s.shared_at FROM public.blog_shares s JOIN public.blogs b ON b.id = s.blog_id WHERE s.user_id = %s::uuid AND b.published = true ORDER BY s.shared_at DESC, s.blog_id DESC'''
}
def digest(value):
 return hashlib.sha256(json.dumps(value,default=str,sort_keys=True,ensure_ascii=False).encode()).hexdigest()
def summary(values):
 return {'n':len(values),'p50_ms':statistics.median(values),'p95_ms':sorted(values)[math.ceil(.95*len(values))-1],'min_ms':min(values),'max_ms':max(values)}
def snapshot(db):
 with conn(db) as c:
  tables=[r[0] for r in c.execute("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")]
  counts={}; hashes={}
  for t in tables:
   rows=c.execute(f'SELECT to_jsonb(t)::text FROM public."{t}" t ORDER BY to_jsonb(t)::text').fetchall()
   counts[t]=len(rows); hashes[t]=digest(rows)
  return {'counts':counts,'table_sha256':hashes,
   'indexes':c.execute("SELECT indexname,indexdef FROM pg_indexes WHERE schemaname='public' ORDER BY indexname").fetchall(),
   'constraints':c.execute("SELECT conrelid::regclass::text,conname,contype,pg_get_constraintdef(oid) FROM pg_constraint WHERE connamespace='public'::regnamespace ORDER BY 1,2").fetchall(),
   'migrations':c.execute('SELECT version,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank').fetchall(),
   'triggers':c.execute("SELECT pg_get_triggerdef(oid) FROM pg_trigger WHERE NOT tgisinternal ORDER BY tgname").fetchall(),
   'functions':c.execute("SELECT p.proname,md5(pg_get_functiondef(p.oid)) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.prokind IN ('f','p') ORDER BY 1").fetchall()}
def run(stage,with_db,without_db):
 result={'stage':stage,'warmup':5,'blocks':6,'samples_per_block':10,'order':'with,without / without,with alternating','queries':QUERIES,'databases':{}}
 for db in [with_db,without_db]: result['databases'][db]=snapshot(db)
 a,b=[result['databases'][db] for db in [with_db,without_db]]
 for key in ['counts','table_sha256','constraints','migrations','triggers','functions']: assert a[key]==b[key],key
 ia,ib=[{x[0] for x in s['indexes']} for s in [a,b]]
 assert len(ia-ib)==19 and not ib-ia
 result['checks']={'identical_data_schema_history':True,'index_delta':sorted(ia-ib)}
 with conn(with_db,'soulspace_runtime') as c:
  result['postgres_version']=c.execute('SELECT version()').fetchone()[0]
  settings=['autovacuum','max_connections','shared_buffers','work_mem','effective_cache_size','random_page_cost','seq_page_cost','jit','default_statistics_target','track_io_timing','synchronous_commit','fsync','max_parallel_workers_per_gather','enable_seqscan','enable_bitmapscan','enable_indexscan','statement_timeout','lock_timeout']
  result['settings']={k:c.execute('SELECT current_setting(%s)',(k,)).fetchone()[0] for k in settings}
  params={
   'comments':str(c.execute('SELECT c.blog_id FROM comments c JOIN blogs b ON b.id=c.blog_id WHERE b.published GROUP BY c.blog_id ORDER BY count(*) DESC,c.blog_id LIMIT 1').fetchone()[0]),
   'author':str(c.execute('SELECT author_id FROM blogs WHERE published GROUP BY author_id ORDER BY count(*) DESC,author_id LIMIT 1').fetchone()[0]),
   'shares':str(c.execute('SELECT user_id FROM blog_shares GROUP BY user_id ORDER BY count(*) DESC,user_id LIMIT 1').fetchone()[0])}
 result['parameters']=params
 conns={db:conn(db,'soulspace_runtime') for db in [with_db,without_db]}
 result['sql']={}
 try:
  for c in conns.values():
   c.prepare_threshold=None; c.execute('SET default_transaction_read_only=on')
  for name,q in QUERIES.items():
   result['sql'][name]={}
   for db,c in conns.items():
    rows=c.execute(q,(params[name],)).fetchall()
    result['sql'][name][db]={'rows':len(rows),'response_sha256':digest(rows),'samples':[]}
    for _ in range(5): c.execute('EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) '+q,(params[name],)).fetchone()
   assert len({r['response_sha256'] for r in result['sql'][name].values()})==1
  for block in range(6):
   order=[with_db,without_db] if block%2==0 else [without_db,with_db]
   for name,q in QUERIES.items():
    for db in order:
     for sample in range(10):
      plan=conns[db].execute('EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) '+q,(params[name],)).fetchone()[0][0]
      result['sql'][name][db]['samples'].append({'block':block+1,'sample':sample+1,**plan})
  for queries in result['sql'].values():
   for r in queries.values():
    r['execution']=summary([p['Execution Time'] for p in r['samples']])
    r['planning']=summary([p['Planning Time'] for p in r['samples']])
 finally:
  for c in conns.values(): c.close()
 out=ROOT/'docs/index/results'; out.mkdir(exist_ok=True)
 (out/f'{stage}-sql.json').write_text(json.dumps(result,default=str,ensure_ascii=False,indent=2)+'\n')
 print(stage,'SQL complete',flush=True)
 return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", choices=["demo", "large", "recheck"], required=True)
    parser.add_argument("--with-db", required=True)
    parser.add_argument("--without-db", required=True)
    args = parser.parse_args()
    target = ROOT / "docs/index/results" / (args.stage + "-sql.json")
    if target.exists():
        parser.error("File kết quả đã tồn tại; hãy lưu bản cũ trước khi đo lại: " + str(target))
    run(args.stage, args.with_db, args.without_db)
