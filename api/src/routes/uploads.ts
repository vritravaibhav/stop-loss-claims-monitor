import { Router, type RequestHandler } from 'express';
import multer from 'multer';
import { currentUser, requireAnalyst } from '../auth.js';
import type { Db } from '../db.js';
import { HttpError, parseId } from '../http.js';
import { ingestClaimFile } from '../services/ingest.js';
import { getUpload, listUploads } from '../services/uploads.js';

/** Largest claim file accepted (Cloud Run caps request bodies at 32 MiB). */
export const MAX_UPLOAD_BYTES = 25 * 1024 * 1024;

const parser = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: MAX_UPLOAD_BYTES, files: 1, fields: 5, parts: 6 },
  defParamCharset: 'utf8',
}).single('file');

/** multer, with its errors mapped to `{ error }` responses. */
const singleFile: RequestHandler = (req, res, next) => {
  parser(req, res, (err: unknown) => {
    if (!err) return next();
    if (err instanceof multer.MulterError) {
      if (err.code === 'LIMIT_FILE_SIZE') {
        return next(new HttpError(413, `File is larger than ${MAX_UPLOAD_BYTES / 1024 / 1024} MB`));
      }
      if (err.code === 'LIMIT_UNEXPECTED_FILE') {
        return next(new HttpError(400, 'Send exactly one file, in the multipart field "file"'));
      }
      return next(new HttpError(400, `Invalid upload: ${err.message}`));
    }
    return next(new HttpError(400, 'Malformed multipart body'));
  });
};

export function uploadRoutes(db: Db): Router {
  const router = Router();

  // Role is checked before the body is read, so a broker's upload is never parsed.
  router.post('/uploads', requireAnalyst, singleFile, async (req, res) => {
    const file = req.file;
    if (!file) throw new HttpError(400, 'Attach the claim file in the multipart field "file"');
    const result = await ingestClaimFile(db, {
      filename: file.originalname,
      content: file.buffer,
      uploadedBy: currentUser(res).email,
    });
    const c = result.counts;
    console.log(
      `upload #${result.id} ${result.filename} by ${result.uploadedBy}: ${result.status}` +
        (result.status === 'processed'
          ? ` rows=${c.rows} applied=${c.applied} unchanged=${c.unchanged} older=${c.olderIgnored} ` +
            `summary=${c.summary} errors=${c.errors} alerts=${result.alertsRaised.length}`
          : ` (${result.rejectionReason})`),
    );
    res.status(result.status === 'processed' ? 201 : 400).json(result);
  });

  router.get('/uploads', requireAnalyst, async (_req, res) => {
    res.json(await listUploads(db));
  });

  router.get('/uploads/:id', requireAnalyst, async (req, res) => {
    const id = parseId(req.params.id);
    const upload = id === null ? null : await getUpload(db, id);
    if (!upload) throw new HttpError(404, 'Upload not found');
    res.json(upload);
  });

  return router;
}
