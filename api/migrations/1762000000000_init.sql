-- Up Migration

-- Reference data -----------------------------------------------------------------------------------------------

CREATE TABLE brokerage (
  id   text PRIMARY KEY,
  name text NOT NULL UNIQUE
);

CREATE TABLE employer_group (
  id           text PRIMARY KEY,
  name         text NOT NULL,
  brokerage_id text REFERENCES brokerage (id)
);
CREATE INDEX employer_group_brokerage_idx ON employer_group (brokerage_id);

CREATE TABLE policy_year (
  id                        serial PRIMARY KEY,
  group_id                  text   NOT NULL REFERENCES employer_group (id),
  start_date                date   NOT NULL,
  end_date                  date   NOT NULL,
  specific_deductible_cents bigint NOT NULL CHECK (specific_deductible_cents > 0),
  UNIQUE (group_id, start_date),
  CHECK (end_date >= start_date)
);

-- A service date must map to at most one policy year of a group, so policy years must not overlap.
-- (An EXCLUDE constraint would need the btree_gist extension; a trigger keeps the migration extension-free.)
CREATE FUNCTION policy_year_no_overlap() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM policy_year p
    WHERE p.group_id = NEW.group_id
      AND p.id <> NEW.id
      AND daterange(p.start_date, p.end_date, '[]') && daterange(NEW.start_date, NEW.end_date, '[]')
  ) THEN
    RAISE EXCEPTION 'policy year % to % overlaps another policy year of group %',
      NEW.start_date, NEW.end_date, NEW.group_id;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER policy_year_no_overlap
  BEFORE INSERT OR UPDATE ON policy_year
  FOR EACH ROW EXECUTE FUNCTION policy_year_no_overlap();

CREATE TABLE member (
  id            text PRIMARY KEY,
  group_id      text NOT NULL REFERENCES employer_group (id),
  first_name    text NOT NULL,
  last_name     text NOT NULL,
  date_of_birth date,
  relationship  text
);
CREATE INDEX member_group_idx ON member (group_id);

CREATE TABLE laser (
  member_id                 text   NOT NULL REFERENCES member (id),
  policy_year_id            int    NOT NULL REFERENCES policy_year (id),
  specific_deductible_cents bigint NOT NULL CHECK (specific_deductible_cents > 0),
  PRIMARY KEY (member_id, policy_year_id)
);

CREATE TABLE app_user (
  id           serial PRIMARY KEY,
  email        text NOT NULL UNIQUE CHECK (email = lower(email)),
  role         text NOT NULL CHECK (role IN ('analyst', 'broker')),
  brokerage_id text REFERENCES brokerage (id),
  -- a broker always belongs to a brokerage; an analyst never does
  CHECK ((role = 'broker') = (brokerage_id IS NOT NULL))
);

-- Uploads ------------------------------------------------------------------------------------------------------

CREATE TABLE upload (
  id                  bigserial PRIMARY KEY,
  group_id            text REFERENCES employer_group (id),   -- null when the file-name prefix is not a known group
  filename            text        NOT NULL,
  sha256              text        NOT NULL,
  size_bytes          int         NOT NULL,
  layout              text,                                   -- null when the header was not recognised
  status              text        NOT NULL CHECK (status IN ('processed', 'rejected')),
  rejection_reason    text,
  uploaded_by         text        NOT NULL,
  uploaded_at         timestamptz NOT NULL DEFAULT now(),
  row_count           int         NOT NULL DEFAULT 0,
  applied_count       int         NOT NULL DEFAULT 0,
  unchanged_count     int         NOT NULL DEFAULT 0,
  older_ignored_count int         NOT NULL DEFAULT 0,
  summary_count       int         NOT NULL DEFAULT 0,
  error_count         int         NOT NULL DEFAULT 0,
  CHECK ((status = 'rejected') = (rejection_reason IS NOT NULL))
);
CREATE INDEX upload_uploaded_at_idx ON upload (uploaded_at DESC, id DESC);

