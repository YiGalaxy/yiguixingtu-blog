"""Export locally built production images without shell binary redirection."""
import gzip
import hashlib
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parent.parent
release = root / 'output' / 'release' / 'v3.1.0-20261105'
release.mkdir(parents=True, exist_ok=True)
images = ['yiguixingtu-blog-web:v3.1.0-20261105', 'yiguixingtu-blog-api:v3.1.0-20261105']
process = subprocess.Popen(['docker', 'save', *images], stdout=subprocess.PIPE)
with gzip.open(release / 'images.tar.gz', 'wb', compresslevel=3) as archive:
    shutil.copyfileobj(process.stdout, archive)
if process.wait() != 0:
    raise RuntimeError('Docker image export failed')
for name in ['compose.yaml', 'production.py']:
    shutil.copy2(root / 'deploy' / name, release / name)
files = [release / name for name in ['images.tar.gz', 'compose.yaml', 'production.py']]
(release / 'SHA256SUMS').write_text(''.join(hashlib.file_digest(file.open('rb'), 'sha256').hexdigest() + '  ' + file.name + '\n' for file in files))
(release / 'revision.txt').write_bytes(subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root))
print(release)
