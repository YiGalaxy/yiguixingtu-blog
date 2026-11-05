"""Materialize Nuxt's Windows junctions for a portable Linux runtime artifact."""
from pathlib import Path
import shutil

root = Path(__file__).resolve().parent.parent
source = root / 'frontend' / '.output'
target = root / 'output' / 'release' / 'web-runtime'
if not (source / 'server' / 'index.mjs').is_file():
    raise RuntimeError('Run the frontend production build first')
if target.exists():
    shutil.rmtree(target)
shutil.copytree(source, target, symlinks=False)
print('Portable frontend runtime staged: ' + str(target))
