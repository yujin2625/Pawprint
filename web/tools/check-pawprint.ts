// Reads .pawprint files made by the mod and writes them back, to check the web reader against real data.
// Run: npx vite-node tools/check-pawprint.ts <file.pawprint>...
import { readFileSync } from 'node:fs';
import { readPawprint, writePawprint } from '../src/core/format/pawprint';

for (const path of process.argv.slice(2)) {
  const started = Date.now();
  const bp = readPawprint(new Uint8Array(readFileSync(path)));
  const readMs = Date.now() - started;
  const again = readPawprint(writePawprint(bp));
  const same =
    again.palette.join() === bp.palette.join() &&
    again.positions.every((v, i) => v === bp.positions[i]) &&
    again.states.every((v, i) => v === bp.states[i]) &&
    again.removals.length === bp.removals.length;
  console.log(path.split(/[\\/]/).pop(), `format ${bp.meta.format}`, `size ${bp.meta.size.join('x')}`, `blocks ${bp.states.length}`,
    `removals ${bp.removals.length / 3}`, `palette ${bp.palette.length}`, `read ${readMs}ms`, same ? 'round-trip OK' : 'ROUND-TRIP DIFFERS');
  console.log('  e.g.', bp.palette.slice(0, 4).join('  '));
}
