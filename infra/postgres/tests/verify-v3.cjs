const fs = require('node:fs');
const assert = require('node:assert/strict');
const { PGlite } = require(process.env.SOULSPACE_PGLITE_MODULE);

(async () => {
    const db = new PGlite();
    try {
        console.log((await db.query('SELECT version()')).rows[0].version);
        await db.exec(`CREATE ROLE soulspace_migrator; CREATE ROLE soulspace_runtime;
            ALTER SCHEMA public OWNER TO soulspace_migrator;
            GRANT USAGE ON SCHEMA public TO soulspace_runtime;
            SET ROLE soulspace_migrator;
            ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO soulspace_runtime;
            ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;`);
        const dir = 'soulspace/src/main/resources/db/migration';
        for (const name of fs.readdirSync(dir).filter(n => /^V\d+__.*\.sql$/.test(n)).sort()) {
            await db.exec('BEGIN;\n' + fs.readFileSync(dir + '/' + name, 'utf8') + '\nCOMMIT;');
            console.log('PASS migration', name);
        }
        await db.exec('SET ROLE soulspace_runtime;');
        for (const name of ['invariants.sql', 'public_blog_feed.sql', 'blog_categories.sql', 'notifications.sql']) {
            await db.exec(fs.readFileSync('infra/postgres/tests/' + name, 'utf8')
                .replace(/^\s*\\set\s+ON_ERROR_STOP\s+on\b/gm, '').replace(/^\\.*$/gm, ''));
            console.log('PASS suite', name);
        }
        await db.exec(`SET ROLE soulspace_migrator; BEGIN;
            CREATE TEMP TABLE v3_scope_trace(name text);
            GRANT ALL ON v3_scope_trace TO soulspace_runtime;`);
        for (const name of ['check_nonempty_blog', 'check_deleted_comment_likes']) {
            const original = (await db.query('SELECT pg_get_functiondef($1::regprocedure) AS body', [name + '()'])).rows[0].body;
            const instrumented = original.replace(/\bBEGIN\b/, `BEGIN INSERT INTO pg_temp.v3_scope_trace VALUES ('${name}');`);
            assert.notEqual(instrumented, original);
            await db.exec(instrumented);
        }
        await db.exec(`SET ROLE soulspace_runtime;
            INSERT INTO users(id,username,password_hash,fullname) VALUES('10000000-0000-0000-0000-000000000001','v3scope','TEST_ONLY','Scope');
            INSERT INTO blogs(id,author_id,content) VALUES('20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','Text');
            INSERT INTO comments(id,blog_id,author_id,content) VALUES('30000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','Comment');
            SET CONSTRAINTS ALL IMMEDIATE; SET CONSTRAINTS ALL DEFERRED; DELETE FROM pg_temp.v3_scope_trace;
            CALL sp_set_blog_like('10000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001',true);
            CALL sp_set_comment_like('10000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001',true);
            UPDATE blogs SET published=false WHERE id='20000000-0000-0000-0000-000000000001';
            UPDATE comments SET content='Edited' WHERE id='30000000-0000-0000-0000-000000000001';
            SET CONSTRAINTS ALL IMMEDIATE;`);
        assert.equal((await db.query('SELECT count(*)::int AS n FROM pg_temp.v3_scope_trace')).rows[0].n, 0);
        console.log('PASS no deferred checks for blog/comment likes, visibility or active comment edit');
        await db.exec(`SET CONSTRAINTS ALL DEFERRED;
            UPDATE blogs SET content='New text' WHERE id='20000000-0000-0000-0000-000000000001';
            SET CONSTRAINTS ALL IMMEDIATE;`);
        assert.deepEqual((await db.query('SELECT name FROM pg_temp.v3_scope_trace')).rows, [{ name: 'check_nonempty_blog' }]);
        console.log('PASS content UPDATE still runs nonempty check');
        await db.exec(`DELETE FROM pg_temp.v3_scope_trace; SET CONSTRAINTS ALL DEFERRED;
            UPDATE comments SET content=NULL,deleted_at=clock_timestamp() WHERE id='30000000-0000-0000-0000-000000000001';
            DELETE FROM comment_likes WHERE comment_id='30000000-0000-0000-0000-000000000001';
            SET CONSTRAINTS ALL IMMEDIATE;`);
        const checks = (await db.query('SELECT name FROM pg_temp.v3_scope_trace')).rows;
        assert(checks.length > 0 && checks.every(r => r.name === 'check_deleted_comment_likes'));
        console.log('PASS deleted comment is deferred; counter-only blog UPDATE skips nonempty check');
        await db.exec('ROLLBACK;');
    } finally {
        await db.close();
    }
})().catch(e => { console.error(e.code, e.message); process.exitCode = 1; });
