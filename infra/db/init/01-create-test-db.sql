-- Runs once, when the db container starts with an empty volume.
-- A separate database for `make test-api`: the tests create/truncate their own schema, so
-- they must never point at the dev database the app is using.
CREATE DATABASE stoploss_test;
