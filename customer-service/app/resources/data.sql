-- No credential seed — all logins live in auth-service (the central IdP).
-- Customer DOMAIN rows are created at registration (which provisions the
-- credential in auth-service and stores the profile here keyed by userId).
--
-- The demo customer 'j2ee' (userId c0000000-...-001, seeded in auth-service) gets
-- a full profile here so the classic demo works out of the box: checkout pre-fills
-- the saved ship-to/bill-to address (legacy behaviour — the address is captured at
-- registration and re-shown on the order form, not re-typed each time).
-- H2 MERGE (upsert) keyed on user_id so re-running is idempotent across restarts.
MERGE INTO customer (user_id, given_name, family_name, email, telephone,
        street1, street2, city, state, zip_code, country, status,
        preferred_language, favorite_category, my_list_pref, banner_pref,
        card_number, card_type, card_expiry)
    KEY(user_id) VALUES
 ('c0000000-0000-4000-8000-000000000001', 'ABC', 'XYZ',
  'j2ee@petstore.com', '555-123-4567',
  '1 Network Drive', NULL, 'Burlington', 'MA', '01803', 'United States', 'active',
  'en_US', NULL, TRUE, TRUE,
  '4111111111111111', 'Visa', '12/29');
