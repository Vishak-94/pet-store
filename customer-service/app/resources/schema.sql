-- customer-service owns ONLY the customer DOMAIN (profile/account/card).
-- Credentials live in auth-service; there is no app_user table here anymore.
-- customer.user_id is the opaque id minted by auth-service (a UUID).

CREATE TABLE IF NOT EXISTS customer (
    user_id VARCHAR(40) NOT NULL,
    given_name VARCHAR(80),
    family_name VARCHAR(80),
    email VARCHAR(120),
    telephone VARCHAR(40),
    street1 VARCHAR(120),
    street2 VARCHAR(120),
    city VARCHAR(80),
    state VARCHAR(80),
    zip_code VARCHAR(20),
    country VARCHAR(80),
    preferred_language VARCHAR(20),
    favorite_category VARCHAR(20),
    my_list_pref BOOLEAN NOT NULL DEFAULT FALSE,
    banner_pref BOOLEAN NOT NULL DEFAULT FALSE,
    card_number VARCHAR(30),
    card_type VARCHAR(30),
    card_expiry VARCHAR(10),
    CONSTRAINT pk_customer PRIMARY KEY (user_id)
);
