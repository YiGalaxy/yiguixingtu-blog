"""Read deployment metadata and schema without printing credentials or content."""
import json
import re
import subprocess


def inspect(name):
    return json.loads(subprocess.check_output(['docker', 'inspect', name]))[0]


mysql = inspect('yiguixingtu-prod-mysql')
backend = inspect('yiguixingtu-prod-backend')
env = dict(item.split('=', 1) for item in mysql['Config']['Env'] if '=' in item)
backend_env = dict(item.split('=', 1) for item in backend['Config']['Env'] if '=' in item)


def query(sql):
    result = subprocess.run(
        ['docker', 'exec', '-i', '-e', 'MYSQL_PWD=' + env['MYSQL_ROOT_PASSWORD'],
         mysql['Name'].lstrip('/'), 'mysql', '-uroot', '--default-character-set=utf8mb4', '-N'],
        input=sql, text=True, capture_output=True, check=True)
    return result.stdout


print('Database names:', query('SHOW DATABASES;'))
for key, value in backend_env.items():
    if key in ['SPRING_DATASOURCE_URL', 'SERVER_PORT', 'TZ']:
        print(key, value)
databases = query('SHOW DATABASES;').splitlines()
for database in databases:
    if database in ['information_schema', 'mysql', 'performance_schema', 'sys']:
        continue
    assert re.fullmatch(r'[A-Za-z0-9_]+', database)
    print('Schema:', database)
    print(query(f"SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='{database}' ORDER BY TABLE_NAME,ORDINAL_POSITION;"))
    for table in query(f'SHOW TABLES FROM `{database}`;').splitlines():
        assert re.fullmatch(r'[A-Za-z0-9_]+', table)
        print(table, query(f'SELECT COUNT(*) FROM `{database}`.`{table}`;').strip())
    if database == 'yiguixingtu':
        for statement in [
            'SELECT status,deleted,COUNT(*) FROM article GROUP BY status,deleted',
            'SELECT status,deleted,COUNT(*) FROM comment GROUP BY status,deleted',
            'SELECT status,deleted,COUNT(*) FROM project GROUP BY status,deleted',
            "SELECT username,role,status,deleted,LEFT(password,4) FROM user",
            'SELECT visitor_type,referer_type,device,COUNT(*) FROM visit_log GROUP BY visitor_type,referer_type,device',
            'SELECT @@global.time_zone,@@session.time_zone,NOW(),UTC_TIMESTAMP(),MIN(create_time),MAX(create_time) FROM visit_log',
            "SELECT DISTINCT SUBSTRING(cover,1,80) FROM article WHERE cover <> ''",
        ]:
            print(statement, query(f'USE `{database}`; {statement};'))
