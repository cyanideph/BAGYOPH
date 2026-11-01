#!/usr/bin/env node
// Find every package-boundary group whose module graph reaches a given file.
// Used to keep scripts/package-boundaries.json honest when a layer is added.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';

const root = path.resolve(process.argv[2] || '.');
const targets = process.argv.slice(3);
const groups = JSON.parse(
  readFileSync(path.join(root, 'scripts/package-boundaries.json'), 'utf8'),
);

// Static import graph over the repository (import ... from '...', export ... from).
const graph = new Map();
const files = [];
(function walk(dir) {
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules' || entry === '.git' || entry === 'dist')
      continue;
    const full = path.join(dir, entry);
    const st = statSync(full);
    if (st.isDirectory()) walk(full);
    else if (/\.[mc]?js$/.test(entry)) files.push(full);
  }
})(root);

const resolveSpec = (from, spec) => {
  if (!spec.startsWith('.')) return null;
  const base = path.resolve(path.dirname(from), spec);
  for (const candidate of [base, `${base}.js`, `${base}.mjs`,
    path.join(base, 'index.js'), path.join(base, 'index.mjs')]) {
    try {
      if (statSync(candidate).isFile()) return candidate;
    } catch {
      /* keep looking */
    }
  }
  return null;
};

for (const file of files) {
  const text = readFileSync(file, 'utf8');
  const deps = new Set();
  for (const match of text.matchAll(
    /(?:import|export)[^'"]*from\s*['"]([^'"]+)['"]/g,
  )) {
    const resolved = resolveSpec(file, match[1]);
    if (resolved) deps.add(resolved);
  }
  graph.set(file, [...deps]);
}

const reachers = new Set();
const seen = new Set();
const stack = [...targets.map((t) => path.resolve(root, t))];
while (stack.length) {
  const file = stack.pop();
  if (seen.has(file)) continue;
  seen.add(file);
  for (const [parent, deps] of graph) {
    if (deps.includes(file) && !seen.has(parent)) {
      reachers.add(parent);
      stack.push(parent);
    }
  }
}

for (const [name, group] of Object.entries(groups)) {
  const owned = group.modules.some((module) => reachers.has(path.resolve(root, module)));
  if (owned) console.log(name);
}
