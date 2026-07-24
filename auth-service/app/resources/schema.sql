-- auth-service owns the ONE credential store for all users (customers + staff).
-- Only authentication data lives here; customer profile/cards live in customer-service.
CREATE TABLE IF NOT EXISTS account (
    user_name VARCHAR(25) NOT NULL,
    password  VARCHAR(120) NOT NULL,
    user_id   VARCHAR(40) NOT NULL,
    role      VARCHAR(20) NOT NULL,
    CONSTRAINT pk_account PRIMARY KEY (user_name),
    CONSTRAINT uq_account_userid UNIQUE (user_id)
);
