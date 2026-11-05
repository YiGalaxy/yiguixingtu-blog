"""Deploy locally built artifacts; keep the original database and containers intact."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import time
import urllib.request

ROOT = Path('/srv/yiguixingtu-blog')
SOURCE_DB = 'yiguixingtu'
TARGET_DB = 'yiguixingtu_blog_v3'
MYSQL = 'yiguixingtu-prod-mysql'
NGINX = Path('/etc/nginx/sites-available/yiguixingtu.conf')
PUBLIC = 'https://www.yigalaxy.xin'


def run(args, **kwargs):
    result = subprocess.run(args, capture_output=True, **kwargs)
    if result.returncode:
        raise RuntimeError(f'{args[0]} failed (exit {result.returncode})')
    return result.stdout


def inspect(name):
    return json.loads(run(['docker', 'inspect', name]))[0]


def mysql(sql):
    return run(['docker', 'exec', '-i', MYSQL, 'sh', '-c',
                'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --default-character-set=utf8mb4 --batch --raw --skip-column-names'],
               input=sql, text=True)


def literal(value):
    # Hex strings avoid escaping SQL and preserve Chinese content.
    return "CONVERT(X'" + str(value).encode().hex() + "' USING utf8mb4)"


def rows(statement):
    return [json.loads(line) for line in mysql(statement).splitlines() if line]


def compose(*args):
    return run(['docker', 'compose', '--project-directory', str(ROOT), '-f', str(ROOT / 'compose.yaml'), *args], text=True)


def wait(url, attempts=60):
    for _ in range(attempts):
        try:
            with urllib.request.urlopen(url, timeout=10) as response:
                return response.read()
        except Exception:
            time.sleep(2)
    raise RuntimeError('Readiness check failed: ' + url)


def snapshot(directory):
    directory.mkdir(parents=True, exist_ok=False, mode=0o700)
    shutil.copy2(NGINX, directory / 'nginx.conf')
    for name in ['docker-compose.prod.yaml', 'docker-compose.images.yaml', '.env']:
        shutil.copy2(Path('/srv/yiguixingtu') / name, directory / name)
    (directory / 'containers.json').write_bytes(run(['docker', 'inspect', 'yiguixingtu-prod-backend', 'yiguixingtu-prod-frontend', MYSQL, 'yiguixingtu-prod-redis']))
    dump = run(['docker', 'exec', MYSQL, 'sh', '-c',
                'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysqldump -uroot --single-transaction --routines --triggers --events --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 yiguixingtu'])
    with gzip.open(directory / 'database.sql.gz', 'wb') as stream:
        stream.write(dump)
    source_uploads = next(Path(m['Source']) for m in inspect('yiguixingtu-prod-backend')['Mounts'] if m['Destination'] == '/app/uploads')
    run(['tar', '-czf', str(directory / 'uploads.tar.gz'), '-C', str(source_uploads), '.'])
    # Restore the actual backup into an isolated schema before accepting it.
    restore_db = 'yiguixingtu_restore_check'
    if mysql(f"SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{restore_db}';").strip() != '0':
        raise RuntimeError('Backup verification schema already exists')
    mysql(f'CREATE DATABASE `{restore_db}` CHARACTER SET utf8mb4;')
    mysql(f'USE `{restore_db}`;\n' + dump.decode())
    for table in ['article', 'comment', 'user', 'article_attachment', 'visit_log']:
        counts = mysql(f'SELECT (SELECT COUNT(*) FROM `{SOURCE_DB}`.`{table}`),(SELECT COUNT(*) FROM `{restore_db}`.`{table}`);').strip().split('\t')
        if counts[0] != counts[1]:
            raise RuntimeError('Backup restore count mismatch: ' + table)
    mysql(f'DROP DATABASE `{restore_db}`;')
    manifest = {file.name: hashlib.sha256(file.read_bytes()).hexdigest() for file in directory.iterdir() if file.is_file()}
    (directory / 'sha256.json').write_text(json.dumps(manifest, indent=2))
    (directory / 'restore-verified').write_text(time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()))
    print('Database and media backed up; isolated restore verified.', flush=True)
    return source_uploads


def prepare(release):
    ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
    if (ROOT / 'production.env').exists():
        raise RuntimeError('Production configuration already exists; inspect before reusing')
    source_uploads = snapshot(release / 'backup-initial')
    shutil.copytree(source_uploads, ROOT / 'legacy-uploads')
    shutil.copy2(release / 'compose.yaml', ROOT / 'compose.yaml')
    password = secrets.token_urlsafe(36)
    if mysql(f"SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{TARGET_DB}';").strip() != '0':
        raise RuntimeError('Target schema already exists')
    mysql(f"CREATE DATABASE `{TARGET_DB}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER 'blog_v3'@'%' IDENTIFIED BY '{password}'; GRANT ALL ON `{TARGET_DB}`.* TO 'blog_v3'@'%';")
    (ROOT / 'production.env').write_text('\n'.join([
        f'DATABASE_URL=jdbc:mysql://mysql:3306/{TARGET_DB}?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC',
        'DATABASE_USERNAME=blog_v3', f'DATABASE_PASSWORD={password}',
        'BLOG_ADMIN_USERNAME=admin', 'BLOG_CORS_ALLOWED_ORIGINS=https://www.yigalaxy.xin,https://yigalaxy.xin',
        'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=4', 'SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=1',
        'SERVER_TOMCAT_THREADS_MAX=40', 'SERVER_TOMCAT_THREADS_MIN_SPARE=2', '',
    ]))
    (ROOT / 'production.env').chmod(0o600)
    (ROOT / '.env').write_text('RELEASE_TAG=' + release.name + '\n')
    (ROOT / 'uploads').mkdir(mode=0o755)
    os.chown(ROOT / 'uploads', 10001, 10001)
    print('Backup and production configuration prepared; containers start during cutover.', flush=True)


def migrate(release):
    # This target is dedicated to this migration. The source is never modified.
    marker = ROOT / 'cutover-complete'
    if marker.exists():
        raise RuntimeError('Production has already switched; refusing migration overwrite')
    source_uploads = next(Path(m['Source']) for m in inspect('yiguixingtu-prod-backend')['Mounts'] if m['Destination'] == '/app/uploads')
    mapping = {}
    supported = {'.jpg', '.jpeg', '.png', '.gif', '.webp', '.pdf', '.txt', '.md', '.zip', '.7z', '.rar', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx', '.csv'}
    shutil.copytree(source_uploads, ROOT / 'legacy-uploads', dirs_exist_ok=True)
    for file in source_uploads.rglob('*'):
        if not file.is_file() or file.suffix.lower() not in supported:
            continue
        relative = file.relative_to(source_uploads).as_posix()
        name = 'legacy-' + hashlib.sha256(relative.encode()).hexdigest()[:32] + file.suffix.lower()
        target = ROOT / 'uploads' / name
        shutil.copy2(file, target)
        os.chown(target, 10001, 10001)
        target.chmod(0o644)
        mapping[relative] = f'{PUBLIC}/api/v1/media/{name}'

    statements = [f'USE `{TARGET_DB}`', "SET time_zone = '+00:00'", 'START TRANSACTION']
    for table in ['article_attachment', 'article_tag', 'comment', 'visit_log', 'article', 'category', 'tag', 'project', 'favorite', 'friend_link', 'admin_session', 'admin_user']:
        statements.append(f'DELETE FROM `{table}`')
    src = f'`{SOURCE_DB}`'
    statements += [
        f'INSERT INTO category (id,name,description,sort,created_at,updated_at) SELECT id,name,description,sort,create_time,update_time FROM {src}.category WHERE deleted=0',
        f'INSERT INTO tag (id,name,sort,created_at) SELECT id,name,sort,create_time FROM {src}.tag',
        f'INSERT INTO article (id,title,summary,content,cover,category_id,status,view_count,is_top,created_at,updated_at,deleted) SELECT id,title,summary,content,cover,category_id,status,view_count,is_top,create_time,update_time,deleted FROM {src}.article',
        f'INSERT INTO article_tag (article_id,tag_id) SELECT article_id,tag_id FROM {src}.article_tag',
        f'INSERT INTO article_attachment (id,article_id,name,url,size,sort) SELECT id,article_id,name,url,size,0 FROM {src}.article_attachment',
        f"INSERT INTO comment (id,article_id,nickname,email,content,status,created_at) SELECT c.id,c.article_id,COALESCE(NULLIF(c.nickname,''),NULLIF(u.nickname,''),u.username,'游客'),c.email,c.content,c.status,c.create_time FROM {src}.comment c LEFT JOIN {src}.user u ON u.id=c.user_id WHERE c.deleted=0",
        f'INSERT INTO project (id,title,description,primary_url,secondary_url,metadata,sort,cover) SELECT id,name,description,url,repo,tech,sort,cover FROM {src}.project WHERE deleted=0 AND status=1',
        f'INSERT INTO favorite (id,title,description,primary_url,secondary_url,sort) SELECT id,title,description,url,category,sort FROM {src}.favorite WHERE deleted=0 AND status=1',
        f'INSERT INTO friend_link (id,title,description,primary_url,secondary_url,sort) SELECT id,name,description,url,avatar,sort FROM {src}.friend_link WHERE deleted=0 AND status=1',
        f'UPDATE about_page n JOIN {src}.about o ON o.id=1 SET n.content=COALESCE(o.bio,\'\'),n.nickname=o.nickname,n.avatar=o.avatar,n.email=o.email,n.github=o.github,n.wechat=o.wechat,n.qq=o.qq,n.updated_at=o.update_time WHERE n.id=1',
        f'UPDATE site_setting n JOIN {src}.site_setting o ON o.id=1 SET n.site_name=o.site_name,n.announcement=o.announcement,n.comment_enabled=o.comment_enabled,n.page_size=o.page_size,n.notice_text=o.notice_text,n.copyright=o.copyright,n.icp_number=o.icp_number,n.police_number=o.police_number,n.updated_at=o.update_time WHERE n.id=1',
        f"INSERT INTO admin_user (username,password_hash,created_at,updated_at) SELECT username,password,create_time,update_time FROM {src}.user WHERE username='admin' AND role='ADMIN' AND status=1 AND deleted=0",
        f"INSERT INTO visit_log (id,path,article_id,ip,visitor_type,referer_type,device,visited_at) SELECT id,path,article_id,ip,IF(visitor_type='ADMIN','ADMIN','GUEST'),referer_type,device,create_time FROM {src}.visit_log WHERE create_time>=UTC_TIMESTAMP()-INTERVAL 90 DAY AND ip IS NOT NULL AND LENGTH(ip)<=45 AND path REGEXP '^/(articles|article/[1-9][0-9]*|projects|favorites|links|about)?$'",
    ]
    fields = {'article': ['content', 'cover'], 'article_attachment': ['url'], 'project': ['cover', 'primary_url', 'secondary_url', 'metadata'], 'favorite': ['primary_url', 'description'], 'friend_link': ['secondary_url'], 'about_page': ['content', 'avatar'], 'site_setting': ['announcement', 'notice_text']}
    for relative, target in mapping.items():
        # Replace absolute references first, then root-relative variants.
        for prefix in [PUBLIC + '/api/uploads/', 'http://www.yigalaxy.xin/api/uploads/', 'https://yigalaxy.xin/api/uploads/', '/api/uploads/', PUBLIC + '/uploads/', '/uploads/']:
            for table, columns in fields.items():
                assignments = ','.join(f'`{column}`=REPLACE(`{column}`,{literal(prefix + relative)},{literal(target)})' for column in columns)
                statements.append(f'UPDATE `{table}` SET {assignments}')
    # Media rewriting must not change the original editorial timestamps.
    statements += [
        f'UPDATE article n JOIN {src}.article o ON o.id=n.id SET n.updated_at=o.update_time',
        f'UPDATE about_page n JOIN {src}.about o ON o.id=n.id SET n.updated_at=o.update_time',
        f'UPDATE site_setting n JOIN {src}.site_setting o ON o.id=n.id SET n.updated_at=o.update_time',
    ]
    statements.append('COMMIT')
    mysql(';\n'.join(statements) + ';')
    # Preserve source auto-increment high water marks, including deleted records.
    for table in ['article', 'category', 'tag', 'comment', 'project', 'favorite', 'friend_link', 'article_attachment', 'visit_log']:
        watermark = mysql(f"SELECT AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA='{SOURCE_DB}' AND TABLE_NAME='{table}';").strip()
        if watermark.isdigit():
            mysql(f'ALTER TABLE `{TARGET_DB}`.`{table}` AUTO_INCREMENT={watermark};')
    (release / 'media-map.json').write_text(json.dumps(mapping, indent=2))
    verify(release)
    print('Migration complete; source database untouched.', flush=True)


def verify(release):
    counts = {}
    conditions = {'article': '1=1', 'article_attachment': '1=1', 'article_tag': '1=1', 'tag': '1=1', 'category': 'deleted=0', 'comment': 'deleted=0', 'project': 'deleted=0 AND status=1', 'favorite': 'deleted=0 AND status=1', 'friend_link': 'deleted=0 AND status=1'}
    for table, where in conditions.items():
        expected, actual = mysql(f'SELECT (SELECT COUNT(*) FROM `{SOURCE_DB}`.`{table}` WHERE {where}),(SELECT COUNT(*) FROM `{TARGET_DB}`.`{table}`);').strip().split('\t')
        if expected != actual:
            raise RuntimeError('Migration count mismatch: ' + table)
        counts[table] = int(actual)
    if mysql(f"SELECT COUNT(*) FROM `{TARGET_DB}`.admin_user WHERE username='admin';").strip() != '1':
        raise RuntimeError('Original administrator missing')
    if mysql(f'SELECT COUNT(*) FROM `{TARGET_DB}`.article n JOIN `{SOURCE_DB}`.article o ON o.id=n.id WHERE n.title<>o.title OR n.status<>o.status OR n.deleted<>o.deleted OR n.created_at<>o.create_time OR n.view_count<>o.view_count;').strip() != '0':
        raise RuntimeError('Article identity or state mismatch')
    if mysql(f"SELECT COUNT(*) FROM `{TARGET_DB}`.admin_user n JOIN `{SOURCE_DB}`.user o ON o.username=n.username WHERE n.password_hash=o.password AND o.role='ADMIN' AND o.deleted=0;").strip() != '1':
        raise RuntimeError('Original administrator password mismatch')
    # Validate complete bodies, allowing only the planned media URL substitutions.
    media_map_file = release / 'media-map.json'
    mapping = json.loads(media_map_file.read_text()) if media_map_file.exists() else {}
    for row in rows(f"SELECT JSON_OBJECT('id',n.id,'content',n.content,'original',o.content,'cover',n.cover,'deleted',n.deleted) FROM `{TARGET_DB}`.article n JOIN `{SOURCE_DB}`.article o ON o.id=n.id;"):
        expected = row['original']
        for relative, target in mapping.items():
            for prefix in [PUBLIC + '/api/uploads/', 'http://www.yigalaxy.xin/api/uploads/', 'https://yigalaxy.xin/api/uploads/', '/api/uploads/', PUBLIC + '/uploads/', '/uploads/']:
                expected = expected.replace(prefix + relative, target)
        if row['content'] != expected:
            raise RuntimeError('Full article body mismatch: ' + str(row['id']))
        if not row['deleted'] and re.search(r'(?:https?://(?:www\.)?yigalaxy\.xin)?/(?:api/)?uploads/', row['content'] + (row['cover'] or '')):
            raise RuntimeError('Unmapped legacy article media: ' + str(row['id']))
    counts['published'] = int(mysql(f'SELECT COUNT(*) FROM `{TARGET_DB}`.article WHERE status=1 AND deleted=0;'))
    counts['drafts'] = int(mysql(f'SELECT COUNT(*) FROM `{TARGET_DB}`.article WHERE status=0 AND deleted=0;'))
    counts['visits'] = int(mysql(f'SELECT COUNT(*) FROM `{TARGET_DB}`.visit_log;'))
    (release / 'migration-counts.json').write_text(json.dumps(counts, indent=2))
    print(json.dumps(counts), flush=True)


def switch(release):
    original = NGINX.read_text()
    candidate = original.replace('http://127.0.0.1:3000', 'http://127.0.0.1:3002')
    start = candidate.index('    location /api/ {')
    end = candidate.index('\n    }', start) + len('\n    }')
    candidate = candidate[:start] + '''    location /api/v1/ {
        proxy_pass http://127.0.0.1:8084;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        limit_req zone=api burst=40 nodelay;
        limit_req_status 429;
    }

    location /api/ { return 404; }
    location = /feed.xml { return 301 /rss.xml; }
    location = /archive { return 301 /articles; }
    location = /music { return 301 /; }
    location ~ ^/(swagger-ui(?:/|\\.html)|v3/api-docs) {
        proxy_pass http://127.0.0.1:8084;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
    }''' + candidate[end:]
    start = candidate.index('    location /api/uploads/ {')
    end = candidate.index('\n    }', start) + len('\n    }')
    candidate = candidate[:start] + '''    location /api/uploads/ {
        alias /srv/yiguixingtu-blog/legacy-uploads/;
        add_header X-Content-Type-Options nosniff always;
        expires 30d;
        access_log off;
    }''' + candidate[end:]
    candidate = candidate.replace('location = /api/auth/login', 'location = /api/v1/auth/login').replace('http://127.0.0.1:8082/auth/login', 'http://127.0.0.1:8084/api/v1/auth/login')
    candidate = candidate.replace('$proxy_add_x_forwarded_for', '$remote_addr')
    # Do not expose a traversable private deployment directory to nginx.
    ROOT.chmod(0o711)
    (release / 'nginx-candidate.conf').write_text(candidate)
    NGINX.write_text(candidate)
    try:
        run(['nginx', '-t'])
        run(['systemctl', 'reload', 'nginx'])
        body = wait(PUBLIC + '/api/v1/articles?size=6')
        if json.loads(body)['total'] != json.loads((release / 'migration-counts.json').read_text())['published']:
            raise RuntimeError('Public article count mismatch')
        for path in ['/', '/articles', '/article/37', '/about', '/projects', '/favorites', '/links', '/login', '/sitemap.xml', '/rss.xml', '/swagger-ui.html']:
            wait(PUBLIC + path)
    except Exception:
        NGINX.write_text(original)
        run(['nginx', '-t'])
        compose('stop')
        run(['docker', 'start', 'yiguixingtu-prod-backend', 'yiguixingtu-prod-frontend'])
        run(['systemctl', 'reload', 'nginx'])
        raise
    (ROOT / 'cutover-complete').write_text(release.name)
    (release / 'deploy-complete').write_text(time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()))
    print('Public routing switched; HTTPS page checks passed.', flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['prepare', 'migrate', 'verify', 'cutover'])
    parser.add_argument('release', type=Path)
    args = parser.parse_args()
    release = args.release.resolve()
    if release.parent != ROOT / 'releases':
        raise RuntimeError('Unexpected release path')
    os.umask(0o077)
    if args.action == 'prepare':
        prepare(release)
    elif args.action == 'migrate':
        migrate(release)
    elif args.action == 'verify':
        verify(release)
    else:
        if (ROOT / 'cutover-complete').exists():
            raise RuntimeError('Production has already switched; refusing a second cutover')
        # Also recover safely from an interrupted preparation or host restart.
        compose('stop')
        # Freeze writes and release the old JVM's memory before starting the new one.
        run(['docker', 'stop', 'yiguixingtu-prod-backend'])
        try:
            snapshot(release / 'backup-cutover')
            # The small host must not run both Java backends during startup.
            print(compose('up', '-d', '--no-build', 'api'), flush=True)
            wait('http://127.0.0.1:8084/api/v1/site-settings')
            migrate(release)
            print(compose('up', '-d', '--no-build', '--no-deps', 'web'), flush=True)
            wait('http://127.0.0.1:3002/')
            switch(release)
        except Exception:
            compose('stop')
            run(['docker', 'start', 'yiguixingtu-prod-backend', 'yiguixingtu-prod-frontend'])
            raise
        run(['docker', 'stop', 'yiguixingtu-prod-frontend'])


if __name__ == '__main__':
    main()