CREATE TABLE upload_row_error (
  id         bigserial PRIMARY KEY,
  upload_id  bigint NOT NULL REFERENCES upload (id) ON DELETE CASCADE,
  row_number int    NOT NULL,
  reason     text   NOT NULL,
  raw        text   NOT NULL
);
CREATE INDEX upload_row_error_upload_idx ON upload_row_error (upload_id, row_number);

-- Claims -------------------------------------------------------------------------------------------------------

-- Every accepted row is one version of a claim (including versions older than the current one).
CREATE TABLE claim_version (
  id                bigserial PRIMARY KEY,
  group_id          text        NOT NULL REFERENCES employer_group (id),
  claim_id          text        NOT NULL,
  version           int         NOT NULL CHECK (version >= 0),
  member_id         text        NOT NULL REFERENCES member (id),
  service_date      date        NOT NULL,
  paid_date         date,
  paid_amount_cents bigint      NOT NULL CHECK (paid_amount_cents >= 0),
  status            text        NOT NULL CHECK (status IN ('paid', 'reversed')),
  diagnosis_code    text,
  upload_id         bigint      NOT NULL REFERENCES upload (id),
  row_number        int         NOT NULL,
  loaded_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (group_id, claim_id, version)
);
CREATE INDEX claim_version_member_idx ON claim_version (member_id);
CREATE INDEX claim_version_upload_idx ON claim_version (upload_id);

-- The highest version of each claim, with its policy year and effective amount (0 when reversed/void).
-- Maintained by the ingest transaction; spend is SUM(effective_amount_cents) per (member, policy year).
CREATE TABLE claim_current (
  group_id               text   NOT NULL,
  claim_id               text   NOT NULL,
  claim_version_id       bigint NOT NULL UNIQUE REFERENCES claim_version (id),
  version                int    NOT NULL,
  member_id              text   NOT NULL REFERENCES member (id),
  service_date           date   NOT NULL,
  policy_year_id         int REFERENCES policy_year (id),       -- null: service date outside every policy year
  effective_amount_cents bigint NOT NULL CHECK (effective_amount_cents >= 0),
  PRIMARY KEY (group_id, claim_id)
);
CREATE INDEX claim_current_policy_year_member_idx ON claim_current (policy_year_id, member_id);
CREATE INDEX claim_current_member_idx ON claim_current (member_id);

-- Alerts -------------------------------------------------------------------------------------------------------

CREATE TABLE alert (
  id                          bigserial PRIMARY KEY,
  group_id                    text        NOT NULL REFERENCES employer_group (id),
  member_id                   text        NOT NULL REFERENCES member (id),
  policy_year_id              int         NOT NULL REFERENCES policy_year (id),
  threshold                   int         NOT NULL CHECK (threshold IN (50, 75, 100)),
  spend_cents_at_trigger      bigint      NOT NULL,
  deductible_cents_at_trigger bigint      NOT NULL,
  upload_id                   bigint REFERENCES upload (id),
  status                      text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'acknowledged')),
  created_at                  timestamptz NOT NULL DEFAULT now(),
  acknowledged_by             text,
  acknowledged_at             timestamptz,
  ack_note                    text,
  -- each threshold alerts at most once per member and policy year (rule: no re-alert after a drop and rise)
  UNIQUE (member_id, policy_year_id, threshold),
  CHECK ((status = 'acknowledged') =
         (acknowledged_by IS NOT NULL AND acknowledged_at IS NOT NULL AND ack_note IS NOT NULL))
);
CREATE INDEX alert_status_created_idx ON alert (status, created_at DESC);
CREATE INDEX alert_group_idx ON alert (group_id);
CREATE INDEX alert_upload_idx ON alert (upload_id);

-- Down Migration

DROP TABLE alert;
DROP TABLE claim_current;
DROP TABLE claim_version;
DROP TABLE upload_row_error;
DROP TABLE upload;
DROP TABLE app_user;
DROP TABLE laser;
DROP TABLE member;
DROP TRIGGER policy_year_no_overlap ON policy_year;
DROP FUNCTION policy_year_no_overlap();
DROP TABLE policy_year;
DROP TABLE employer_group;
DROP TABLE brokerage;
