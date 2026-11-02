import pg from 'pg';

export type Db = pg.Pool;
export type DbClient = pg.PoolClient | pg.Pool;

const { builtins } = pg.types;

/**
 * Per-pool type parsers (no global mutation):
 * - `date` stays a `YYYY-MM-DD` string (pg would otherwise build a local-time JS Date and shift it by the TZ);
 * - `int8` becomes a JS number, refusing values outside the safe-integer range rather than losing precision.
 */
const typeOverrides = {
  getTypeParser(oid: number, format?: 'text' | 'binary') {
    if (oid === builtins.DATE) return (v: string) => v;
    if (oid === builtins.INT8) {
      return (v: string) => {
        const n = Number(v);
        if (!Number.isSafeInteger(n)) throw new Error(`int8 value ${v} exceeds the safe integer range`);
        return n;
      };
    }
    return pg.types.getTypeParser(oid, format);
  },
} as pg.CustomTypesConfig;

export interface PoolOptions {
  /** Postgres schema to use as search_path (tests use one schema per test file). */
  schema?: string;
  max?: number;
}

export function createPool(connectionString: string, opts: PoolOptions = {}): Db {
  const config: pg.PoolConfig = { connectionString, types: typeOverrides, max: opts.max ?? 10 };
  if (opts.schema) {
    if (!/^[a-z_][a-z0-9_]*$/.test(opts.schema)) throw new Error(`invalid schema name ${opts.schema}`);
    config.options = `-c search_path=${opts.schema}`;
  }
  return new pg.Pool(config);
}

export function requireDatabaseUrl(): string {
  const url = process.env.DATABASE_URL;
  if (!url) throw new Error('DATABASE_URL is not set');
  return url;
}

/** Run `fn` inside a transaction on a dedicated client; rolls back on any error. */
export async function withTransaction<T>(db: Db, fn: (client: pg.PoolClient) => Promise<T>): Promise<T> {
  const client = await db.connect();
  try {
    await client.query('BEGIN');
    const result = await fn(client);
    await client.query('COMMIT');
    return result;
  } catch (err) {
    await client.query('ROLLBACK').catch(() => undefined);
    throw err;
  } finally {
    client.release();
  }
}
