// Build the static page into dist/ (AWAI-3410). tatara-core is copied from
// packages/tatara-core, not edited: the device runs the same verify-run.
import { build } from 'esbuild';
import { createHash } from 'node:crypto';
import { cpSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(root, 'dist');
const core = join(root, '../../packages/tatara-core/src/tatara/core');
rmSync(dist, { recursive: true, force: true });
mkdirSync(join(dist, 'cljs/tatara/core'), { recursive: true });
mkdirSync(join(dist, 'cljs/tatara/app'), { recursive: true });

await build({ entryPoints: [join(root, 'src/crypto.js')], bundle: true, format: 'iife',
              target: 'es2020', outfile: join(dist, 'crypto.js'), legalComments: 'none' });
cpSync(join(root, 'node_modules/scittle/dist/scittle.js'), join(dist, 'scittle.js'));
for (const f of ['canonical', 'lease', 'verdict', 'verify'])
  cpSync(join(core, `${f}.cljc`), join(dist, `cljs/tatara/core/${f}.cljc`));
for (const f of ['verify', 'ui'])
  cpSync(join(root, `src/tatara/app/${f}.cljs`), join(dist, `cljs/tatara/app/${f}.cljs`));
for (const f of ['index.html', 'style.css']) cpSync(join(root, 'src', f), join(dist, f));
cpSync(join(root, 'test/fixtures/run-bundle.edn'), join(dist, 'fixture/run-bundle.edn'));

const files = [];
(function walk(d) { for (const n of readdirSync(d)) { const p = join(d, n);
  statSync(p).isDirectory() ? walk(p) : files.push(p); } })(dist);
const manifest = Object.fromEntries(files.sort().map(p =>
  [relative(dist, p), createHash('sha256').update(readFileSync(p)).digest('hex')]));
writeFileSync(join(dist, 'MANIFEST.json'), JSON.stringify(manifest, null, 2) + '\n');
console.log(`dist: ${files.length} files`);
