import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { parseClaimFile, type ParsedLine } from '../../src/domain/claimFile.js';
import { detectLayout } from '../../src/domain/layouts.js';

const DATA = path.resolve(import.meta.dirname, '../../../data/claims');
const sample = (name: string) => readFileSync(path.join(DATA, name));

const ONE_HEADER = 'Claim ID,Adj Seq,Member ID,Service Date,Paid Date,Paid Amount,Status,Diagnosis Code';
const TWO_HEADER = 'claim_number,version,member,dos,paid_dt,amount,claim_status';

function parseOk(text: string | Buffer) {
  const r = parseClaimFile(text);
  if (!r.ok) throw new Error(r.reason);
  return r;
}
const at = (lines: ParsedLine[], rowNumber: number) => lines.find((l) => l.rowNumber === rowNumber);

describe('layout detection', () => {
  it('recognises both TPA headers', () => {
    expect(parseOk(`${ONE_HEADER}\r\n`).layout.id).toBe('tpa_one');
    expect(parseOk(`# TPA Two claims extract\n${TWO_HEADER}\r\n`).layout.id).toBe('tpa_two');
  });

  it('ignores case, surrounding whitespace and punctuation in header names', () => {
    const r = detectLayout(['CLAIM ID', ' adj seq ', 'member_id', 'Service Date', 'PAID DATE', 'paid amount', 'status', 'diagnosis code']);
    expect(r.ok && r.layout.id).toBe('tpa_one');
  });

  it('maps columns by name, not position, and ignores unknown extra columns', () => {
    const text = [
      'claim_status,extra,member,claim_number,amount,dos,version,paid_dt,another',
      'V,zzz, B-0001 ,77001,"$1,000.00",2026-04-01,2,2026-04-20,',
    ].join('\r\n');
    const r = parseOk(text);
    expect(r.layout.id).toBe('tpa_two');
    expect(r.lines[0]).toMatchObject({
      kind: 'claim',
      rowNumber: 2,
      claim: {
        claimId: '77001',
        version: 2,
        memberId: 'B-0001',
        serviceDate: '2026-04-01',
        paidDate: '2026-04-20',
        amountCents: 100000,
        status: 'reversed',
        diagnosisCode: null,
      },
    });
  });

  it('rejects an unknown header', () => {
    const r = parseClaimFile('foo,bar,baz\r\n1,2,3\r\n');
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.reason).toMatch(/unrecognised header/);
  });

  it('explains which columns are missing when the header is close to a layout', () => {
    const r = parseClaimFile('Claim ID,Adj Seq,Member ID,Service Date,Paid Date,Paid Amount\r\n');
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.reason).toBe('header looks like layout tpa_one but is missing column(s): Status');
  });

  it('rejects duplicate header columns', () => {
    const r = parseClaimFile(`${ONE_HEADER},Status\r\n`);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.reason).toMatch(/duplicate column "Status"/);
  });

  it('rejects an empty file and a comment-only file', () => {
    expect(parseClaimFile('').ok).toBe(false);
    expect(parseClaimFile('# just a comment\n\n').ok).toBe(false);
  });

  it('handles BOM, LF-only files, blank lines and trailing whitespace', () => {
    const text = `﻿\n${ONE_HEADER}\n\n100,0,A-0001,01/05/2026,01/09/2026,10.00,PAID,I10   \n\n,,,,,,,\n`;
    const r = parseOk(text);
    expect(r.headerRowNumber).toBe(2);
    expect(r.lines).toHaveLength(1);
    expect(r.lines[0]).toMatchObject({ kind: 'claim', rowNumber: 4, claim: { diagnosisCode: 'I10', amountCents: 1000 } });
  });
});

