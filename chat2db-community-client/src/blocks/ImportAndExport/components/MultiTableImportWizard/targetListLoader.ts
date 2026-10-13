import type { MultiTableTarget } from './model';

/** Keeps successful schema lookups across an explicit retry of failed lookups. */
export const createTargetListLoader = (
  discover: () => Promise<Array<string | undefined>>,
  tables: (schema?: string) => Promise<MultiTableTarget[]>,
) => {
  let schemas: Array<string | undefined> | undefined;
  const successful = new Map<string | undefined, MultiTableTarget[]>();
  let pending: Promise<{ targets: MultiTableTarget[]; errors: unknown[] }> | undefined;
  return {
    load: () => {
      if (pending) return pending;
      pending = (async () => {
        const errors: unknown[] = [];
        try {
          schemas ??= await discover();
          await Promise.all(
            schemas.map(async (schema) => {
              if (successful.has(schema)) return;
              try {
                successful.set(schema, await tables(schema));
              } catch (error) {
                errors.push(error);
              }
            }),
          );
        } catch (error) {
          errors.push(error);
        }
        return { targets: Array.from(successful.values()).flat(), errors };
      })().finally(() => {
        pending = undefined;
      });
      return pending;
    },
  };
};
