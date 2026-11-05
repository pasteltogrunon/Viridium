// Dumps reference values from the JS prototype (ViridiumExtras/worldgen/js/worldgen.js) for the Java parity test.
//
//   node tools/worldgen/parity_dump.js <path/to/worldgen.js> [outDir]
//
// Writes noise.csv (primitives) and points_<seed>.csv (pipeline up to geology) into outDir
// (default: src/test/resources/worldgen).
'use strict';
const fs = require('fs');
const path = require('path');

const src = process.argv[2];
if (!src) {
  console.error('usage: node parity_dump.js <worldgen.js> [outDir]');
  process.exit(1);
}
const outDir = process.argv[3] || path.join(__dirname, '..', '..', 'src', 'test', 'resources', 'worldgen');
require(path.resolve(src));
const W = globalThis.WorldGen;
fs.mkdirSync(outDir, { recursive: true });

// deterministic sampler (independent of Math.random)
let st = 0x9e3779b9;
const rand = () => { st = (Math.imul(st, 1664525) + 1013904223) >>> 0; return st / 4294967296; };
const num = (v) => (Number.isInteger(v) ? String(v) : Number(v.toPrecision(10)).toString());

// ---------------------------------------------------------------- primitives
{
  const rows = ['kind,x,y,s,oct,value'];
  for (let i = 0; i < 400; i++) {
    const x = (rand() - 0.5) * 2e5, y = (rand() - 0.5) * 2e5, s = Math.floor(rand() * 0x3fffffff);
    const xi = Math.floor(x), yi = Math.floor(y);
    rows.push(`hash,${xi},${yi},${s},0,${W.hash(xi, yi, s)}`);
    rows.push(`perlin,${x / 97},${y / 97},${s},0,${W.perlin(x / 97, y / 97, s)}`);
    const oct = 1 + (i % 5);
    rows.push(`fbm,${x / 311},${y / 311},${s},${oct},${W.fbm(x / 311, y / 311, s, oct)}`);
  }
  fs.writeFileSync(path.join(outDir, 'noise.csv'), rows.join('\n') + '\n');
}

// ---------------------------------------------------------------- pipeline
const FIELDS = ['Tb', 'Pb', 'lat', 'pole', 'windX', 'windY', 'pi', 'pj', 'edge', 'edgeNC', 'conv', 'ridge', 'orog',
  'trench', 'arc', 'collZ', 'collK', 'collS', 'L', 'h0', 'mtnMask', 'volc', 'volcType', 'collR', 'rock', 'rock2',
  'rockT', 'erod'];

for (const seed of [12345, 987654321]) {
  W.setParams({ seed });
  const o = W.newPoint();
  const rows = ['x,z,' + FIELDS.join(',')];
  const sample = (x, y) => {
    W.evaluate(x, y, W.STAGE.ROCK, 1, o);
    rows.push(`${x},${y},` + FIELDS.map((f) => num(o[f])).join(','));
  };
  const R = 60000;
  for (let i = 0; i < 1000; i++) sample(Math.round((rand() - 0.5) * 2 * R), Math.round((rand() - 0.5) * 2 * R));
  // dense patches (coast, mountains): contiguous columns like real chunks
  for (let p = 0; p < 6; p++) {
    const x0 = Math.round((rand() - 0.5) * 2 * R), y0 = Math.round((rand() - 0.5) * 2 * R);
    for (let k = 0; k < 64; k++) sample(x0 + (k % 8) * 37, y0 + Math.floor(k / 8) * 37);
  }
  // volcanic features: sample around resolved hotspots
  let hs = 0;
  for (let i = 0; i < 400 && hs < 300; i++) {
    const x = (rand() - 0.5) * 2 * R, y = (rand() - 0.5) * 2 * R;
    for (const v of W.hotspotsNear(x, y)) {
      for (let k = 0; k < 6; k++, hs++) {
        const r = rand() * (v.type === 1 ? 900 : 1.2 * v.R), a = rand() * Math.PI * 2;
        const ox = v.type === 1 ? v.ux * rand() * v.N * v.sp : 0, oy = v.type === 1 ? v.uy * rand() * v.N * v.sp : 0;
        sample(Math.round(v.x + ox + Math.cos(a) * r), Math.round(v.y + oy + Math.sin(a) * r));
      }
    }
  }
  fs.writeFileSync(path.join(outDir, `points_${seed}.csv`), rows.join('\n') + '\n');
  console.log(`seed ${seed}: ${rows.length - 1} points`);
}