describe('tpa_one rows (sample files)', () => {
  it('GA_2026-01: TRAILER on line 223 is a summary row, not an error; quoted thousands parse', () => {
    const r = parseOk(sample('GA_2026-01.csv'));
    expect(r.layout.id).toBe('tpa_one');
    const errors = r.lines.filter((l) => l.kind === 'error');
    expect(errors).toEqual([]);
    expect(at(r.lines, 223)).toMatchObject({ kind: 'summary', raw: 'TRAILER,221,,,,"272,718.78",,' });
    expect(r.lines.filter((l) => l.kind === 'claim')).toHaveLength(221);
    // line 2 is the first data row, with a 2025 service date (stored, counts toward no GA policy year)
    expect(at(r.lines, 2)).toMatchObject({
      kind: 'claim',
      claim: { claimId: '100190', version: 0, memberId: 'A-0099', serviceDate: '2025-12-13', paidDate: '2026-01-02', amountCents: 79068, status: 'paid', diagnosisCode: 'J45.909' },
    });
  });

  it('GA_2026-05 line 18: amount N/A is a row error', () => {
    const r = parseOk(sample('GA_2026-05.csv'));
    const errors = r.lines.filter((l) => l.kind === 'error');
    expect(errors).toEqual([
      {
        kind: 'error',
        rowNumber: 18,
        raw: '101995,0,A-0030,05/02/2026,05/12/2026,N/A,PAID,Z00.00',
        reason: 'Invalid paid amount "N/A"',
      },
    ]);
  });

  it('GA_2026-06 line 35: REVERSED maps to reversed and keeps the row amount', () => {
    const r = parseOk(sample('GA_2026-06.csv'));
    expect(at(r.lines, 35)).toMatchObject({
      kind: 'claim',
      claim: { claimId: '101988', version: 1, memberId: 'A-0009', amountCents: 3000000, status: 'reversed' },
    });
  });
});

describe('tpa_two rows (sample files)', () => {
  it('GB_2026-04: comment line 1 (LF) + header line 2, trims padded member IDs, parses $ amounts', () => {
    const r = parseOk(sample('GB_2026-04.csv'));
    expect(r.layout.id).toBe('tpa_two');
    expect(r.headerRowNumber).toBe(2);
    expect(r.lines.filter((l) => l.kind !== 'claim')).toEqual([]);
    expect(r.lines).toHaveLength(181);
    expect(at(r.lines, 3)).toMatchObject({
      kind: 'claim',
      raw: '7700076,1, B-0179 ,2026-03-15,2026-04-09,$615.09,P',
      claim: { claimId: '7700076', version: 1, memberId: 'B-0179', serviceDate: '2026-03-15', amountCents: 61509, status: 'paid' },
    });
    expect(at(r.lines, 77)).toMatchObject({ claim: { memberId: 'B-0046', amountCents: 453216 } }); // $4,532.16
    expect(at(r.lines, 166)).toMatchObject({ claim: { amountCents: 3000000 } }); // $30000.00
  });

  it('every sample file parses with only the known bad rows', () => {
    const files = ['GA_2026-02.csv', 'GA_2026-03.csv', 'GA_2026-04.csv', 'GA_2026-07.csv', 'GA_2026-08.csv', 'GA_2026-09.csv',
      'GB_2026-05.csv', 'GB_2026-06.csv', 'GB_2026-07.csv', 'GB_2026-08.csv', 'GB_2026-09.csv'];
    for (const f of files) {
      const r = parseOk(sample(f));
      // Unknown/foreign members and conflicts are DB checks; parsing alone finds no errors in these files.
      expect(r.lines.filter((l) => l.kind === 'error'), f).toEqual([]);
    }
  });

  it('maps V to reversed (void)', () => {
    const r = parseOk(sample('GB_2026-09.csv'));
    expect(at(r.lines, 219)).toMatchObject({ claim: { claimId: '7700742', version: 2, status: 'reversed', amountCents: 37649 } });
  });
});

