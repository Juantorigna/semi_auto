-- ============================================================
--  GESTIONALE 4.0 — DB SCHEMA (reconstructed from source)
-- ============================================================

-- ── 1. registrati (ospiti presenti) ──────────────────────────
CREATE TABLE `registrati` (
  `id_registrato`        INT UNSIGNED     NOT NULL AUTO_INCREMENT,
  `Registration Code`    VARCHAR(50)      NOT NULL,
  `Name`                 VARCHAR(255)     NOT NULL,
  `Nationality`          VARCHAR(100)     DEFAULT NULL,
  `Telefono`             VARCHAR(50)      DEFAULT NULL,
  `Mail`                 VARCHAR(255)     DEFAULT NULL,
  `Quanti`               INT UNSIGNED     DEFAULT NULL,
  `Category`             VARCHAR(50)      DEFAULT NULL,
  `Caravan Confirmation` VARCHAR(50)      DEFAULT NULL,
  `Lunghezza`            FLOAT            DEFAULT NULL,
  `Plate`                VARCHAR(50)      DEFAULT NULL,
  `Piazzola`             TINYINT UNSIGNED DEFAULT NULL,   -- 1–49
  `Chiavetta`            VARCHAR(3)       DEFAULT NULL,   -- solo registrati
  `Corrente`             VARCHAR(50)      DEFAULT NULL,
  `Notes`                TEXT             DEFAULT NULL,
  `Paid`                 VARCHAR(50)      DEFAULT NULL,
  `Amount`               DECIMAL(10,2)    DEFAULT NULL,
  `PaidInAdvance`        VARCHAR(50)      DEFAULT NULL,
  `AmountAdvance`        DECIMAL(10,2)    DEFAULT NULL,
  `Has Car`              VARCHAR(10)      DEFAULT NULL,
  `Arrival DateTime`     DATETIME         DEFAULT NULL,
  `Departure DateTime`   DATETIME         DEFAULT NULL,
  `Total Charge`         DECIMAL(10,2)    DEFAULT NULL,
  `Timestamp`            DATE             DEFAULT NULL,
  `Data Consent`         VARCHAR(10)      DEFAULT NULL,
  `Mail Sent`            VARCHAR(10)      DEFAULT NULL,
  `Today Mail`           VARCHAR(10)      DEFAULT NULL,
  `Active`               CHAR(1)          NOT NULL DEFAULT 'Y',
  PRIMARY KEY (`id_registrato`),
  UNIQUE KEY `uq_registration_code` (`Registration Code`)
);

-- ── 2. prenotazioni (prenotazioni non ancora arrivate) ────────
--    Stessa struttura di registrati, NO Chiavetta
CREATE TABLE `prenotazioni` (
  `id_prenotazione`      INT UNSIGNED     NOT NULL AUTO_INCREMENT,
  `Registration Code`    VARCHAR(50)      NOT NULL,
  `Name`                 VARCHAR(255)     NOT NULL,
  `Nationality`          VARCHAR(100)     DEFAULT NULL,
  `Telefono`             VARCHAR(50)      DEFAULT NULL,
  `Mail`                 VARCHAR(255)     DEFAULT NULL,
  `Quanti`               INT UNSIGNED     DEFAULT NULL,
  `Category`             VARCHAR(50)      DEFAULT NULL,
  `Caravan Confirmation` VARCHAR(50)      DEFAULT NULL,
  `Lunghezza`            FLOAT            DEFAULT NULL,
  `Plate`                VARCHAR(50)      DEFAULT NULL,
  `Piazzola`             TINYINT UNSIGNED DEFAULT NULL,
  `Corrente`             VARCHAR(50)      DEFAULT NULL,
  `Notes`                TEXT             DEFAULT NULL,
  `Paid`                 VARCHAR(50)      DEFAULT NULL,
  `Amount`               DECIMAL(10,2)    DEFAULT NULL,
  `PaidInAdvance`        VARCHAR(50)      DEFAULT NULL,
  `AmountAdvance`        DECIMAL(10,2)    DEFAULT NULL,
  `Has Car`              VARCHAR(10)      DEFAULT NULL,
  `Arrival DateTime`     DATETIME         DEFAULT NULL,
  `Departure DateTime`   DATETIME         DEFAULT NULL,
  `Total Charge`         DECIMAL(10,2)    DEFAULT NULL,
  `Timestamp`            DATE             DEFAULT NULL,
  `Data Consent`         VARCHAR(10)      DEFAULT NULL,
  `Mail Sent`            VARCHAR(10)      DEFAULT NULL,
  `Today Mail`           VARCHAR(10)      DEFAULT NULL,
  `Active`               CHAR(1)          NOT NULL DEFAULT 'Y',
  PRIMARY KEY (`id_prenotazione`),
  UNIQUE KEY `uq_registration_code` (`Registration Code`)
);

