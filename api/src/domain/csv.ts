/**
 * Minimal RFC 4180 reader that works one *physical line* at a time.
 *
 * Why not a streaming CSV library: the analyst needs the physical line number of every bad row, a malformed line
 * must only fail that line (not swallow the rest of the file), and the sample files mix line endings (the
 * `#` comment line of TPA Two files ends in LF, the rest in CRLF). Splitting lines first and then parsing each line
 * gives all three. Quoted fields containing line breaks are not supported (claim files never contain them); such
 * a line is reported as a malformed row.
 */

export type CsvLineResult = { ok: true; cells: string[] } | { ok: false; reason: string };

/** Split file text into physical lines; accepts CRLF, LF and lone CR. Line i of the result is file line i + 1. */
export function splitLines(text: string): string[] {
  const lines = text.split(/\r\n|\n|\r/);
  // A trailing line break does not start another line.
  if (lines.length > 0 && lines[lines.length - 1] === '') lines.pop();
  return lines;
}

/** Decode file bytes as UTF-8 and drop a leading byte-order mark. */
export function decodeText(content: Buffer | string): string {
  const text = typeof content === 'string' ? content : new TextDecoder('utf-8').decode(content);
  return text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
}

/**
 * Parse one CSV line into raw (untrimmed) cells. Fields may be quoted with `"`; `""` inside a quoted field is a
 * literal quote; whitespace around a quoted field is tolerated. A quote in the middle of an unquoted field is kept
 * literally.
 */
export function parseCsvLine(line: string): CsvLineResult {
  const cells: string[] = [];
  let i = 0;
  const n = line.length;
  for (;;) {
    // Skip leading whitespace to detect a quoted field, but keep it for unquoted ones.
    let j = i;
    while (j < n && (line[j] === ' ' || line[j] === '\t')) j++;
    if (j < n && line[j] === '"') {
      let value = '';
      let k = j + 1;
      let closed = false;
      while (k < n) {
        const ch = line[k];
        if (ch === '"') {
          if (line[k + 1] === '"') {
            value += '"';
            k += 2;
            continue;
          }
          closed = true;
          k++;
          break;
        }
        value += ch;
        k++;
      }
      if (!closed) return { ok: false, reason: 'malformed CSV: unterminated quoted field' };
      while (k < n && (line[k] === ' ' || line[k] === '\t')) k++;
      if (k < n && line[k] !== ',') return { ok: false, reason: 'malformed CSV: unexpected text after a quoted field' };
      cells.push(value);
      if (k >= n) return { ok: true, cells };
      i = k + 1;
    } else {
      const comma = line.indexOf(',', i);
      if (comma === -1) {
        cells.push(line.slice(i));
        return { ok: true, cells };
      }
      cells.push(line.slice(i, comma));
      i = comma + 1;
    }
  }
}
