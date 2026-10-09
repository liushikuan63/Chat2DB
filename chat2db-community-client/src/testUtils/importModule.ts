/**
 * Resolves the named exports of a dynamically imported module.
 *
 * `tsx` transpiles the project `.ts` sources to CommonJS under this repo's `tsconfig` (no
 * `"type": "module"`), so `await import('./mod')` resolves to a namespace whose named exports are
 * not hoisted: they live on the `default` export, which is the `module.exports` object. Reading
 * `namespace.ShortcutAction` therefore yields `undefined` even though the export exists, and the
 * assertion below would fail on an unrelated toolchain detail rather than on the code under test.
 *
 * The helper tries the ESM shape first and falls back to the CommonJS shape, so the same test works
 * under either module system.
 */
export async function importModule<T extends object>(loader: () => Promise<unknown>): Promise<T> {
  const namespace = (await loader()) as Record<string, unknown> & { default?: T };
  const commonjs = namespace.default as Record<string, unknown> | undefined;
  const named = Object.keys(namespace).some(key => key !== 'default' && key !== 'module.exports');
  return (named ? namespace : commonjs) as T;
}
