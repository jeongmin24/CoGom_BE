CREATE TABLE IF NOT EXISTS cogom_account (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    nickname VARCHAR(32) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    CONSTRAINT uq_cogom_account_email UNIQUE (email)
);
