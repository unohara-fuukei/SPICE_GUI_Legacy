// Paraglide compile with a serialized (bounded) write-out.
//
// Upstream's `paraglide-js compile` writes the generated modules through
// `Promise.allSettled` with no concurrency limit. On this host the open-file
// ulimit is 2048; with ~4.4k message modules the concurrent `writeFile` calls
// exhaust the descriptor table, and the rejected writes are swallowed by
// `allSettled` — roughly half the modules (plus `messages/_index.js` and
// `messages/package.json`) silently never reach disk, which then breaks
// resolution for any code path that imports a missing message.
//
// This script reuses the compiler's own `compileProject` to build the full
// output map, then writes every file sequentially so no descriptor pool is
// exhausted. The generated content is identical to the CLI output; only the
// write-out strategy differs.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const require = createRequire(import.meta.url);

// Resolve the paraglide entry through its pnpm-isolated layout, then locate
// the bundled @inlang/sdk sibling directory used to load the project.
// paraMain: <pkg>/node_modules/@inlang/paraglide-js/dist/index.js → walk up 4
// dirs to <pkg>/node_modules/, where the @inlang/sdk sibling link lives.
const paraMain = require.resolve('@inlang/paraglide-js');
const paraNodeModules = path.dirname(path.dirname(path.dirname(path.dirname(paraMain))));
const sdkEntry = path.join(paraNodeModules, '@inlang/sdk/dist/index.js');

const { loadProjectFromDirectory } = await import(pathToFileURL(sdkEntry));
const { compileProject } = await import('@inlang/paraglide-js');

const projectPath = './project.inlang';
const outdir = './src/lib/paraglide';

const project = await loadProjectFromDirectory({ path: projectPath, fs });

const output = await compileProject({
    compilerOptions: {
        // Match the CLI strategy passed in package.json.
        strategy: ['localStorage', 'cookie', 'globalVariable', 'baseLocale'],
    },
    project,
    projectPath,
});

fs.rmSync(outdir, { recursive: true, force: true });

let written = 0;
for (const [file, content] of Object.entries(output)) {
    const full = path.join(outdir, file);
    fs.mkdirSync(path.dirname(full), { recursive: true });
    fs.writeFileSync(full, content);
    written += 1;
}

await project.close();
console.log(`✅ [paraglide-safe] wrote ${written} generated files to ${outdir}`);