describe('bad rows', () => {
  const file = (...rows: string[]) => parseOk([ONE_HEADER, ...rows].join('\r\n'));
  const reasonOf = (row: string) => {
    const l = file(row).lines[0]!;
    expect(l.kind).toBe('error');
    return l.kind === 'error' ? l.reason : '';
  };

  it.each([
    ['1,0,A-0001,01/05/2026,01/09/2026,10.00,PAID', 'Expected 8 columns, got 7'],
    ['1,0,A-0001,01/05/2026,01/09/2026,14,455.00,PAID,I10', 'Expected 8 columns, got 9'], // unquoted thousands
    [',0,A-0001,01/05/2026,01/09/2026,10.00,PAID,I10', 'Claim ID is blank'],
    ['1,,A-0001,01/05/2026,01/09/2026,10.00,PAID,I10', 'Version is blank'],
    ['1,x,A-0001,01/05/2026,01/09/2026,10.00,PAID,I10', 'Invalid version "x" (expected a whole number ≥ 0)'],
    ['1,-1,A-0001,01/05/2026,01/09/2026,10.00,PAID,I10', 'Invalid version "-1" (expected a whole number ≥ 0)'],
    ['1,1.5,A-0001,01/05/2026,01/09/2026,10.00,PAID,I10', 'Invalid version "1.5" (expected a whole number ≥ 0)'],
    ['1,0,  ,01/05/2026,01/09/2026,10.00,PAID,I10', 'Member ID is blank'],
    ['1,0,A-0001,,01/09/2026,10.00,PAID,I10', 'Service date is blank'],
    ['1,0,A-0001,02/30/2026,01/09/2026,10.00,PAID,I10', 'Invalid service date "02/30/2026" (not a real calendar date)'],
    ['1,0,A-0001,01/05/2026,31/01/2026,10.00,PAID,I10', 'Invalid paid date "31/01/2026" (not a real calendar date)'],
    ['1,0,A-0001,01/05/2026,01/09/2026,,PAID,I10', 'Paid amount is blank'],
    ['1,0,A-0001,01/05/2026,01/09/2026,-10.00,PAID,I10', 'Negative paid amount "-10.00" (reversals must use the status column)'],
    ['1,0,A-0001,01/05/2026,01/09/2026,10.00,PENDING,I10', 'Unknown status "PENDING" (expected PAID/P or REVERSED/R/VOID/V)'],
    ['1,0,A-0001,01/05/2026,01/09/2026,10.00,,I10', 'Status is blank'],
    ['1,0,A-0001,01/05/2026,01/09/2026,"10.00,PAID,I10', 'Malformed CSV: unterminated quoted field'],
  ])('%s → %s', (row, reason) => {
    expect(reasonOf(row)).toBe(reason);
  });

  it('reports every problem in a row at once', () => {
    expect(reasonOf('1,0,A-0001,2026-02-30,,N/A,X,I10')).toBe(
      'Invalid service date "2026-02-30" (not a real calendar date); invalid paid amount "N/A"; ' +
        'unknown status "X" (expected PAID/P or REVERSED/R/VOID/V)',
    );
  });

  it('accepts optional blanks, status synonyms and trailing empty cells', () => {
    const r = file(
      '1,0,A-0001,01/05/2026,,10.00,paid,',
      '2,3,A-0001,2026-01-05,,10.00,Void,I10,,',
      '3,0,A-0001,01/05/2026,,10.00,r,I10',
      'total,,,,,"30.00",,',
      'Summary:,1,,,,,,',
    );
    expect(r.lines.map((l) => l.kind)).toEqual(['claim', 'claim', 'claim', 'summary', 'summary']);
    expect(r.lines[0]).toMatchObject({ claim: { paidDate: null, diagnosisCode: null, status: 'paid' } });
    expect(r.lines[1]).toMatchObject({ claim: { version: 3, status: 'reversed', serviceDate: '2026-01-05' } });
    expect(r.lines[2]).toMatchObject({ claim: { status: 'reversed' } });
  });

  it('numbers rows by physical line, counting comment, header and blank lines', () => {
    const text = '# extract\n\r\n' + TWO_HEADER + '\r\n\r\n1,1,B-0001,2026-04-01,,$1.00,P\r\nbad\r\n';
    const r = parseOk(text);
    expect(r.headerRowNumber).toBe(3);
    expect(r.lines.map((l) => [l.rowNumber, l.kind])).toEqual([
      [5, 'claim'],
      [6, 'error'],
    ]);
  });
});
