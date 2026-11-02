import { describe, expect, it } from 'vitest';
import { decodeText, parseCsvLine, splitLines } from '../../src/domain/csv.js';
import { parseDate } from '../../src/domain/dates.js';

describe('parseDate', () => {
  it.each([
    ['12/13/2025', '2025-12-13'],
    ['01/02/2026', '2026-01-02'],
    ['1/2/2026', '2026-01-02'],
    ['2026-03-15', '2026-03-15'],
    [' 2026-06-30 ', '2026-06-30'],
    ['02/29/2028', '2028-02-29'], // leap year
    ['2000-02-29', '2000-02-29'], // divisible by 400
  ])('%s → %s', (input, expected) => {
    expect(parseDate(input, 'service date')).toEqual({ ok: true, date: expected });
  });

  it.each([
    ['02/30/2026', /not a real calendar date/],
    ['02/29/2026', /not a real calendar date/], // not a leap year
    ['1900-02-29', /not a real calendar date/], // divisible by 100, not 400
    ['13/01/2026', /not a real calendar date/], // DD/MM is not accepted
    ['2026-13-01', /not a real calendar date/],
    ['2026-04-31', /not a real calendar date/],
    ['00/10/2026', /not a real calendar date/],
    ['2026/03/15', /expected MM\/DD\/YYYY or YYYY-MM-DD/],
    ['15-03-2026', /expected/],
    ['20260315', /expected/],
    ['03/15/26', /expected/],
    ['yesterday', /expected/],
    ['', /service date is blank/],
  ])('rejects %j', (input, reason) => {
    const r = parseDate(input, 'service date');
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.reason).toMatch(reason);
  });
});

describe('splitLines / decodeText', () => {
  it('handles CRLF, LF, lone CR and a mix (TPA Two comment line ends in LF, the rest in CRLF)', () => {
    expect(splitLines('a\r\nb\r\nc\r\n')).toEqual(['a', 'b', 'c']);
    expect(splitLines('a\nb\nc')).toEqual(['a', 'b', 'c']);
    expect(splitLines('# comment\nh1,h2\r\n1,2\r\n')).toEqual(['# comment', 'h1,h2', '1,2']);
    expect(splitLines('a\rb')).toEqual(['a', 'b']);
    expect(splitLines('a\r\n\r\nb\r\n')).toEqual(['a', '', 'b']); // blank lines keep their line numbers
  });
  it('strips a UTF-8 BOM', () => {
    const buf = Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from('Claim ID,x')]);
    expect(decodeText(buf)).toBe('Claim ID,x');
  });
});

describe('parseCsvLine', () => {
  it('splits plain and quoted fields', () => {
    expect(parseCsvLine('100190,0,A-0099,12/13/2025,01/02/2026,790.68,PAID,J45.909')).toEqual({
      ok: true,
      cells: ['100190', '0', 'A-0099', '12/13/2025', '01/02/2026', '790.68', 'PAID', 'J45.909'],
    });
    expect(parseCsvLine('100624,0,A-0111,03/15/2026,03/28/2026,"1,953.51",PAID,E11.9')).toEqual({
      ok: true,
      cells: ['100624', '0', 'A-0111', '03/15/2026', '03/28/2026', '1,953.51', 'PAID', 'E11.9'],
    });
    expect(parseCsvLine('7700508,1, B-0200 ,2026-05-27,2026-06-22,"$10,987.25",P')).toEqual({
      ok: true,
      cells: ['7700508', '1', ' B-0200 ', '2026-05-27', '2026-06-22', '$10,987.25', 'P'],
    });
  });
  it('keeps empty cells, including trailing ones', () => {
    expect(parseCsvLine('TRAILER,221,,,,"272,718.78",,')).toEqual({
      ok: true,
      cells: ['TRAILER', '221', '', '', '', '272,718.78', '', ''],
    });
    expect(parseCsvLine('')).toEqual({ ok: true, cells: [''] });
  });
  it('handles escaped quotes and whitespace around quoted fields', () => {
    expect(parseCsvLine('"a ""b"" c", "x" ,y')).toEqual({ ok: true, cells: ['a "b" c', 'x', 'y'] });
  });
  it('reports malformed quoting instead of guessing', () => {
    expect(parseCsvLine('1,"unterminated,2').ok).toBe(false);
    expect(parseCsvLine('1,"a"b,2').ok).toBe(false);
  });
});
