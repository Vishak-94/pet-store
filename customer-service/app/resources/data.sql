-- No credential seed — all logins live in auth-service (the central IdP).
-- Customer DOMAIN rows are created at registration (which provisions the
-- credential in auth-service and stores the profile here keyed by userId).
-- The demo customer 'j2ee' (userId c0000000-...-001) is seeded in auth-service;
-- its profile row is created lazily / can be added here if a profile is needed.
SELECT 1;
