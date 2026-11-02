-- Ported unchanged from the Node API's migration 1762000000001_policy_year_overlap_allows_upsert.
-- `INSERT ... ON CONFLICT (group_id, start_date) DO UPDATE` (the idempotent seed) fires BEFORE INSERT triggers
-- *before* the conflict is detected, with a fresh NEW.id, so the existing row for the same (group_id, start_date)
-- was reported as an overlap and every re-seed failed. That row is the one the upsert updates (or the unique
-- constraint rejects), so it is not an overlap. The UPDATE that follows still checks against every other year.
CREATE OR REPLACE FUNCTION policy_year_no_overlap() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM policy_year p
    WHERE p.group_id = NEW.group_id
      AND p.id <> NEW.id
      AND p.start_date <> NEW.start_date
      AND daterange(p.start_date, p.end_date, '[]') && daterange(NEW.start_date, NEW.end_date, '[]')
  ) THEN
    RAISE EXCEPTION 'policy year % to % overlaps another policy year of group %',
      NEW.start_date, NEW.end_date, NEW.group_id;
  END IF;
  RETURN NEW;
END;
$$;