-- ── 3. stanziali (ospiti a lungo termine) ─────────────────────
--    Come registrati, ma NO Chiavetta
CREATE TABLE `stanziali` (
  `id_stanziale`         INT UNSIGNED     NOT NULL AUTO_INCREMENT,
  `Registration Code`    VARCHAR(50)      NOT NULL,
  `Name`                 VARCHAR(255)     NOT NULL,
  `Nationality`          VARCHAR(100)     DEFAULT NULL,
  `Telefono`             VARCHAR(50)      DEFAULT NULL,
  `Mail`                 VARCHAR(255)     DEFAULT NULL,
  `Quanti`               INT UNSIGNED     DEFAULT NULL,
  `Category`             VARCHAR(50)      DEFAULT NULL,
  `Caravan Confirmation` VARCHAR(50)      DEFAULT NULL,
  `Lunghezza`            FLOAT            DEFAULT NULL,
  `Plate`                VARCHAR(50)      DEFAULT NULL,
  `Piazzola`             TINYINT UNSIGNED DEFAULT NULL,
  `Corrente`             VARCHAR(50)      DEFAULT NULL,
  `Notes`                TEXT             DEFAULT NULL,
  `Paid`                 VARCHAR(50)      DEFAULT NULL,
  `Amount`               DECIMAL(10,2)    DEFAULT NULL,
  `PaidInAdvance`        VARCHAR(50)      DEFAULT NULL,
  `AmountAdvance`        DECIMAL(10,2)    DEFAULT NULL,
  `Has Car`              VARCHAR(10)      DEFAULT NULL,
  `Arrival DateTime`     DATETIME         DEFAULT NULL,
  `Departure DateTime`   DATETIME         DEFAULT NULL,
  `Total Charge`         DECIMAL(10,2)    DEFAULT NULL,
  `Timestamp`            DATE             DEFAULT NULL,
  `Data Consent`         VARCHAR(10)      DEFAULT NULL,
  `Mail Sent`            VARCHAR(10)      DEFAULT NULL,
  `Today Mail`           VARCHAR(10)      DEFAULT NULL,
  `Active`               CHAR(1)          NOT NULL DEFAULT 'Y',
  PRIMARY KEY (`id_stanziale`),
  UNIQUE KEY `uq_registration_code` (`Registration Code`)
);

