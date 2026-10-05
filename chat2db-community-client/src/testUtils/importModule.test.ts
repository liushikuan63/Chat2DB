/** Resolution probe: the helper must read named exports from either module shape. */
import assert from 'node:assert/strict';

import { importModule } from './importModule';

type ShortcutModule = {
  ShortcutAction: Record<string, string>;
  DEFAULT_SHORTCUT_CONFIG: Record<string, { action: string; canModify: boolean }>;
};

async function main() {
  const globalObj = globalThis as unknown as Record<string, unknown>;
  globalObj.__RUNTIME_ENV__ = 'community';
  globalObj.__ENV__ = 'test';
  globalObj.window = {};

  const esm = await importModule<ShortcutModule>(() => import('../constants/shortcut'));
  assert.equal(typeof esm.ShortcutAction, 'object', 'named exports must be readable');
  assert.equal(
    esm.DEFAULT_SHORTCUT_CONFIG[esm.ShortcutAction.SqlToggleLineComment].action,
    esm.ShortcutAction.SqlToggleLineComment,
  );

  // The shape tsx produces for a transpiled .ts module: no hoisted named exports, the real
  // exports live on `default`.
  const commonjsShape = { default: { answer: 42 }, 'module.exports': {} };
  const cjs = await importModule<{ answer: number }>(() => Promise.resolve(commonjsShape));
  assert.equal(cjs.answer, 42, 'a CommonJS-shaped namespace must resolve through default');

  console.log('importModule tests passed');
}

main();