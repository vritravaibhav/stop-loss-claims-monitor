import type { ErrorRequestHandler } from 'express';

/** An error with an HTTP status whose message is safe to show the client (`{ "error": message }`). */
export class HttpError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = 'HttpError';
  }
}

/** Positive integer id from a path segment / query value, else null (callers decide 400 vs 404). */
export function parseId(value: unknown): number | null {
  if (typeof value !== 'string' || !/^[1-9]\d{0,14}$/.test(value)) return null;
  return Number(value);
}

export const errorHandler: ErrorRequestHandler = (err, _req, res, next) => {
  if (res.headersSent) return next(err);
  if (err instanceof HttpError) {
    res.status(err.status).json({ error: err.message });
    return;
  }
  // body-parser errors carry `type` and a 4xx `status`.
  const e = err as { type?: string; status?: number };
  if (e.type === 'entity.parse.failed') {
    res.status(400).json({ error: 'Malformed JSON body' });
    return;
  }
  if (e.type === 'entity.too.large') {
    res.status(413).json({ error: 'Request body too large' });
    return;
  }
  console.error('unhandled error', err);
  res.status(500).json({ error: 'Internal server error' });
};