-- ── 4. rimessaggi (deposito veicoli) ─────────────────────────
--    Subset ridotto: no Quanti, Lunghezza, Caravan Confirmation,
--    Chiavetta, Data Consent, Mail Sent, Today Mail
CREATE TABLE `rimessaggi` (
  `id_rimessaggio`       INT UNSIGNED     NOT NULL AUTO_INCREMENT,
  `Registration Code`    VARCHAR(50)      NOT NULL,
  `Name`                 VARCHAR(255)     NOT NULL,
  `Nationality`          VARCHAR(100)     DEFAULT NULL,
  `Telefono`             VARCHAR(50)      DEFAULT NULL,
  `Mail`                 VARCHAR(255)     DEFAULT NULL,
  `Category`             VARCHAR(50)      DEFAULT NULL,
  `Plate`                VARCHAR(50)      DEFAULT NULL,
  `Piazzola`             TINYINT UNSIGNED DEFAULT NULL,
  `Corrente`             VARCHAR(50)      DEFAULT NULL,
  `Notes`                TEXT             DEFAULT NULL,
  `Paid`                 VARCHAR(50)      DEFAULT NULL,
  `Amount`               DECIMAL(10,2)    DEFAULT NULL,
  `PaidInAdvance`        VARCHAR(50)      DEFAULT NULL,
  `AmountAdvance`        DECIMAL(10,2)    DEFAULT NULL,
  `Has Car`              VARCHAR(10)      DEFAULT NULL,
  `Arrival DateTime`     DATETIME         DEFAULT NULL,
  `Departure DateTime`   DATETIME         DEFAULT NULL,
  `Total Charge`         DECIMAL(10,2)    DEFAULT NULL,
  `Timestamp`            DATE             DEFAULT NULL,
  `Active`               CHAR(1)          NOT NULL DEFAULT 'Y',
  PRIMARY KEY (`id_rimessaggio`),
  UNIQUE KEY `uq_registration_code` (`Registration Code`)
);

-- ── 5. usciti (ospiti usciti / archivio) ──────────────────────
--    Come registrati + Entry Timestamp + Payment Method
--    id_registrato è AUTO_INCREMENT indipendente (fonti miste)
CREATE TABLE `usciti` (
  `id_registrato`        INT UNSIGNED     NOT NULL AUTO_INCREMENT,
  `Registration Code`    VARCHAR(50)      NOT NULL,
  `Name`                 VARCHAR(255)     NOT NULL,
  `Nationality`          VARCHAR(100)     DEFAULT NULL,
  `Telefono`             VARCHAR(50)      DEFAULT NULL,
  `Mail`                 VARCHAR(255)     DEFAULT NULL,
  `Quanti`               INT UNSIGNED     DEFAULT NULL,
  `Category`             VARCHAR(50)      DEFAULT NULL,
  `Caravan Confirmation` VARCHAR(50)      DEFAULT NULL,
  `Lunghezza`            FLOAT            DEFAULT NULL,
  `Plate`                VARCHAR(50)      DEFAULT NULL,
  `Piazzola`             TINYINT UNSIGNED DEFAULT NULL,
  `Chiavetta`            VARCHAR(3)       DEFAULT NULL,   -- NULL se da stanziali/rimessaggi
  `Corrente`             VARCHAR(50)      DEFAULT NULL,
  `Notes`                TEXT             DEFAULT NULL,
  `Paid`                 VARCHAR(50)      DEFAULT NULL,
  `Amount`               DECIMAL(10,2)    DEFAULT NULL,
  `PaidInAdvance`        VARCHAR(50)      DEFAULT NULL,
  `AmountAdvance`        DECIMAL(10,2)    DEFAULT NULL,
  `Has Car`              VARCHAR(10)      DEFAULT NULL,
  `Arrival DateTime`     DATETIME         DEFAULT NULL,
  `Departure DateTime`   DATETIME         DEFAULT NULL,
  `Total Charge`         DECIMAL(10,2)    DEFAULT NULL,
  `Timestamp`            DATE             DEFAULT NULL,
  `Data Consent`         VARCHAR(10)      DEFAULT NULL,
  `Mail Sent`            VARCHAR(10)      DEFAULT NULL,
  `Today Mail`           VARCHAR(10)      DEFAULT NULL,
  `Active`               CHAR(1)          NOT NULL DEFAULT 'Y',
  `Entry Timestamp`      DATETIME         DEFAULT NULL,   -- usciti-only
  `Payment Method`       VARCHAR(50)      DEFAULT NULL,   -- usciti-only
  PRIMARY KEY (`id_registrato`)
);