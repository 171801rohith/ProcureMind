-- Seed the fixed role set. See docs/AUTH_IMPLEMENTATION_PLAN.md section 12.

INSERT INTO roles (id, name)
VALUES (1, 'ADMIN'),
       (2, 'ANALYST'),
       (3, 'VIEWER')
ON CONFLICT (id) DO NOTHING;
