-- All users' credentials in one place (customers + staff), BCrypt-hashed.
-- Raw creds: j2ee/j2ee (USER/customer), supplier/supplier (SUPPLIER), admin/admin (ADMIN).
-- userId is the stable opaque id other services reference.
MERGE INTO account (user_name, password, user_id, role) KEY(user_name) VALUES
 ('j2ee',     '{bcrypt}$2a$10$WGI9SYWO2KbDtyx.088oUuLyoP3TRPfluWacpXauEGiyuLmzfr0TO', 'c0000000-0000-4000-8000-000000000001', 'USER'),
 ('supplier', '{bcrypt}$2a$10$v71ZOZOjD7DA8aZ5vdOlxOu6LdDp3afBR9OW1ACjdY6EfNmKDn3Ri', 'c0000000-0000-4000-8000-000000000003', 'SUPPLIER'),
 ('admin',    '{bcrypt}$2a$10$nOdOCsNgaIia.UvSKo4jGu8Xie/X5uclucgbE2qfoJvzMoInJf/Ri', 'c0000000-0000-4000-8000-000000000002', 'ADMIN');
